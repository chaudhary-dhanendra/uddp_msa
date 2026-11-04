package gov.uddp.core.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Versioned database target for the legacy YAML catalogue; YAML remains the compatibility reader in A4. */
@Service
public class SchemePackService {
  private final PostgresConnectionFactory connections; private final ObjectMapper json;
  private final gov.uddp.core.rules.MakerChecker approvals;
  public SchemePackService(PostgresConnectionFactory connections, ObjectMapper json) {
    this.connections=connections; this.json=json;
    this.approvals=new gov.uddp.core.rules.MakerChecker(connections, "scheme_pack_version", "scheme_pack_version_id", "Scheme Pack version");
  }
  public int importCatalogue(SchemeCatalogue catalogue) {
    int imported=0;
    try (var c=connections.open()) { c.setAutoCommit(false); try {
      for (var department: catalogue.departments()) try (var s=c.prepareStatement("insert into department(department_code,name) values(?,?) on conflict(department_code) do nothing")) { s.setString(1,department.code());s.setString(2,department.name());s.executeUpdate(); }
      for (var scheme: catalogue.schemes()) {
        try(var s=c.prepareStatement("insert into scheme_pack(scheme_id,owning_department_code) values(?,?) on conflict(scheme_id) do nothing")){s.setString(1,scheme.schemeId());s.setString(2,scheme.department());s.executeUpdate();}
        try(var exists=c.prepareStatement("select 1 from scheme_pack_version where scheme_id=? and version=1")){exists.setString(1,scheme.schemeId());if(exists.executeQuery().next())continue;}
        UUID id=UUID.randomUUID(); try(var s=c.prepareStatement("insert into scheme_pack_version(scheme_pack_version_id,scheme_id,version,status,name,beneficiary_level,benefit_type,benefit_form,unit,frequency,effective_from,catalogue_payload) values(?,?,1,'APPROVED',?,?,?,?,?,?,?,cast(? as jsonb))")){s.setObject(1,id);s.setString(2,scheme.schemeId());s.setString(3,scheme.name());s.setString(4,"FAMILY".equals(scheme.beneficiaryLevel())?"HOUSEHOLD":"INDIVIDUAL");s.setString(5,scheme.benefitType());s.setString(6,scheme.benefitForm());s.setString(7,scheme.unit());s.setString(8,scheme.frequency());s.setDate(9,Date.valueOf(LocalDate.of(1970,1,1)));s.setString(10,json.writeValueAsString(scheme));s.executeUpdate();}
        try(var s=c.prepareStatement("insert into scheme_pack_implementing_department values(?,?)")){s.setObject(1,id);s.setString(2,scheme.department());s.executeUpdate();}
        if(scheme.exclusivityGroup()!=null)try(var s=c.prepareStatement("insert into scheme_pack_exclusivity_group values(?,?)")){s.setObject(1,id);s.setString(2,scheme.exclusivityGroup());s.executeUpdate();}
        imported++;
      } c.commit(); return imported;
    } catch(Exception e){c.rollback();throw e;} } catch(Exception e){throw new IllegalStateException("Cannot import scheme catalogue",e);}
  }
  @io.swagger.v3.oas.annotations.media.Schema(name = "SchemePackVersion")
  public record Version(String schemeId, UUID id, int version, String status, String name, String beneficiaryLevel, String owner,
                        LocalDate effectiveFrom, LocalDate effectiveTo, UUID eligibilityRuleSetId,
                        List<String> implementingDepartments, List<String> sourcePacks, List<String> exclusivityGroups,
                        String configuration, Details details) { }

  /** A source code of a Source Pack that means this scheme (for example a card type or pension type code). */
  @io.swagger.v3.oas.annotations.media.Schema(name = "SchemeCodeCrosswalk")
  public record CodeCrosswalk(String packId, String sourceCode) { }

  /** A4 fields beyond the catalogue basics; classification is CENTRAL, STATE or CENTRALLY_SPONSORED. */
  @io.swagger.v3.oas.annotations.media.Schema(name = "SchemePackDetails")
  public record Details(String classification, String goReference, String benefitType, String benefitForm, String unit,
                        String frequency, java.math.BigDecimal amount, java.math.BigDecimal quantity,
                        List<CodeCrosswalk> codeCrosswalks) {
    public Details {
      codeCrosswalks = codeCrosswalks == null ? List.of() : List.copyOf(codeCrosswalks);
    }
  }
  @io.swagger.v3.oas.annotations.media.Schema(name = "SchemePackDraft")
  public record Draft(String schemeId,String name,String owner,String beneficiaryLevel,String benefitType,String benefitForm,String effectiveFrom,String effectiveTo,UUID eligibilityRuleSetId,List<String> implementingDepartments,List<String> sourcePacks,List<String> exclusivityGroups,String configuration,Details details) {
    public Draft(String schemeId,String name,String owner,String beneficiaryLevel,String benefitType,String benefitForm,String effectiveFrom,String effectiveTo,UUID eligibilityRuleSetId,List<String> implementingDepartments,List<String> sourcePacks,List<String> exclusivityGroups,String configuration) {
      this(schemeId,name,owner,beneficiaryLevel,benefitType,benefitForm,effectiveFrom,effectiveTo,eligibilityRuleSetId,implementingDepartments,sourcePacks,exclusivityGroups,configuration,null);
    }
  }
  public Version createDraft(Draft draft){return write(draft, null);}
  /** A new DRAFT version authored by {@code maker}; it becomes effective only after another user approves it. */
  public Version createDraft(Draft draft, String maker){return write(draft, maker);}

  public Version submit(String schemeId, UUID versionId, String actor) {
    approvals.submit(versionId, actor);
    return version(schemeId, versionId);
  }

  /** A different user approves; a linked eligibility rule set must itself be APPROVED first. */
  public Version approve(String schemeId, UUID versionId, String approver, String reason) {
    Version pending = version(schemeId, versionId);
    if (pending.eligibilityRuleSetId() != null) {
      String status = ruleSetStatus(pending.eligibilityRuleSetId());
      if (!"APPROVED".equals(status)) {
        throw new gov.uddp.core.rules.ApprovalConflictException("The linked eligibility rule set is " + status
            + "; approve it before the Scheme Pack version");
      }
    }
    approvals.approve(versionId, approver, reason);
    return version(schemeId, versionId);
  }

  public Version reject(String schemeId, UUID versionId, String approver, String reason) {
    approvals.reject(versionId, approver, reason);
    return version(schemeId, versionId);
  }

  public Version version(String schemeId, UUID versionId) {
    return history(schemeId).stream().filter(x -> x.id().equals(versionId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Scheme Pack version does not exist: " + versionId));
  }

  private String ruleSetStatus(UUID ruleSetId) {
    try (var c = connections.open(); var s = c.prepareStatement("select status from rule_set where rule_set_id=?")) {
      s.setObject(1, ruleSetId);
      var r = s.executeQuery();
      return r.next() ? r.getString(1) : "MISSING";
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read the eligibility rule set", e);
    }
  }
  public Version updateDraft(UUID id, Draft draft){try(var c=connections.open();var s=c.prepareStatement("update scheme_pack_version set name=?,beneficiary_level=?,benefit_type=?,benefit_form=?,effective_from=?,effective_to=?,eligibility_rule_set_id=?,catalogue_payload=cast(? as jsonb) where scheme_pack_version_id=? and status='DRAFT'")){s.setString(1,draft.name());s.setString(2,draft.beneficiaryLevel());s.setString(3,draft.benefitType());s.setString(4,draft.benefitForm());s.setDate(5,Date.valueOf(draft.effectiveFrom()));if(draft.effectiveTo()==null)s.setNull(6,java.sql.Types.DATE);else s.setDate(6,Date.valueOf(draft.effectiveTo()));s.setObject(7,draft.eligibilityRuleSetId());s.setString(8,draft.configuration()==null?"{}":draft.configuration());s.setObject(9,id);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Only an existing DRAFT Scheme Pack version may be edited");saveDetails(c,id,draft.details());}catch(Exception e){if(e instanceof IllegalArgumentException)throw (IllegalArgumentException)e;throw new IllegalStateException("Cannot update Scheme Pack draft",e);}return history(draft.schemeId()).stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();}
  public Version newVersion(String schemeId, UUID sourceVersion){return newVersion(schemeId, sourceVersion, null);}
  public Version newVersion(String schemeId, UUID sourceVersion, String maker){Version source=history(schemeId).stream().filter(x->x.id().equals(sourceVersion)).findFirst().orElseThrow();Details details=source.details();return write(new Draft(schemeId,source.name(),source.owner(),source.beneficiaryLevel(),details.benefitType(),details.benefitForm(),source.effectiveFrom().toString(),source.effectiveTo()==null?null:source.effectiveTo().toString(),source.eligibilityRuleSetId(),source.implementingDepartments(),source.sourcePacks(),source.exclusivityGroups(),source.configuration(),details),maker);}
  private Version write(Draft d,String maker){try(var c=connections.open()){c.setAutoCommit(false);try{try(var s=c.prepareStatement("insert into scheme_pack(scheme_id,owning_department_code) values(?,?) on conflict(scheme_id) do nothing")){s.setString(1,d.schemeId());s.setString(2,d.owner());s.executeUpdate();}int number;try(var s=c.prepareStatement("select coalesce(max(version),0)+1 from scheme_pack_version where scheme_id=?")){s.setString(1,d.schemeId());var r=s.executeQuery();r.next();number=r.getInt(1);}UUID id=UUID.randomUUID();try(var s=c.prepareStatement("insert into scheme_pack_version(scheme_pack_version_id,scheme_id,version,status,name,beneficiary_level,benefit_type,benefit_form,effective_from,effective_to,eligibility_rule_set_id,catalogue_payload,created_by) values(?,?,?,'DRAFT',?,?,?,?,?,?,?,cast(? as jsonb),?)")){s.setObject(1,id);s.setString(2,d.schemeId());s.setInt(3,number);s.setString(4,d.name());s.setString(5,d.beneficiaryLevel());s.setString(6,d.benefitType());s.setString(7,d.benefitForm());s.setDate(8,Date.valueOf(d.effectiveFrom()));if(d.effectiveTo()==null)s.setNull(9,java.sql.Types.DATE);else s.setDate(9,Date.valueOf(d.effectiveTo()));s.setObject(10,d.eligibilityRuleSetId());s.setString(11,d.configuration()==null?"{}":d.configuration());s.setString(12,maker);s.executeUpdate();}for(String x:d.implementingDepartments())link(c,"scheme_pack_implementing_department","department_code",id,x);for(String x:d.sourcePacks())link(c,"scheme_pack_source_pack","pack_id",id,x);for(String x:d.exclusivityGroups())link(c,"scheme_pack_exclusivity_group","group_code",id,x);saveDetails(c,id,d.details());c.commit();return history(d.schemeId()).stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();}catch(Exception e){c.rollback();throw e;}}catch(Exception e){throw new IllegalStateException("Cannot save Scheme Pack draft",e);}}
  private static void link(java.sql.Connection c,String table,String column,UUID id,String value)throws Exception{try(var s=c.prepareStatement("insert into "+table+"(scheme_pack_version_id,"+column+") values(?,?)")){s.setObject(1,id);s.setString(2,value);s.executeUpdate();}}
  public List<Version> list() { return query("select scheme_id from scheme_pack order by scheme_id", null); }
  public List<Version> history(String schemeId) { return query("select scheme_id from scheme_pack where scheme_id=?", schemeId); }
  public Optional<Version> effective(String schemeId, LocalDate on) { return history(schemeId).stream().filter(v -> "APPROVED".equals(v.status()) && !v.effectiveFrom().isAfter(on) && (v.effectiveTo()==null || !v.effectiveTo().isBefore(on))).findFirst(); }
  public void setEligibilityRuleSet(UUID versionId, UUID ruleSetId) { try(var c=connections.open();var check=c.prepareStatement("select rule_type from rule_set where rule_set_id=?")){check.setObject(1,ruleSetId);var r=check.executeQuery();if(!r.next()||!"ELIGIBILITY".equals(r.getString(1)))throw new IllegalArgumentException("Eligibility association requires an ELIGIBILITY rule set");try(var s=c.prepareStatement("update scheme_pack_version set eligibility_rule_set_id=? where scheme_pack_version_id=? and status='DRAFT'")){s.setObject(1,ruleSetId);s.setObject(2,versionId);if(s.executeUpdate()!=1)throw new IllegalArgumentException("Only a DRAFT Scheme Pack version may change its eligibility rule");}}catch(Exception e){if(e instanceof IllegalArgumentException)throw (IllegalArgumentException)e;throw new IllegalStateException("Cannot set eligibility rule association",e);} }
  private List<Version> query(String logicalSql, String schemeId) {
    try(var c=connections.open();var logical=c.prepareStatement(logicalSql)){if(schemeId!=null)logical.setString(1,schemeId);var rs=logical.executeQuery();var out=new java.util.ArrayList<Version>();while(rs.next()){String key=rs.getString(1);try(var s=c.prepareStatement("select v.scheme_pack_version_id,v.version,v.status,v.name,v.beneficiary_level,p.owning_department_code,v.effective_from,v.effective_to,v.eligibility_rule_set_id,v.catalogue_payload::text from scheme_pack p join scheme_pack_version v on v.scheme_id=p.scheme_id where p.scheme_id=? order by v.version desc")){s.setString(1,key);var r=s.executeQuery();while(r.next()){UUID id=r.getObject(1,UUID.class);out.add(new Version(key,id,r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getDate(7).toLocalDate(),r.getDate(8)==null?null:r.getDate(8).toLocalDate(),r.getObject(9,UUID.class),links(c,"scheme_pack_implementing_department","department_code",id),links(c,"scheme_pack_source_pack","pack_id",id),links(c,"scheme_pack_exclusivity_group","group_code",id),r.getString(10),details(c,id)));}}}return List.copyOf(out);}catch(Exception e){throw new IllegalStateException("Cannot read Scheme Packs",e);}
  }
  private static List<String> links(java.sql.Connection c,String table,String column,UUID id)throws Exception{try(var s=c.prepareStatement("select "+column+" from "+table+" where scheme_pack_version_id=? order by "+column)){s.setObject(1,id);var r=s.executeQuery();var out=new java.util.ArrayList<String>();while(r.next())out.add(r.getString(1));return List.copyOf(out);}}

  private static final java.util.Set<String> CLASSIFICATIONS = java.util.Set.of("CENTRAL", "STATE", "CENTRALLY_SPONSORED");

  /** Writes the A4 details of one version; null details leave the version's existing details unchanged. */
  private static void saveDetails(java.sql.Connection c, UUID id, Details details) throws Exception {
    if (details == null) {
      return;
    }
    if (details.classification() != null && !CLASSIFICATIONS.contains(details.classification())) {
      throw new IllegalArgumentException("Classification must be CENTRAL, STATE or CENTRALLY_SPONSORED");
    }
    String sql = "update scheme_pack_version set classification=?,go_reference=?,unit=?,frequency=?,amount=?,quantity=?"
        + (details.benefitType() == null ? "" : ",benefit_type=?")
        + (details.benefitForm() == null ? "" : ",benefit_form=?")
        + " where scheme_pack_version_id=?";
    try (var s = c.prepareStatement(sql)) {
      int i = 1;
      s.setString(i++, details.classification());
      s.setString(i++, details.goReference());
      s.setString(i++, details.unit());
      s.setString(i++, details.frequency());
      s.setBigDecimal(i++, details.amount());
      s.setBigDecimal(i++, details.quantity());
      if (details.benefitType() != null) {
        s.setString(i++, details.benefitType());
      }
      if (details.benefitForm() != null) {
        s.setString(i++, details.benefitForm());
      }
      s.setObject(i, id);
      s.executeUpdate();
    }
    try (var s = c.prepareStatement("delete from scheme_pack_code_crosswalk where scheme_pack_version_id=?")) {
      s.setObject(1, id);
      s.executeUpdate();
    }
    try (var s = c.prepareStatement("insert into scheme_pack_code_crosswalk(scheme_pack_version_id,pack_id,source_code)"
        + " values(?,?,?) on conflict do nothing")) {
      for (CodeCrosswalk crosswalk : details.codeCrosswalks()) {
        if (crosswalk.packId() == null || crosswalk.packId().isBlank()
            || crosswalk.sourceCode() == null || crosswalk.sourceCode().isBlank()) {
          throw new IllegalArgumentException("A code crosswalk needs a Source Pack and a source code");
        }
        s.setObject(1, id);
        s.setString(2, crosswalk.packId().strip());
        s.setString(3, crosswalk.sourceCode().strip());
        s.addBatch();
      }
      s.executeBatch();
    }
  }

  private static Details details(java.sql.Connection c, UUID id) throws Exception {
    List<CodeCrosswalk> crosswalks = new java.util.ArrayList<>();
    try (var s = c.prepareStatement("select pack_id,source_code from scheme_pack_code_crosswalk"
        + " where scheme_pack_version_id=? order by pack_id,source_code")) {
      s.setObject(1, id);
      var r = s.executeQuery();
      while (r.next()) {
        crosswalks.add(new CodeCrosswalk(r.getString(1), r.getString(2)));
      }
    }
    try (var s = c.prepareStatement("select classification,go_reference,benefit_type,benefit_form,unit,frequency,amount,"
        + "quantity from scheme_pack_version where scheme_pack_version_id=?")) {
      s.setObject(1, id);
      var r = s.executeQuery();
      r.next();
      return new Details(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
          r.getString(6), r.getBigDecimal(7), r.getBigDecimal(8), crosswalks);
    }
  }

  /**
   * Imports the packs' own {@code scheme} crosswalks (source code -> scheme ID) onto version 1 of each imported
   * scheme, once; returns how many code links were added. The packs stay the source of truth for ingestion.
   */
  public int importCodeCrosswalks(java.util.Collection<gov.uddp.core.statepack.LoadedStatePack> packs) {
    int added = 0;
    try (var c = connections.open(); var s = c.prepareStatement("insert into scheme_pack_code_crosswalk"
        + "(scheme_pack_version_id,pack_id,source_code) select scheme_pack_version_id,?,? from scheme_pack_version"
        + " where scheme_id=? and version=1 on conflict do nothing")) {
      for (var pack : packs) {
        var crosswalk = pack.crosswalks().get("scheme");
        if (crosswalk == null || crosswalk.values() == null) {
          continue;
        }
        for (var entry : crosswalk.values().entrySet()) {
          s.setString(1, pack.manifest().packId());
          s.setString(2, String.valueOf(entry.getKey()));
          s.setString(3, String.valueOf(entry.getValue()));
          added += s.executeUpdate();
        }
      }
      return added;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot import scheme code crosswalks", e);
    }
  }
}
