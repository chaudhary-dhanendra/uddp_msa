package gov.uddp.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.Migrations;
import gov.uddp.core.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A database at V9 that already holds canonical Residents and two per-run Golden builds migrates to V10 without
 * losing a single identity: the most recent build's Golden IDs stay current, older IDs retire into them, and
 * canonical, Raw and legacy Golden rows are untouched. Seeded with plain SQL, as a V9 application wrote them.
 */
class GoldenLifecycleMigrationTest {
    private final PostgresConnectionFactory connections = TestDatabase.freshEmpty();
    private final Map<String, UUID> ids = new HashMap<>();

    @Test
    void aV9DatabaseWithGoldenRowsMigratesWithoutLosingIdentities() throws Exception {
        var migrator = new DatabaseMigrator(connections);
        assertEquals("9", migrator.migrateTo("9").targetSchemaVersion);
        seedV9();
        String canonicalBefore = snapshot("select canonical_resident_id::text||canonical_payload::text||created_at::text"
                + " from canonical_resident order by 1");
        String legacyGoldenBefore = snapshot("select golden_resident_id::text||matching_run_id::text"
                + "||survivorship_policy_version||member_set_key||golden_payload::text from golden_resident order by 1");
        String legacyMembersBefore = snapshot("select golden_resident_id::text||canonical_resident_id::text"
                + " from golden_resident_member order by 1");

        var result = migrator.migrate();

        // V10 (Golden lifecycle), then V11 (Family), V12 (stewardship), V13 (benefits) and V14 (database State Packs),
        // which only add tables.
        assertEquals(Migrations.after(9), result.migrationsExecuted);
        assertEquals(String.valueOf(Migrations.LATEST), result.targetSchemaVersion);
        migrator.validate();
        // Nothing that existed is rewritten.
        assertEquals(canonicalBefore, snapshot("select canonical_resident_id::text||canonical_payload::text"
                + "||created_at::text from canonical_resident order by 1"));
        assertEquals(legacyGoldenBefore, snapshot("select golden_resident_id::text||matching_run_id::text"
                + "||survivorship_policy_version||member_set_key||golden_payload::text from golden_resident order by 1"));
        assertEquals(legacyMembersBefore, snapshot("select golden_resident_id::text||canonical_resident_id::text"
                + " from golden_resident_member order by 1"));

        // Canonical versions: Ram and Sita share ration number R1001 in one batch, so they are different records;
        // Ram's pension record arrived twice (two batches), so the second is version 2 and the first is superseded.
        assertEquals("ST1|pds|pds|R1001|raw:" + ids.get("raw-ram1"), key("ram1"));
        assertEquals("ST1|pds|pds|R1001|raw:" + ids.get("raw-sita"), key("sita"));
        assertEquals("ST1|pds|pds|R1002", key("ramesh"));
        assertEquals("ST2|pension|pension|F5001", key("ram2v1"));
        assertEquals(key("ram2v1"), key("ram2v2"));
        assertEquals(2, count("select version_number from canonical_resident_version where canonical_resident_id=?",
                ids.get("ram2v2")));
        assertEquals(1, count("select count(*) from canonical_resident_version where supersedes_canonical_id=?",
                ids.get("ram2v1")));
        assertEquals(4, count("select count(*) from canonical_resident_current", null));
        assertEquals(0, count("select count(*) from canonical_resident_current where canonical_resident_id=?",
                ids.get("ram2v1")));

        // The latest build's Golden IDs are the current identities; every legacy ID has version 1 and a CREATED event.
        assertEquals(List.of("H-ram1", "H-ram2v2", "H-ramesh", "H-sita"), labels(
                "select golden_resident_id from golden_resident where status='ACTIVE'"));
        assertEquals(List.of("G-ram", "G-ramesh", "G-sita", "H-ram2v1"), labels(
                "select golden_resident_id from golden_resident where status='RETIRED'"));
        assertEquals(8, count("select count(*) from golden_resident_version where version_number=1", null));
        assertEquals(4, count("select count(*) from golden_resident_version where effective_to is null", null));
        assertEquals(0, count("select count(*) from golden_resident g join golden_resident_version v"
                + " on v.golden_resident_id=g.golden_resident_id and v.effective_to is null where g.status='RETIRED'", null));
        assertEquals(8, count("select count(*) from golden_resident_event where event_type='CREATED'", null));
        assertEquals(4, count("select count(*) from golden_resident_event where event_type='RETIRED'", null));
        // Version 1 carries the legacy payload, policy and provenance.
        assertEquals(1, count("select count(*) from golden_resident_version v join golden_resident g"
                + " on g.golden_resident_id=v.golden_resident_id where v.golden_payload=g.golden_payload"
                + " and v.survivorship_policy_version=g.survivorship_policy_version and g.golden_resident_id=?",
                ids.get("H-ram1")));
        assertEquals(count("select count(*) from golden_resident_field_provenance", null),
                count("select count(*) from golden_resident_version_provenance", null));

        // Retired IDs point at the current identity holding most of their source records (ties: the oldest).
        assertEquals(ids.get("H-ram1"), mergedInto("G-ram"));
        assertEquals(ids.get("H-sita"), mergedInto("G-sita"));
        assertEquals(ids.get("H-ramesh"), mergedInto("G-ramesh"));
        assertEquals(ids.get("H-ram2v2"), mergedInto("H-ram2v1"));
        for (String active : List.of("H-ram1", "H-ram2v2", "H-ramesh", "H-sita")) {
            assertNull(mergedInto(active));
        }

        // Current memberships: one per latest source record, never on a retired identity or a superseded canonical.
        assertEquals(4, count("select count(*) from golden_resident_membership where effective_to is null", null));
        assertEquals(0, count("select count(*) from golden_resident_membership m join golden_resident g"
                + " on g.golden_resident_id=m.golden_resident_id where m.effective_to is null and g.status='RETIRED'", null));
        assertEquals(0, count("select count(*) from golden_resident_membership where effective_to is null"
                + " and canonical_resident_id=?", ids.get("ram2v1")));
        assertEquals(ids.get("ram2v2"), uuid("select canonical_resident_id from golden_resident_membership"
                + " where effective_to is null and golden_resident_id=?", ids.get("H-ram2v2")));
        assertEquals(count("select count(*) from golden_resident_member", null),
                count("select count(*) from golden_resident_membership", null));
    }

    private void seedV9() throws SQLException {
        try (var c = connections.open()) {
            UUID st1 = batch(c, "ST1", "pds");
            UUID st2a = batch(c, "ST2", "pension");
            UUID st2b = batch(c, "ST2", "pension");
            canonical(c, "ram1", st1, "ST1", "pds", "R1001", "Ram Singh", "2026-01-01T00:00:01Z");
            canonical(c, "sita", st1, "ST1", "pds", "R1001", "Sita Singh", "2026-01-01T00:00:02Z");
            canonical(c, "ramesh", st1, "ST1", "pds", "R1002", "Ramesh Kumar", "2026-01-01T00:00:03Z");
            canonical(c, "ram2v1", st2a, "ST2", "pension", "F5001", "Ram Singh", "2026-01-01T00:00:04Z");
            canonical(c, "ram2v2", st2b, "ST2", "pension", "F5001", "Ram Singh", "2026-02-01T00:00:00Z");
            UUID oldRun = run(c, "2026-01-02T00:00:00Z");
            UUID newRun = run(c, "2026-02-02T00:00:00Z");
            // Older build (v2): Ram x2, Sita, Ramesh.
            golden(c, "G-ram", oldRun, "resident-survivorship-v2", "2026-01-03T00:00:00Z", "ram1", "ram2v1");
            golden(c, "G-sita", oldRun, "resident-survivorship-v2", "2026-01-03T00:00:00Z", "sita");
            golden(c, "G-ramesh", oldRun, "resident-survivorship-v2", "2026-01-03T00:00:00Z", "ramesh");
            // Latest build (v3): the superseded pension record (ram2v1) and its new version are separate rows.
            golden(c, "H-ram1", newRun, "resident-survivorship-v3", "2026-02-03T00:00:01Z", "ram1");
            golden(c, "H-ram2v1", newRun, "resident-survivorship-v3", "2026-02-03T00:00:02Z", "ram2v1");
            golden(c, "H-ram2v2", newRun, "resident-survivorship-v3", "2026-02-03T00:00:03Z", "ram2v2");
            golden(c, "H-sita", newRun, "resident-survivorship-v3", "2026-02-03T00:00:04Z", "sita");
            golden(c, "H-ramesh", newRun, "resident-survivorship-v3", "2026-02-03T00:00:05Z", "ramesh");
        }
    }

    private UUID batch(java.sql.Connection c, String state, String source) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                + "state_pack_version,pipeline_template,status) values(?,?,?,'file','1.0.0','generic-file-ingest.hpl','COMPLETED')")) {
            s.setObject(1, id);
            s.setString(2, state);
            s.setString(3, source);
            s.executeUpdate();
        }
        return id;
    }

    private void canonical(java.sql.Connection c, String label, UUID batch, String state, String source, String recordId,
                           String name, String createdAt) throws SQLException {
        UUID raw = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        try (var s = c.prepareStatement("insert into raw_record(raw_record_id,batch_id,state_code,source_system,raw_payload,"
                + "source_metadata,state_pack_version,pipeline_template) values(?,?,?,?,'{}','{}','1.0.0','generic-file-ingest.hpl')")) {
            s.setObject(1, raw);
            s.setObject(2, batch);
            s.setString(3, state);
            s.setString(4, source);
            s.executeUpdate();
        }
        String payload = "{\"name\":\"" + name + "\",\"metadata\":{\"sourceRecordId\":\"" + recordId + "\"}}";
        try (var s = c.prepareStatement("insert into canonical_resident(canonical_resident_id,raw_record_id,batch_id,"
                + "state_code,source_system,source_entity,state_pack_version,canonical_payload,created_at)"
                + " values(?,?,?,?,?,?,'1.0.0',cast(? as jsonb),cast(? as timestamptz))")) {
            s.setObject(1, id);
            s.setObject(2, raw);
            s.setObject(3, batch);
            s.setString(4, state);
            s.setString(5, source);
            s.setString(6, source);
            s.setString(7, payload);
            s.setString(8, createdAt);
            s.executeUpdate();
        }
        ids.put(label, id);
        ids.put("raw-" + label, raw);
    }

    private UUID run(java.sql.Connection c, String completedAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var s = c.prepareStatement("insert into matching_run(run_id,model_version,threshold,status,completed_at)"
                + " values(?,'resident-match-v1',0.95,'COMPLETED',cast(? as timestamptz))")) {
            s.setObject(1, id);
            s.setString(2, completedAt);
            s.executeUpdate();
        }
        return id;
    }

    private void golden(java.sql.Connection c, String label, UUID run, String policy, String createdAt, String... members)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (var s = c.prepareStatement("insert into golden_resident(golden_resident_id,matching_run_id,"
                + "survivorship_policy_version,member_set_key,golden_payload,created_at)"
                + " values(?,?,?,?,cast(? as jsonb),cast(? as timestamptz))")) {
            s.setObject(1, id);
            s.setObject(2, run);
            s.setString(3, policy);
            s.setString(4, label);
            s.setString(5, "{\"name\":\"" + label + "\"}");
            s.setString(6, createdAt);
            s.executeUpdate();
        }
        for (String member : members) {
            try (var s = c.prepareStatement("insert into golden_resident_member(golden_resident_id,canonical_resident_id)"
                    + " values(?,?)")) {
                s.setObject(1, id);
                s.setObject(2, ids.get(member));
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("insert into golden_resident_field_provenance(golden_resident_id,field_path,"
                    + "canonical_resident_id,selected_value,rule_applied,survivorship_policy_version,"
                    + "contributing_canonical_ids) values(?,?,?,'\"x\"','ALL_VALUES_AGREE',?,'[]')")) {
                s.setObject(1, id);
                s.setString(2, "name-" + member);
                s.setObject(3, ids.get(member));
                s.setString(4, policy);
                s.executeUpdate();
            }
        }
        ids.put(label, id);
    }

    private String key(String label) {
        try (var c = connections.open();
                var s = c.prepareStatement("select source_record_key from canonical_resident_version where canonical_resident_id=?")) {
            s.setObject(1, ids.get(label));
            var r = s.executeQuery();
            assertTrue(r.next(), label + " has a canonical version row");
            return r.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID mergedInto(String label) {
        return uuid("select merged_into_id from golden_resident where golden_resident_id=?", ids.get(label));
    }

    private UUID uuid(String sql, UUID parameter) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, parameter);
            var r = s.executeQuery();
            assertTrue(r.next(), sql);
            return r.getObject(1, UUID.class);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String sql, UUID parameter) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            if (parameter != null) {
                s.setObject(1, parameter);
            }
            var r = s.executeQuery();
            r.next();
            return r.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Labels (seed names) of the Golden IDs the query returns, sorted. */
    private List<String> labels(String sql) {
        var byId = new HashMap<UUID, String>();
        ids.forEach((label, id) -> byId.put(id, label));
        var out = new ArrayList<String>();
        try (var c = connections.open(); var s = c.prepareStatement(sql); var r = s.executeQuery()) {
            while (r.next()) {
                out.add(byId.get(r.getObject(1, UUID.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out.stream().sorted().toList();
    }

    private String snapshot(String sql) {
        var out = new StringBuilder();
        try (var c = connections.open(); var s = c.prepareStatement(sql); var r = s.executeQuery()) {
            while (r.next()) {
                out.append(r.getString(1)).append('\n');
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }
}
