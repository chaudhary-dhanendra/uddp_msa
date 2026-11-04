package gov.uddp.core.rules;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RuleSetStore {
    private final PostgresConnectionFactory connections;

    public RuleSetStore(PostgresConnectionFactory connections) { this.connections = connections; }

    PostgresConnectionFactory connections() { return connections; }

    public void save(RuleSet set) {
        save(set, null);
    }

    /** Inserts one version; {@code createdBy} is the maker of a draft (null for seeded configuration). */
    public void save(RuleSet set, String createdBy) {
        String sql = "insert into rule_set(rule_set_id, rule_type, owner_type, owner_id, version, status, effective_from, effective_to, dmn_xml, required_attributes, created_by)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, set.id()); s.setString(2, set.type().name()); s.setString(3, set.ownerType().name());
            s.setString(4, set.ownerId()); s.setInt(5, set.version()); s.setString(6, set.status().name());
            s.setDate(7, Date.valueOf(set.effectiveFrom()));
            if (set.effectiveTo() == null) s.setNull(8, java.sql.Types.DATE); else s.setDate(8, Date.valueOf(set.effectiveTo()));
            s.setString(9, set.dmnXml()); s.setString(10, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(set.requiredAttributes()));
            s.setString(11, createdBy); s.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException("Cannot save rule set", e); }
    }

    public Optional<RuleSet> effective(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId, LocalDate on) {
        String sql = "select rule_set_id, version, status, effective_from, effective_to, dmn_xml, required_attributes from rule_set"
                + " where rule_type = ? and owner_type = ? and owner_id = ? and status = 'APPROVED'"
                + " and effective_from <= ? and (effective_to is null or effective_to >= ?)"
                + " order by effective_from desc, version desc limit 1";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setString(1, type.name()); s.setString(2, ownerType.name()); s.setString(3, ownerId);
            s.setDate(4, Date.valueOf(on)); s.setDate(5, Date.valueOf(on));
            var r = s.executeQuery();
            if (!r.next()) return Optional.empty();
            Date to = r.getDate(5);
            return Optional.of(new RuleSet(r.getObject(1, java.util.UUID.class), type, ownerType, ownerId, r.getInt(2),
                    RuleSet.Status.valueOf(r.getString(3)), r.getDate(4).toLocalDate(), to == null ? null : to.toLocalDate(), r.getString(6), attributes(r.getString(7))));
        } catch (SQLException e) { throw new IllegalStateException("Cannot select rule set", e); }
    }
    public Optional<RuleSet> byId(java.util.UUID id) { try(var c=connections.open();var s=c.prepareStatement("select rule_type,owner_type,owner_id,version,status,effective_from,effective_to,dmn_xml,required_attributes from rule_set where rule_set_id=?")){s.setObject(1,id);var r=s.executeQuery();if(!r.next())return Optional.empty();Date to=r.getDate(7);return Optional.of(new RuleSet(id,RuleSet.Type.valueOf(r.getString(1)),RuleSet.OwnerType.valueOf(r.getString(2)),r.getString(3),r.getInt(4),RuleSet.Status.valueOf(r.getString(5)),r.getDate(6).toLocalDate(),to==null?null:to.toLocalDate(),r.getString(8),attributes(r.getString(9))));}catch(Exception e){throw new IllegalStateException("Cannot read rule set",e);} }
    public java.util.List<RuleSet> list(RuleSet.Type type) { String sql="select rule_set_id,rule_type,owner_type,owner_id,version,status,effective_from,effective_to,required_attributes from rule_set"+(type==null?"":" where rule_type=?")+" order by rule_type,owner_type,owner_id,version desc";try(var c=connections.open();var s=c.prepareStatement(sql)){if(type!=null)s.setString(1,type.name());var r=s.executeQuery();var out=new java.util.ArrayList<RuleSet>();while(r.next()){Date to=r.getDate(8);out.add(new RuleSet(r.getObject(1,java.util.UUID.class),RuleSet.Type.valueOf(r.getString(2)),RuleSet.OwnerType.valueOf(r.getString(3)),r.getString(4),r.getInt(5),RuleSet.Status.valueOf(r.getString(6)),r.getDate(7).toLocalDate(),to==null?null:to.toLocalDate(),"",attributes(r.getString(9))));}return java.util.List.copyOf(out);}catch(Exception e){throw new IllegalStateException("Cannot list rule sets",e);} }
    private static java.util.List<String> attributes(String text){try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text,new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){});}catch(Exception e){throw new IllegalStateException(e);}}

    /** The version number a new draft of this logical rule set gets. */
    public int nextVersion(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId) {
        String sql = "select coalesce(max(version), 0) + 1 from rule_set where rule_type = ? and owner_type = ? and owner_id = ?";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setString(1, type.name()); s.setString(2, ownerType.name()); s.setString(3, ownerId);
            var r = s.executeQuery();
            r.next();
            return r.getInt(1);
        } catch (SQLException e) { throw new IllegalStateException("Cannot number the rule set", e); }
    }

    /** All persisted versions of one logical rule set, newest first. */
    public List<RuleSet> history(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId) {
        String sql = "select rule_set_id, version, status, effective_from, effective_to, dmn_xml from rule_set"
                + " where rule_type = ? and owner_type = ? and owner_id = ? order by version desc";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setString(1, type.name()); s.setString(2, ownerType.name()); s.setString(3, ownerId);
            var r = s.executeQuery(); var out = new java.util.ArrayList<RuleSet>();
            while (r.next()) { Date to = r.getDate(5); out.add(new RuleSet(r.getObject(1, java.util.UUID.class), type, ownerType,
                    ownerId, r.getInt(2), RuleSet.Status.valueOf(r.getString(3)), r.getDate(4).toLocalDate(),
                    to == null ? null : to.toLocalDate(), r.getString(6))); }
            return List.copyOf(out);
        } catch (SQLException e) { throw new IllegalStateException("Cannot read rule set history", e); }
    }
}
