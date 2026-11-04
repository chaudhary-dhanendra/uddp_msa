package gov.uddp.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** V101: canonical_resident_match_v exposes the v1.1 match columns and leaves canonical_resident_current alone. */
class ResidentMatchViewMigrationTest {
    private final PostgresConnectionFactory connections = TestDatabase.freshEmpty();

    @Test
    void viewReadsV11AndV10Payloads() throws Exception {
        new DatabaseMigrator(connections).migrate();
        UUID batch = UUID.randomUUID();
        try (var c = connections.open(); var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,"
                + "source_system,source_type,state_pack_version,pipeline_template,status)"
                + " values(?,'ST1','pds','file','1.0.0','generic-file-ingest.hpl','COMPLETED')")) {
            s.setObject(1, batch);
            s.executeUpdate();
        }
        UUID v11 = canonical(batch, "{\"name\":\"Ram Singh\",\"mobile\":\"9876543210\",\"nameNormalised\":\"ram singh\","
                + "\"birthYear\":1980,\"dateOfBirthPrecision\":\"YEAR\",\"relations\":["
                + "{\"type\":\"MOTHER\",\"nameNormalised\":\"gita\"},{\"type\":\"FATHER\",\"nameNormalised\":\"mohan\"}],"
                + "\"metadata\":{\"sourceRecordId\":\"A\"},\"extensions\":{\"uddp.geography\":{\"village\":"
                + "{\"lgd\":\"V77\"}},\"uddp.identifiers\":{\"AADHAAR\":{\"last4\":\"1234\"}}}}");
        UUID v10 = canonical(batch, "{\"name\":\"Anita\",\"dateOfBirth\":\"1978-02-17\","
                + "\"metadata\":{\"sourceRecordId\":\"B\"}}");

        assertEquals("ram singh|mohan|1980|YEAR|V77|1234|XXXXXX3210", row(v11));
        assertEquals("null|null|1978|DAY|null|null|null", row(v10));
        try (var c = connections.open(); var s = c.prepareStatement(
                "select count(*) from canonical_resident_current"); var r = s.executeQuery()) {
            r.next();
            assertEquals(2, r.getInt(1));
        }
    }

    private UUID canonical(UUID batch, String payload) throws SQLException {
        UUID raw = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        try (var c = connections.open()) {
            try (var s = c.prepareStatement("insert into raw_record(raw_record_id,batch_id,state_code,source_system,"
                    + "raw_payload,source_metadata,state_pack_version,pipeline_template)"
                    + " values(?,?,'ST1','pds','{}','{}','1.0.0','generic-file-ingest.hpl')")) {
                s.setObject(1, raw);
                s.setObject(2, batch);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("insert into canonical_resident(canonical_resident_id,raw_record_id,batch_id,"
                    + "state_code,source_system,source_entity,state_pack_version,canonical_payload)"
                    + " values(?,?,?,'ST1','pds','pds','1.0.0',cast(? as jsonb))")) {
                s.setObject(1, id);
                s.setObject(2, raw);
                s.setObject(3, batch);
                s.setString(4, payload);
                s.executeUpdate();
            }
        }
        return id;
    }

    private String row(UUID id) throws SQLException {
        try (var c = connections.open(); var s = c.prepareStatement("select name_normalised||'|', father_name_normalised,"
                + " birth_year, dob_precision, village_lgd, aadhaar_last4, mobile_masked from canonical_resident_match_v"
                + " where canonical_resident_id=?")) {
            s.setObject(1, id);
            try (var r = s.executeQuery()) {
                r.next();
                String name = r.getString(1);
                return (name == null ? "null" : name.substring(0, name.length() - 1)) + "|" + r.getString(2) + "|"
                        + r.getString(3) + "|" + r.getString(4) + "|" + r.getString(5) + "|" + r.getString(6) + "|"
                        + r.getString(7);
            }
        }
    }
}
