package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.CanonicalProcessingSummary;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * MSA Phase 3, canonical-service extraction, monolith side: a database that maps nothing itself receives another
 * engine's canonical events (Residents, families, batch reports; every event twice) for the same Raw rows. The MAPPED
 * stage's remote processor returns the same counts as local processing, and the unchanged Golden build over the
 * projected rows gives the same 5 Golden Residents and 6 Golden Families.
 */
@Testcontainers
class RemoteCanonicalTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void projectedCanonicalRecordsGiveTheLocalResults() throws Exception {
        var source = TestDatabase.freshMigrated();
        var sourceSql = new Sql(source);
        var processing = SampleData.processing(source, SampleData.loader(SampleData.STATE_PACKS),
                new PlatformEvents(source, true));
        List<UUID> batches = new ArrayList<>();
        SampleData.landAll(source, SampleData.STATE_PACKS).values().forEach(batches::addAll);
        Map<UUID, CanonicalProcessingSummary> local = new java.util.HashMap<>();
        batches.forEach(batch -> local.put(batch, processing.processBatch(batch)));

        var target = TestDatabase.freshMigrated();
        var targetSql = new Sql(target);
        copyRaw(source, target);
        var messaging = new RemoteCanonicalMessaging(target, KAFKA.getBootstrapServers());
        messaging.start();
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            for (int delivery = 0; delivery < 2; delivery++) {
                for (var row : sourceSql.column("select topic || chr(1) || partition_key || chr(1) || payload::text"
                        + " from outbox_event where topic in (?, ?, ?) order by created_at", String.class,
                        Topics.RESIDENT_CREATED, Topics.FAMILY_OBSERVED, Topics.BATCH_PROCESSED)) {
                    String[] parts = row.split(String.valueOf((char) 1), 3);
                    producer.send(new ProducerRecord<>(parts[0], parts[1], parts[2])).get();
                }
            }
            var remote = new RemoteCanonicalProcessor(target, Duration.ofMinutes(2));
            for (UUID batch : batches) {
                try {
                    assertEquals(local.get(batch), remote.processBatch(batch));
                } catch (IllegalStateException e) {
                    throw new AssertionError(e.getMessage() + " residents=" + targetSql.count(
                            "select count(*) from canonical_resident") + " reports=" + targetSql.column(
                            "select batch_id::text from remote_canonical_batch", String.class) + " processed="
                            + targetSql.column("select consumer || ':' || count(*) from processed_event group by 1",
                            String.class), e);
                }
            }
            long deadline = System.currentTimeMillis() + 60_000;
            while (targetSql.count("select count(*) from canonical_family_observation") < 7
                    || targetSql.count("select count(*) from processed_event") < expected(sourceSql)) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("projection incomplete");
                }
                Thread.sleep(200);
            }
        } finally {
            messaging.destroy();
        }
        assertEquals(7, targetSql.count("select count(*) from canonical_resident_current"));
        var golden = SampleData.golden(target, SampleData.loader(SampleData.STATE_PACKS));
        golden.build(MatchingRunFixture.completed(target, List.of(Set.copyOf(targetSql.canonicalsNamed("Ram Singh")))));
        assertEquals(5, targetSql.count("select count(*) from golden_resident where status='ACTIVE'"));
        assertEquals(6, targetSql.count("select count(*) from golden_family where status='ACTIVE'"));
    }

    private static long expected(Sql sql) {
        return sql.count("select count(*) from outbox_event where topic in (?, ?, ?)", Topics.RESIDENT_CREATED,
                Topics.FAMILY_OBSERVED, Topics.BATCH_PROCESSED);
    }

    /** ingestion-service's Raw, as RemoteRawProjection would have projected it: the same batches and raw ids. */
    private static void copyRaw(PostgresConnectionFactory from, PostgresConnectionFactory to) throws Exception {
        var fromSql = new Sql(from);
        var toSql = new Sql(to);
        for (String row : fromSql.column("select row_to_json(b)::text from ingestion_batch b", String.class)) {
            toSql.execute("insert into ingestion_batch select * from json_populate_record(null::ingestion_batch,"
                    + " cast(? as json))", row);
        }
        for (String row : fromSql.column("select row_to_json(r)::text from raw_record r", String.class)) {
            toSql.execute("insert into raw_record select * from json_populate_record(null::raw_record,"
                    + " cast(? as json))", row);
        }
    }
}
