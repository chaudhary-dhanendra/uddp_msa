package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.contracts.Topics;
import gov.uddp.core.geography.LgdRow;
import gov.uddp.core.geography.LgdSnapshotData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A snapshot that changed the edge's geography master is published once, in ordered chunks of at most 2000 rows;
 * publishing it again adds nothing (the events' ids follow from the snapshot and chunk).
 */
@Testcontainers
class GeographyPublisherTest {

    @Test
    void aSnapshotIsPublishedInChunksOnce() {
        var connections = TestDatabase.freshMigrated();
        List<LgdRow> rows = new ArrayList<>();
        rows.add(new LgdRow(1, "STATE", "10", "Demo State", "ACTIVE", null, null, null));
        for (int i = 0; i < 4500; i++) {
            rows.add(new LgdRow(i + 2, "DISTRICT", String.valueOf(200000 + i), "District " + i, "ACTIVE", "STATE", "10",
                    null));
        }
        var publisher = new GeographyPublisher(connections);
        UUID snapshot = UUID.randomUUID();
        var data = new LgdSnapshotData("upload", LocalDate.of(2025, 1, 1), rows);
        assertEquals(3, publisher.publish(snapshot, data));
        publisher.publish(snapshot, data);
        assertEquals(3L, new Sql(connections).count("select count(*) from outbox_event where topic = ?",
                Topics.GEOGRAPHY_SNAPSHOT_PUBLISHED));
    }
}
