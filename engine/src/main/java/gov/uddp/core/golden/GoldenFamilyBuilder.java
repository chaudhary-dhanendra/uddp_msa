package gov.uddp.core.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Brings the Golden Families up to date at the end of a Golden Resident build, inside its transaction (M14,
 * docs/family-domain.md, ADR-0009). One stable Golden Family per source household; members are the current Golden
 * Residents of the residents the latest deliveries place in it. When the approved Identity Policy names a
 * {@code familyAnchorType} (Track E3), source households that share a value of that HOUSEHOLD identifier are one
 * family instead, keyed {@code anchor:<TYPE>:<protected value>}. Writes a version only when members or their roles
 * change. Canonical rows are only read; history rows are closed once (ADR-0006).
 */
@Component
public class GoldenFamilyBuilder {
    private final ObjectMapper json;
    private gov.uddp.core.identity.IdentityPolicyService identityPolicies;

    public GoldenFamilyBuilder(ObjectMapper json) {
        this.json = json;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIdentityPolicies(gov.uddp.core.identity.IdentityPolicyService identityPolicies) {
        this.identityPolicies = identityPolicies;
    }

    /** Counts of one Golden Family build. */
    public record Summary(int households, int created, int versioned, int unchanged, int retired, int reactivated,
                          int membersUnresolved) {
    }

    /** A household member as the current deliveries say. */
    private record Target(UUID goldenResidentId, String relationshipType, UUID canonicalMembershipId) {
    }

    /** A household as the current deliveries say. */
    private record Household(String key, String stateCode, String sourceSystem, String sourceFamilyId,
                             Map<String, Target> members) {
    }

    /** A current Golden Family membership row. */
    private record Current(UUID membershipId, UUID goldenResidentId, String relationshipType) {
    }

    /** A Golden Family identity with its latest version and current members. */
    private record Identity(UUID id, String status, int version, Map<String, Current> members) {
    }

    public Summary build(Connection c, UUID build) throws Exception {
        Map<String, Household> households = new TreeMap<>();
        int unresolved = 0;
        try (var s = c.prepareStatement("select m.family_record_key, m.state_code, m.source_system, m.source_family_id,"
                + " m.resident_record_key, m.relationship_type, m.canonical_family_membership_id, g.golden_resident_id"
                + " from canonical_family_membership_current m"
                + " left join golden_resident_membership g on g.source_record_key = m.resident_record_key"
                + " and g.effective_to is null order by m.family_record_key, m.resident_record_key");
                var r = s.executeQuery()) {
            while (r.next()) {
                UUID golden = r.getObject(8, UUID.class);
                if (golden == null) {
                    unresolved++;
                    continue;
                }
                String key = r.getString(1);
                var household = households.get(key);
                if (household == null) {
                    household = new Household(key, r.getString(2), r.getString(3), r.getString(4), new TreeMap<>());
                    households.put(key, household);
                }
                household.members().put(r.getString(5),
                        new Target(golden, r.getString(6), r.getObject(7, UUID.class)));
            }
        }
        String anchorType = identityPolicies == null ? null
                : identityPolicies.effective(c).map(gov.uddp.core.identity.IdentityPolicy::familyAnchorType).orElse(null);
        if (anchorType != null) {
            households = anchored(c, households, anchorType);
        }
        Map<String, Identity> identities = identities(c);

        int created = 0;
        int versioned = 0;
        int unchanged = 0;
        int reactivated = 0;
        for (Household household : households.values()) {
            Identity identity = identities.get(household.key());
            if (identity == null) {
                created++;
                create(c, build, household);
            } else if ("RETIRED".equals(identity.status())) {
                reactivated++;
                reactivate(c, build, household, identity);
            } else if (update(c, build, household, identity)) {
                versioned++;
            } else {
                unchanged++;
            }
        }
        int retired = 0;
        for (var entry : identities.entrySet()) {
            if ("ACTIVE".equals(entry.getValue().status()) && !households.containsKey(entry.getKey())) {
                retired++;
                retire(c, build, entry.getValue());
            }
        }
        var summary = new Summary(households.size(), created, versioned, unchanged, retired, reactivated, unresolved);
        try (var s = c.prepareStatement("update golden_build set summary = summary || jsonb_build_object('families',"
                + " cast(? as jsonb)) where golden_build_id = ?")) {
            s.setString(1, json.writeValueAsString(summary));
            s.setObject(2, build);
            s.executeUpdate();
        }
        return summary;
    }

    /**
     * Joins source households that share a value of the anchor identifier (union over shared values); a joined family's
     * key is {@code anchor:<TYPE>:<smallest value>} and its descriptive fields come from its smallest household key.
     * Households without the identifier stay as they are.
     */
    private Map<String, Household> anchored(Connection c, Map<String, Household> households, String type)
            throws Exception {
        Map<String, java.util.Set<String>> values = new TreeMap<>();
        try (var s = c.prepareStatement("select family_record_key, family_payload->'identifiers'->?->>'value'"
                + " from canonical_family_membership_current where family_payload->'identifiers'->?->>'value' is not null")) {
            s.setString(1, type);
            s.setString(2, type);
            var r = s.executeQuery();
            while (r.next()) {
                if (households.containsKey(r.getString(1))) {
                    values.computeIfAbsent(r.getString(1), x -> new java.util.TreeSet<>()).add(r.getString(2));
                }
            }
        }
        // Union-find over household keys and anchor values.
        Map<String, String> parent = new java.util.HashMap<>();
        java.util.function.UnaryOperator<String> find = new java.util.function.UnaryOperator<>() {
            @Override
            public String apply(String node) {
                String root = node;
                while (!parent.getOrDefault(root, root).equals(root)) {
                    root = parent.get(root);
                }
                return root;
            }
        };
        values.forEach((key, set) -> set.forEach(value -> {
            String a = find.apply("h:" + key);
            String b = find.apply("v:" + value);
            if (!a.equals(b)) {
                parent.put(a.compareTo(b) < 0 ? b : a, a.compareTo(b) < 0 ? a : b);
            }
        }));
        Map<String, List<String>> components = new TreeMap<>();
        values.keySet().forEach(key -> components.computeIfAbsent(find.apply("h:" + key), x -> new ArrayList<>()).add(key));
        Map<String, Household> out = new TreeMap<>(households);
        for (List<String> keys : components.values()) {
            String smallestValue = keys.stream().flatMap(key -> values.get(key).stream()).min(String::compareTo).orElseThrow();
            Household first = households.get(keys.stream().min(String::compareTo).orElseThrow());
            Map<String, Target> members = new TreeMap<>();
            keys.forEach(key -> {
                members.putAll(households.get(key).members());
                out.remove(key);
            });
            String anchorKey = "anchor:" + type + ":" + smallestValue;
            out.put(anchorKey, new Household(anchorKey, first.stateCode(), first.sourceSystem(), first.sourceFamilyId(),
                    members));
        }
        return out;
    }

    private void create(Connection c, UUID build, Household household) throws Exception {
        UUID id = UUID.randomUUID();
        try (var s = c.prepareStatement("insert into golden_family(golden_family_id, family_record_key, state_code,"
                + " source_system, source_family_id) values (?,?,?,?,?)")) {
            s.setObject(1, id);
            s.setString(2, household.key());
            s.setString(3, household.stateCode());
            s.setString(4, household.sourceSystem());
            s.setString(5, household.sourceFamilyId());
            s.executeUpdate();
        }
        insertVersion(c, id, 1, build, household, List.of("CREATED"));
        event(c, id, "CREATED", build, 1, null, null, Map.of());
        for (var member : household.members().entrySet()) {
            open(c, id, member.getKey(), member.getValue(), 1);
            event(c, id, "MEMBER_ADDED", build, 1, member.getKey(), member.getValue().goldenResidentId(),
                    Map.of("relationshipType", member.getValue().relationshipType()));
        }
    }

    private void reactivate(Connection c, UUID build, Household household, Identity identity) throws Exception {
        int version = identity.version() + 1;
        try (var s = c.prepareStatement("update golden_family set status = 'ACTIVE', retired_at = null"
                + " where golden_family_id = ? and status = 'RETIRED'")) {
            s.setObject(1, identity.id());
            s.executeUpdate();
        }
        insertVersion(c, identity.id(), version, build, household, List.of("REACTIVATED"));
        event(c, identity.id(), "REACTIVATED", build, version, null, null, Map.of());
        for (var member : household.members().entrySet()) {
            open(c, identity.id(), member.getKey(), member.getValue(), version);
            event(c, identity.id(), "MEMBER_ADDED", build, version, member.getKey(), member.getValue().goldenResidentId(),
                    Map.of("relationshipType", member.getValue().relationshipType()));
        }
    }

    /** Writes the next version when members or their Golden Residents or roles changed; false when unchanged. */
    private boolean update(Connection c, UUID build, Household household, Identity identity) throws Exception {
        int version = identity.version() + 1;
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (var entry : household.members().entrySet()) {
            Current current = identity.members().get(entry.getKey());
            if (current == null) {
                added.add(entry.getKey());
            } else if (!current.goldenResidentId().equals(entry.getValue().goldenResidentId())
                    || !current.relationshipType().equals(entry.getValue().relationshipType())) {
                changed.add(entry.getKey());
            }
        }
        for (String key : identity.members().keySet()) {
            if (!household.members().containsKey(key)) {
                removed.add(key);
            }
        }
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
            return false;
        }
        List<String> reasons = new ArrayList<>();
        if (!added.isEmpty() || !removed.isEmpty()) {
            reasons.add("MEMBERSHIP_CHANGED");
        }
        if (!changed.isEmpty()) {
            reasons.add("MEMBER_CHANGED");
        }
        closeCurrentVersion(c, identity.id());
        for (String key : removed) {
            Current current = identity.members().get(key);
            close(c, current.membershipId(), version);
            event(c, identity.id(), "MEMBER_REMOVED", build, version, key, current.goldenResidentId(), Map.of());
        }
        for (String key : changed) {
            Current current = identity.members().get(key);
            Target target = household.members().get(key);
            close(c, current.membershipId(), version);
            open(c, identity.id(), key, target, version);
            var detail = new LinkedHashMap<String, Object>();
            detail.put("previousGoldenResidentId", current.goldenResidentId());
            detail.put("previousRelationshipType", current.relationshipType());
            detail.put("relationshipType", target.relationshipType());
            event(c, identity.id(), "MEMBER_CHANGED", build, version, key, target.goldenResidentId(), detail);
        }
        for (String key : added) {
            Target target = household.members().get(key);
            open(c, identity.id(), key, target, version);
            event(c, identity.id(), "MEMBER_ADDED", build, version, key, target.goldenResidentId(),
                    Map.of("relationshipType", target.relationshipType()));
        }
        insertVersion(c, identity.id(), version, build, household, reasons);
        return true;
    }

    private void retire(Connection c, UUID build, Identity identity) throws Exception {
        int removedIn = identity.version() + 1;
        for (var member : identity.members().entrySet()) {
            close(c, member.getValue().membershipId(), removedIn);
            event(c, identity.id(), "MEMBER_REMOVED", build, identity.version(), member.getKey(),
                    member.getValue().goldenResidentId(), Map.of());
        }
        closeCurrentVersion(c, identity.id());
        try (var s = c.prepareStatement("update golden_family set status = 'RETIRED', retired_at = now()"
                + " where golden_family_id = ? and status = 'ACTIVE'")) {
            s.setObject(1, identity.id());
            s.executeUpdate();
        }
        event(c, identity.id(), "RETIRED", build, identity.version(), null, null, Map.of());
    }

    private Map<String, Identity> identities(Connection c) throws Exception {
        Map<String, Identity> out = new LinkedHashMap<>();
        Map<UUID, String> keys = new LinkedHashMap<>();
        try (var s = c.prepareStatement("select f.golden_family_id, f.family_record_key, f.status,"
                + " coalesce(max(v.version_number), 0) from golden_family f"
                + " left join golden_family_version v on v.golden_family_id = f.golden_family_id"
                + " group by f.golden_family_id, f.family_record_key, f.status order by f.family_record_key");
                var r = s.executeQuery()) {
            while (r.next()) {
                UUID id = r.getObject(1, UUID.class);
                out.put(r.getString(2), new Identity(id, r.getString(3), r.getInt(4), new TreeMap<>()));
                keys.put(id, r.getString(2));
            }
        }
        try (var s = c.prepareStatement("select golden_family_id, resident_record_key, golden_family_membership_id,"
                + " golden_resident_id, relationship_type from golden_family_membership where effective_to is null");
                var r = s.executeQuery()) {
            while (r.next()) {
                var identity = out.get(keys.get(r.getObject(1, UUID.class)));
                identity.members().put(r.getString(2), new Current(r.getObject(3, UUID.class),
                        r.getObject(4, UUID.class), r.getString(5)));
            }
        }
        return out;
    }

    private void insertVersion(Connection c, UUID id, int version, UUID build, Household household,
                               List<String> reasons) throws Exception {
        ObjectNode payload = json.createObjectNode();
        payload.put("familyRecordKey", household.key());
        payload.put("stateCode", household.stateCode());
        payload.put("sourceSystem", household.sourceSystem());
        payload.put("sourceFamilyId", household.sourceFamilyId());
        payload.put("memberCount", household.members().size());
        ArrayNode members = payload.putArray("members");
        household.members().forEach((key, member) -> members.addObject()
                .put("residentRecordKey", key)
                .put("goldenResidentId", member.goldenResidentId().toString())
                .put("relationshipType", member.relationshipType()));
        try (var s = c.prepareStatement("insert into golden_family_version(golden_family_version_id, golden_family_id,"
                + " version_number, golden_build_id, family_payload, change_reasons)"
                + " values (?,?,?,?,cast(? as jsonb),cast(? as jsonb))")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, id);
            s.setInt(3, version);
            s.setObject(4, build);
            s.setString(5, json.writeValueAsString(payload));
            s.setString(6, json.writeValueAsString(reasons));
            s.executeUpdate();
        }
    }

    private void closeCurrentVersion(Connection c, UUID id) throws SQLException {
        try (var s = c.prepareStatement("update golden_family_version set effective_to = clock_timestamp()"
                + " where golden_family_id = ? and effective_to is null")) {
            s.setObject(1, id);
            s.executeUpdate();
        }
    }

    private void open(Connection c, UUID id, String key, Target target, int version) throws SQLException {
        try (var s = c.prepareStatement("insert into golden_family_membership(golden_family_membership_id,"
                + " golden_family_id, resident_record_key, golden_resident_id, relationship_type,"
                + " canonical_family_membership_id, added_in_version) values (?,?,?,?,?,?,?)")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, id);
            s.setString(3, key);
            s.setObject(4, target.goldenResidentId());
            s.setString(5, target.relationshipType());
            s.setObject(6, target.canonicalMembershipId());
            s.setInt(7, version);
            s.executeUpdate();
        }
    }

    private void close(Connection c, UUID membership, int removedIn) throws SQLException {
        try (var s = c.prepareStatement("update golden_family_membership set effective_to = clock_timestamp(),"
                + " removed_in_version = ? where golden_family_membership_id = ? and effective_to is null")) {
            s.setInt(1, removedIn);
            s.setObject(2, membership);
            s.executeUpdate();
        }
    }

    private void event(Connection c, UUID id, String type, UUID build, int version, String key, UUID golden,
                       Map<String, Object> detail) throws Exception {
        try (var s = c.prepareStatement("insert into golden_family_event(golden_family_id, event_type, golden_build_id,"
                + " version_number, resident_record_key, golden_resident_id, detail)"
                + " values (?,?,?,?,?,?,cast(? as jsonb))")) {
            s.setObject(1, id);
            s.setString(2, type);
            s.setObject(3, build);
            s.setInt(4, version);
            s.setString(5, key);
            s.setObject(6, golden);
            s.setString(7, json.writeValueAsString(Objects.requireNonNullElse(detail, Map.of())));
            s.executeUpdate();
        }
    }
}
