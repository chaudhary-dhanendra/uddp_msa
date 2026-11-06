package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.messaging.Inbox;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** In a large delivery resident-created can overtake its raw record; the edge waits instead of parking the event. */
class ResidentCreatedWaitsForRawTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void residentCreatedWaitsUntilItsRawRecordIsProjected() throws Exception {
        PostgresConnectionFactory connections = TestDatabase.freshMigrated();
        UUID batch = UUID.randomUUID();
        UUID raw = UUID.randomUUID();
        UUID canonical = UUID.randomUUID();
        var envelope = json.readTree("""
                {"stateCode": "FX", "payload": {"canonicalResidentId": "%s", "rawRecordId": "%s", "batchId": "%s",
                 "sourceSystem": "src", "sourceEntity": "src", "statePackVersion": "1.0.0",
                 "canonicalPayload": {"name": "Asha"}, "sourceRecordKey": "FX|src|src|P1", "version": 1}}
                """.formatted(canonical, raw, batch));
        execute(connections, "insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                + "state_pack_version,pipeline_template,status) values ('" + batch + "','FX','src','file','1.0.0',"
                + "'generic-file-ingest.hpl','COMPLETED')");

        try (var c = connections.open()) {
            assertThrows(Inbox.NotYetApplicable.class, () -> RemoteCanonicalProjection.residentCreatedAfterRaw(c, envelope));
        }

        execute(connections, "insert into raw_record(raw_record_id,batch_id,state_code,source_system,raw_payload,"
                + "source_metadata,state_pack_version,pipeline_template) values ('" + raw + "','" + batch
                + "','FX','src','{}','{}','1.0.0','generic-file-ingest.hpl')");
        try (var c = connections.open()) {
            RemoteCanonicalProjection.residentCreatedAfterRaw(c, envelope);
            try (var s = c.prepareStatement("select count(*) from canonical_resident where canonical_resident_id = ?")) {
                s.setObject(1, canonical);
                var r = s.executeQuery();
                r.next();
                assertEquals(1, r.getInt(1));
            }
        }
    }

    private static void execute(PostgresConnectionFactory connections, String sql) throws SQLException {
        try (var c = connections.open(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
