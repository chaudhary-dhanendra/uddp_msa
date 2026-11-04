package gov.uddp.core.scheme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.rules.RuleSetService;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only A5 eligibility analysis; it never records an outcome or changes benefits. */
@Service public class SchemeEligibilityService {
  public enum Outcome { ELIGIBLE, NOT_ELIGIBLE, INDETERMINATE, UNCONFIGURED }
  /** {@code ageYears}: the completed years the rule saw (from {@code age.years} or the date of birth); null for a household or when unknown. */
  public record Result(UUID entityId, Outcome outcome, List<String> requiredAttributes, List<String> availableAttributes, List<String> missingAttributes, List<String> clauseReferences, String explanation, List<String> exclusivityConflicts, Integer ageYears) {
  }
  public record Preview(int evaluated,int eligible,int notEligible,int indeterminate,Map<String,Integer> missingAttributeCounts,List<Result> sample) { }
  private final PostgresConnectionFactory db; private final ObjectMapper json; private final RuleSetService rules;
  public SchemeEligibilityService(PostgresConnectionFactory db,ObjectMapper json,RuleSetService rules){this.db=db;this.json=json;this.rules=rules;}
  public Result evaluate(SchemePackService.Version scheme, UUID entityId) {
    return evaluate(scheme, entityId, java.time.LocalDate.now());
  }
  /** As {@link #evaluate(SchemePackService.Version, UUID)}, with ages worked out on {@code on}. */
  public Result evaluate(SchemePackService.Version scheme, UUID entityId, java.time.LocalDate on) {
    if(scheme.eligibilityRuleSetId()==null)return new Result(entityId,Outcome.INDETERMINATE,List.of(),List.of(),List.of(),List.of(),"No eligibility rule is configured",List.of(),null);
    var rule=rules.getById(scheme.eligibilityRuleSetId()); if(rule.type()!=gov.uddp.core.rules.RuleSet.Type.ELIGIBILITY)throw new IllegalStateException("Scheme references non-eligibility rule");
    JsonNode payload=withAge(payload(scheme,entityId),on); Integer age="HOUSEHOLD".equals(scheme.beneficiaryLevel())?null:ageYears(payload); var available=new ArrayList<String>();var missing=new ArrayList<String>();var input=new LinkedHashMap<String,Object>();
    for(String path:rule.requiredAttributes()){JsonNode v=at(payload,path);if(v==null||v.isNull()||v.asText().isBlank())missing.add(path);else{available.add(path);put(input,path,json.convertValue(v,Object.class));}}
    if(!missing.isEmpty())return new Result(entityId,Outcome.INDETERMINATE,rule.requiredAttributes(),available,missing,List.of(),"Required attributes are missing",conflicts(scheme,entityId),age);
    var evaluation=rules.evaluateById(rule.id(),input); var clauses=evaluation.firedRules().stream().map(RuleSetService.FiredRule::clauseReference).toList();
    boolean eligible=evaluation.firedRules().stream().anyMatch(x -> Boolean.TRUE.equals(x.outputs().get("eligible")) || "ELIGIBLE".equals(String.valueOf(x.outputs().get("outcome"))));
    return new Result(entityId,eligible?Outcome.ELIGIBLE:Outcome.NOT_ELIGIBLE,rule.requiredAttributes(),available,List.of(),clauses,"DMN evaluated",conflicts(scheme,entityId),age);
  }
  public Preview preview(SchemePackService.Version scheme,int requestedLimit){int limit=Math.max(1,Math.min(requestedLimit<=0?25:requestedLimit,100));var ids=population(scheme);int eligible=0,notEligible=0,indeterminate=0;var missing=new TreeMap<String,Integer>();var sample=new ArrayList<Result>();for(UUID id:ids){var result=evaluate(scheme,id);if(result.outcome()==Outcome.ELIGIBLE)eligible++;else if(result.outcome()==Outcome.NOT_ELIGIBLE)notEligible++;else indeterminate++;result.missingAttributes().forEach(x->missing.merge(x,1,Integer::sum));if(sample.size()<limit)sample.add(result);}return new Preview(ids.size(),eligible,notEligible,indeterminate,Map.copyOf(missing),List.copyOf(sample));}
  private List<UUID> population(SchemePackService.Version s){boolean h="HOUSEHOLD".equals(s.beneficiaryLevel());String sql="select "+(h?"golden_family_id":"golden_resident_id")+" from "+(h?"golden_family":"golden_resident")+" where status='ACTIVE' order by 1";try(var c=db.open();var st=c.prepareStatement(sql);var r=st.executeQuery()){var out=new ArrayList<UUID>();while(r.next())out.add(r.getObject(1,UUID.class));return out;}catch(Exception e){throw new IllegalStateException("Cannot list Golden population",e);}}
  private List<String> conflicts(SchemePackService.Version s,UUID id){if(s.exclusivityGroups().isEmpty())return List.of();try(var c=db.open();var st=c.prepareStatement("select distinct b.scheme_id from benefit_holding_current h join benefit b on b.benefit_id=h.benefit_id join scheme_pack_version v on v.scheme_id=b.scheme_id join scheme_pack_exclusivity_group g on g.scheme_pack_version_id=v.scheme_pack_version_id where h.golden_resident_id=? and g.group_code=any(?) and b.scheme_id<>?")){st.setObject(1,id);st.setArray(2,c.createArrayOf("text",s.exclusivityGroups().toArray()));st.setString(3,s.schemeId());var r=st.executeQuery();var out=new ArrayList<String>();while(r.next())out.add(r.getString(1));return List.copyOf(out);}catch(Exception e){return List.of();}}
  private JsonNode payload(SchemePackService.Version s,UUID id){boolean household="HOUSEHOLD".equals(s.beneficiaryLevel());try(var c=db.open();var st=c.prepareStatement("select "+(household?"family_payload":"golden_payload")+"::text from "+(household?"golden_family_version":"golden_resident_version")+" where "+(household?"golden_family_id":"golden_resident_id")+"=? and effective_to is null")){st.setObject(1,id);var r=st.executeQuery();if(!r.next())throw new IllegalArgumentException("Golden entity does not exist: "+id);return json.readTree(r.getString(1));}catch(Exception e){throw new IllegalStateException("Cannot read Golden entity",e);}}
  /**
   * A Golden Resident carries a date of birth, while an age is only mapped by sources that report one: without
   * {@code age.years}, a full date of birth gives the age in completed years on {@code on}.
   */
  static JsonNode withAge(JsonNode payload, java.time.LocalDate on) {
    if (at(payload, "age.years") != null && !at(payload, "age.years").isNull()) {
      return payload;
    }
    java.time.LocalDate born;
    try {
      born = java.time.LocalDate.parse(payload.path("dateOfBirth").asText(""));
    } catch (java.time.format.DateTimeParseException notAFullDate) {
      return payload;
    }
    var copy = (com.fasterxml.jackson.databind.node.ObjectNode) payload.deepCopy();
    var age = copy.path("age").isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) copy.get("age") : copy.putObject("age");
    age.put("years", java.time.Period.between(born, on).getYears());
    return copy;
  }
  /**
   * A rule reads {@code age.years} as a path into the record, so the value goes in nested ({@code age -> years}) and
   * keeps its JSON type: the text "65" does not compare with 18.
   */
  @SuppressWarnings("unchecked")
  static void put(Map<String, Object> input, String path, Object value) {
    String[] parts = path.split("\\.");
    Map<String, Object> node = input;
    for (int i = 0; i < parts.length - 1; i++) {
      node = (Map<String, Object>) node.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
    }
    node.put(parts[parts.length - 1], value);
  }
  /** The whole years at {@code age.years}, or null when the record has none. */
  static Integer ageYears(JsonNode payload) {
    JsonNode years = at(payload, "age.years");
    if (years == null || years.isNull()) {
      return null;
    }
    if (years.isNumber()) {
      return years.intValue();
    }
    try {
      return (int) Math.floor(Double.parseDouble(years.asText().strip()));
    } catch (NumberFormatException notANumber) {
      return null;
    }
  }
  private static JsonNode at(JsonNode n,String path){for(String p:path.split("\\.")){n=n.path(p);if(n.isMissingNode())return null;}return n;}
}
