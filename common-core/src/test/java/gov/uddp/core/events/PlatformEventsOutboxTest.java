package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.contracts.EventSchemas;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.messaging.Outbox;
import gov.uddp.messaging.OutboxRelay;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * MSA Phase 2: the existing pipeline (land, canonical processing, matching run, Golden build) writes the catalogue
 * events to the outbox when events are enabled, writes nothing when they are off, never duplicates an event on a
 * rerun, and the relay publishes schema-valid envelopes to Kafka.
 */
@Testcontainers
class PlatformEventsOutboxTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void disabledEventsWriteNothingAndLeaveThePipelineUnchanged() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var events = new PlatformEvents(connections, false);
        var golden = golden(connections, events);

        golden.build(processedWithRun(connections, events));

        assertEquals(0, sql.count("select count(*) from outbox_event"));
        assertEquals(5, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
    }

    @Test
    void thePipelineWritesEveryCatalogueEventOnceAndTheRelayPublishesThem() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var events = new PlatformEvents(connections, true);
        var golden = golden(connections, events);
        UUID run = processedWithRun(connections, events);
        golden.build(run);

        long raw = sql.count("select count(*) from raw_record");
        long batches = sql.count("select count(distinct batch_id) from raw_record");
        assertEquals(raw, outbox(sql, Topics.RAW_RECORD_RECEIVED));
        assertEquals(batches, outbox(sql, Topics.BATCH_COMPLETED));
        assertEquals(batches, outbox(sql, Topics.BATCH_PROCESSED));
        assertEquals(sql.count("select count(*) from canonical_resident_version"), outbox(sql, Topics.RESIDENT_CREATED));
        assertEquals(sql.count("select count(*) from canonical_processing_error"), outbox(sql, Topics.CANONICAL_DLQ));
        assertEquals(sql.count("select count(*) from canonical_family_observation"), outbox(sql, Topics.FAMILY_OBSERVED));
        assertEquals(sql.count("select count(*) from resident_match_pair"), outbox(sql, Topics.MATCH_DECIDED));
        assertEquals(sql.count("select count(*) from resident_match_cluster_member"), outbox(sql, Topics.CLUSTER_ASSIGNED));
        assertEquals(1, outbox(sql, Topics.MATCH_RUN_COMPLETED));
        assertEquals(5, outbox(sql, Topics.GOLDEN_RESIDENT_CHANGED), "one change event per new Golden Resident");
        assertEquals(1, outbox(sql, Topics.GOLDEN_BUILD_COMPLETED));
        long total = sql.count("select count(*) from outbox_event");

        // Reruns: the matching run's and raw records' events are deterministic, an unchanged rebuild touches no
        // identity, so only one more build-completed event appears.
        golden.build(run);
        events.rawBatchLanded(sql.column("select distinct batch_id from raw_record", UUID.class).getFirst());
        assertEquals(total + 1, sql.count("select count(*) from outbox_event"));
        assertEquals(5, outbox(sql, Topics.GOLDEN_RESIDENT_CHANGED));

        var received = relayAndConsume(connections, total + 1);
        assertEquals(total + 1, received.size());
        Set<String> ids = new HashSet<>();
        for (var record : received) {
            var envelope = Outbox.json().readTree(record.value());
            assertTrue(EventSchemas.validate(EventSchemas.ENVELOPE, envelope).isEmpty(), record.value());
            assertEquals(envelope.path("partitionKey").asText(), record.key());
            ids.add(envelope.path("eventId").asText());
        }
        assertEquals(total + 1, ids.size(), "every published event has its own id");
        assertEquals(0, sql.count("select count(*) from outbox_event where published_at is null"));
    }

    private static UUID processedWithRun(PostgresConnectionFactory connections, PlatformEvents events) {
        var processing = SampleData.processing(connections, SampleData.loader(SampleData.STATE_PACKS), events);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        var sql = new Sql(connections);
        return MatchingRunFixture.completed(connections, List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh"))));
    }

    private static gov.uddp.core.golden.GoldenResidentService golden(PostgresConnectionFactory connections,
                                                                     PlatformEvents events) {
        var golden = SampleData.golden(connections, SampleData.loader(SampleData.STATE_PACKS));
        golden.setPlatformEvents(events);
        return golden;
    }

    private static long outbox(Sql sql, String topic) {
        return sql.count("select count(*) from outbox_event where topic = ?", topic);
    }

    private static List<ConsumerRecord<String, String>> relayAndConsume(PostgresConnectionFactory connections,
                                                                        long expected) throws Exception {
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "enable.idempotence", "true", "acks", "all"), new StringSerializer(), new StringSerializer())) {
            new OutboxRelay(connections::open, producer, 50).drain();
        }
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "platform-events-" + UUID.randomUUID(), "auto.offset.reset", "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(java.util.regex.Pattern.compile("uddp\\..*"));
            long deadline = System.currentTimeMillis() + 60_000;
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }
}
