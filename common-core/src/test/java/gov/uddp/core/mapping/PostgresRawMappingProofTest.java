package gov.uddp.core.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;

/** Maps the first Hop-shaped Raw record of each sample State Pack batch from a disposable PostgreSQL 16. */
class PostgresRawMappingProofTest {
    @Test
    void mapsFirstRawRecordFromEachStateBatch() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var batches = SampleData.landAll(connections, SampleData.STATE_PACKS);
        var packs = SampleData.loader(SampleData.STATE_PACKS).loadAll(SampleData.STATE_PACKS);
        var engine = new ResidentMappingEngine(new RawCsvRecordParser(new ObjectMapper()));
        var reader = new RawRecordJdbcReader(new ObjectMapper());

        assertEquals(3, batches.size());
        try (var connection = connections.open()) {
            for (var entry : batches.entrySet()) {
                var raw = reader.firstInBatch(connection, entry.getValue().getFirst()).orElseThrow();
                var resident = engine.map(raw, packs.get(entry.getKey()), raw.sourceSystem());
                assertNotNull(resident.name());
                assertNotNull(resident.metadata().lineageId());
                if (entry.getKey().equals("state-3")) {
                    assertTrue(resident.extensions().containsKey("blood_group"));
                    assertTrue(resident.extensions().containsKey("disability_pct"));
                }
            }
        }
    }
}
