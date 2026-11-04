package gov.uddp.core.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.golden.GoldenTargetGroups.ClusterRow;
import gov.uddp.core.golden.GoldenTargetGroups.Participant;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC access to the M11 Golden lifecycle tables inside the caller's transaction. Only inserts, plus the one-time
 * closes ADR-0006 allows (version and membership {@code effective_to}, identity retirement).
 */
final class GoldenLifecycleStore {
    /** A current membership row. */
    record Membership(UUID membershipId, UUID canonicalId, OffsetDateTime since) {
    }

    /** A stored provenance row of the current version. */
    record StoredField(UUID canonicalId, JsonNode value, String rule, List<String> contributors) {
    }

    /** An active identity with its current version, current members by source record key and provenance. */
    record CurrentIdentity(UUID id, OffsetDateTime createdAt, int version, String policyVersion, JsonNode payload,
                           Map<String, Membership> members, Map<String, StoredField> provenance) {
        CarryForward.Identity carryForward() {
            Map<String, OffsetDateTime> since = new LinkedHashMap<>();
            members.forEach((key, member) -> since.put(key, member.since()));
            return new CarryForward.Identity(id, createdAt, since);
        }
    }

    /** One lifecycle event row. */
    record Event(UUID goldenId, String type, UUID build, Integer version, UUID related, UUID canonicalId, String key,
                 String field, JsonNode oldValue, JsonNode newValue, Map<String, Object> detail) {
        static Event of(UUID goldenId, String type, UUID build, int version) {
            return new Event(goldenId, type, build, version, null, null, null, null, null, null, Map.of());
        }

        Event related(UUID other) {
            return new Event(goldenId, type, build, version, other, canonicalId, key, field, oldValue, newValue, detail);
        }

        Event member(UUID canonical, String sourceRecordKey) {
            return new Event(goldenId, type, build, version, related, canonical, sourceRecordKey, field, oldValue, newValue,
                    detail);
        }

        Event value(String path, JsonNode before, JsonNode after) {
            return new Event(goldenId, type, build, version, related, canonicalId, key, path, before, after, detail);
        }

        Event detail(Map<String, Object> values) {
            return new Event(goldenId, type, build, version, related, canonicalId, key, field, oldValue, newValue, values);
        }
    }

    private final ObjectMapper json;

    GoldenLifecycleStore(ObjectMapper json) {
        this.json = json;
    }

    /** Serialises Golden builds: carry-forward reads and rewrites the one current Golden set. */
    void lock(Connection c) throws SQLException {
        try (var s = c.prepareStatement("select pg_advisory_xact_lock(hashtext('uddp.golden-resident-build'))")) {
            s.execute();
        }
    }

    /** The run's mode; the run must exist and be COMPLETED. */
    String completedRunMode(Connection c, UUID run) throws SQLException {
        try (var s = c.prepareStatement("select status, mode from matching_run where run_id=?")) {
            s.setObject(1, run);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new IllegalArgumentException("Matching run does not exist");
            }
            if (!"COMPLETED".equals(r.getString(1))) {
                throw new IllegalArgumentException("Matching run is not COMPLETED");
            }
            return r.getString(2);
        }
    }

    /** Latest canonical versions, in canonical-ID order. */
    List<Participant> participants(Connection c) throws Exception {
        List<Participant> out = new ArrayList<>();
        try (var s = c.prepareStatement("select canonical_resident_id, state_code, source_system, canonical_payload::text,"
                + " source_record_key from canonical_resident_current order by canonical_resident_id");
                var r = s.executeQuery()) {
            while (r.next()) {
                var observation = new CanonicalObservation(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                        json.readTree(r.getString(4)));
                out.add(new Participant(r.getString(5), observation));
            }
        }
        return out;
    }

    /** The run's cluster rows by source record key (a superseded canonical maps to its record's key). */
    List<ClusterRow> clusters(Connection c, UUID run) throws SQLException {
        List<ClusterRow> out = new ArrayList<>();
        try (var s = c.prepareStatement("select m.cluster_id,"
                + " coalesce(v.source_record_key, 'canonical:' || m.canonical_record_id) from resident_match_cluster_member m"
                + " left join canonical_resident_version v on v.canonical_resident_id = m.canonical_record_id"
                + " where m.run_id=? order by m.cluster_id, m.canonical_record_id")) {
            s.setObject(1, run);
            var r = s.executeQuery();
            while (r.next()) {
                out.add(new ClusterRow(r.getString(1), r.getString(2)));
            }
        }
        return out;
    }

    /** Active identities (oldest first) with their current version, members and provenance. */
    Map<UUID, CurrentIdentity> currentIdentities(Connection c) throws Exception {
        Map<UUID, CurrentIdentity> out = new LinkedHashMap<>();
        Map<UUID, UUID> versions = new LinkedHashMap<>();
        try (var s = c.prepareStatement("select g.golden_resident_id, g.created_at, v.golden_resident_version_id,"
                + " v.version_number, v.survivorship_policy_version, v.golden_payload::text from golden_resident g"
                + " left join golden_resident_version v on v.golden_resident_id = g.golden_resident_id and v.effective_to is null"
                + " where g.status = 'ACTIVE' order by g.created_at, g.golden_resident_id");
                var r = s.executeQuery()) {
            while (r.next()) {
                UUID id = r.getObject(1, UUID.class);
                String payload = r.getString(6);
                out.put(id, new CurrentIdentity(id, r.getObject(2, OffsetDateTime.class), r.getInt(4), r.getString(5),
                        payload == null ? json.createObjectNode() : json.readTree(payload),
                        new LinkedHashMap<>(), new LinkedHashMap<>()));
                versions.put(r.getObject(3, UUID.class), id);
            }
        }
        try (var s = c.prepareStatement("select golden_resident_membership_id, golden_resident_id, canonical_resident_id,"
                + " source_record_key, effective_from from golden_resident_membership where effective_to is null"
                + " order by effective_from, source_record_key");
                var r = s.executeQuery()) {
            while (r.next()) {
                var identity = out.get(r.getObject(2, UUID.class));
                if (identity != null) {
                    identity.members().put(r.getString(4), new Membership(r.getObject(1, UUID.class),
                            r.getObject(3, UUID.class), r.getObject(5, OffsetDateTime.class)));
                }
            }
        }
        try (var s = c.prepareStatement("select p.golden_resident_version_id, p.field_path, p.canonical_resident_id,"
                + " p.selected_value::text, p.rule_applied, p.contributing_canonical_ids::text"
                + " from golden_resident_version_provenance p join golden_resident_version v"
                + " on v.golden_resident_version_id = p.golden_resident_version_id where v.effective_to is null");
                var r = s.executeQuery()) {
            while (r.next()) {
                var identity = out.get(versions.get(r.getObject(1, UUID.class)));
                if (identity == null) {
                    continue;
                }
                List<String> contributors = new ArrayList<>();
                json.readTree(r.getString(6)).forEach(node -> contributors.add(node.asText()));
                identity.provenance().put(r.getString(2), new StoredField(r.getObject(3, UUID.class),
                        json.readTree(r.getString(4)), r.getString(5), contributors));
            }
        }
        return out;
    }

    void startBuild(Connection c, UUID build, UUID run, String policy, String mode) throws SQLException {
        try (var s = c.prepareStatement("insert into golden_build(golden_build_id, matching_run_id,"
                + " survivorship_policy_version, mode) values(?,?,?,?)")) {
            s.setObject(1, build);
            s.setObject(2, run);
            s.setString(3, policy);
            s.setString(4, mode);
            s.executeUpdate();
        }
    }

    void completeBuild(Connection c, UUID build, GoldenBuildSummary summary) throws Exception {
        try (var s = c.prepareStatement("update golden_build set completed_at = clock_timestamp(),"
                + " summary = cast(? as jsonb) where golden_build_id = ?")) {
            s.setString(1, json.writeValueAsString(summary));
            s.setObject(2, build);
            s.executeUpdate();
        }
    }

    /** Creates the identity with its founding (version 1) snapshot in the V6 tables (ADR-0002). */
    void createIdentity(Connection c, UUID id, UUID run, String policy, String memberSetKey, JsonNode payload,
                        UUID splitFrom, List<CanonicalObservation> members, Map<String, GoldenField> fields) throws Exception {
        try (var s = c.prepareStatement("insert into golden_resident(golden_resident_id, matching_run_id,"
                + " survivorship_policy_version, member_set_key, golden_payload, split_from_id)"
                + " values(?,?,?,?,cast(? as jsonb),?)")) {
            s.setObject(1, id);
            s.setObject(2, run);
            s.setString(3, policy);
            s.setString(4, memberSetKey);
            s.setString(5, json.writeValueAsString(payload));
            s.setObject(6, splitFrom);
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("insert into golden_resident_member(golden_resident_id, canonical_resident_id)"
                + " values(?,?)")) {
            for (var member : members) {
                s.setObject(1, id);
                s.setObject(2, member.id());
                s.addBatch();
            }
            s.executeBatch();
        }
        try (var s = c.prepareStatement("insert into golden_resident_field_provenance(golden_resident_id, field_path,"
                + " canonical_resident_id, selected_value, rule_applied, survivorship_policy_version,"
                + " contributing_canonical_ids) values(?,?,?,cast(? as jsonb),?,?,cast(? as jsonb))")) {
            for (var entry : fields.entrySet()) {
                s.setObject(1, id);
                s.setString(2, entry.getKey());
                s.setObject(3, entry.getValue().selectedCanonicalId());
                s.setString(4, json.writeValueAsString(entry.getValue().value()));
                s.setString(5, entry.getValue().rule());
                s.setString(6, policy);
                s.setString(7, json.writeValueAsString(entry.getValue().contributors()));
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    void closeCurrentVersion(Connection c, UUID goldenId) throws SQLException {
        try (var s = c.prepareStatement("update golden_resident_version set effective_to = now()"
                + " where golden_resident_id = ? and effective_to is null")) {
            s.setObject(1, goldenId);
            s.executeUpdate();
        }
    }

    void insertVersion(Connection c, UUID goldenId, int number, UUID build, UUID run, String policy, String memberSetKey,
                       JsonNode payload, List<String> reasons, Map<String, GoldenField> fields) throws Exception {
        UUID versionId = UUID.randomUUID();
        try (var s = c.prepareStatement("insert into golden_resident_version(golden_resident_version_id,"
                + " golden_resident_id, version_number, golden_build_id, matching_run_id, survivorship_policy_version,"
                + " member_set_key, golden_payload, change_reasons) values(?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))")) {
            s.setObject(1, versionId);
            s.setObject(2, goldenId);
            s.setInt(3, number);
            s.setObject(4, build);
            s.setObject(5, run);
            s.setString(6, policy);
            s.setString(7, memberSetKey);
            s.setString(8, json.writeValueAsString(payload));
            s.setString(9, json.writeValueAsString(reasons));
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("insert into golden_resident_version_provenance(golden_resident_version_id,"
                + " field_path, canonical_resident_id, selected_value, rule_applied, survivorship_policy_version,"
                + " contributing_canonical_ids) values(?,?,?,cast(? as jsonb),?,?,cast(? as jsonb))")) {
            for (var entry : fields.entrySet()) {
                s.setObject(1, versionId);
                s.setString(2, entry.getKey());
                s.setObject(3, entry.getValue().selectedCanonicalId());
                s.setString(4, json.writeValueAsString(entry.getValue().value()));
                s.setString(5, entry.getValue().rule());
                s.setString(6, policy);
                s.setString(7, json.writeValueAsString(entry.getValue().contributors()));
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    /** Ends a membership; {@code removedInVersion} is the first version it is no longer part of. */
    void closeMembership(Connection c, UUID membershipId, int removedInVersion) throws SQLException {
        try (var s = c.prepareStatement("update golden_resident_membership set effective_to = now(), removed_in_version = ?"
                + " where golden_resident_membership_id = ? and effective_to is null")) {
            s.setInt(1, removedInVersion);
            s.setObject(2, membershipId);
            s.executeUpdate();
        }
    }

    void openMembership(Connection c, UUID goldenId, UUID canonicalId, String key, int addedInVersion) throws SQLException {
        try (var s = c.prepareStatement("insert into golden_resident_membership(golden_resident_id, canonical_resident_id,"
                + " source_record_key, added_in_version) values(?,?,?,?)")) {
            s.setObject(1, goldenId);
            s.setObject(2, canonicalId);
            s.setString(3, key);
            s.setInt(4, addedInVersion);
            s.executeUpdate();
        }
    }

    void retire(Connection c, UUID goldenId, UUID mergedInto) throws SQLException {
        try (var s = c.prepareStatement("update golden_resident set status = 'RETIRED', retired_at = now(),"
                + " merged_into_id = ? where golden_resident_id = ? and status = 'ACTIVE'")) {
            s.setObject(1, mergedInto);
            s.setObject(2, goldenId);
            if (s.executeUpdate() != 1) {
                throw new IllegalStateException("Golden Resident is not active: " + goldenId);
            }
        }
    }

    void events(Connection c, List<Event> events) throws Exception {
        try (var s = c.prepareStatement("insert into golden_resident_event(golden_resident_id, event_type, golden_build_id,"
                + " version_number, related_golden_resident_id, canonical_resident_id, source_record_key, field_path,"
                + " old_value, new_value, detail) values(?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb))")) {
            for (Event e : events) {
                s.setObject(1, e.goldenId());
                s.setString(2, e.type());
                s.setObject(3, e.build());
                s.setObject(4, e.version());
                s.setObject(5, e.related());
                s.setObject(6, e.canonicalId());
                s.setString(7, e.key());
                s.setString(8, e.field());
                s.setString(9, e.oldValue() == null ? null : json.writeValueAsString(e.oldValue()));
                s.setString(10, e.newValue() == null ? null : json.writeValueAsString(e.newValue()));
                s.setString(11, json.writeValueAsString(e.detail()));
                s.addBatch();
            }
            s.executeBatch();
        }
    }
}
