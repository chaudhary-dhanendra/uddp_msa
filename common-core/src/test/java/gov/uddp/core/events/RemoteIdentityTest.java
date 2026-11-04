package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.messaging.Inbox;
import gov.uddp.messaging.Outbox;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * MSA Phase 3, identity-service extraction, monolith side: a matching run made by identity-service arrives as events
 * in any order and more than once, is projected into the matching tables, becomes COMPLETED only when complete, and
 * the unchanged Golden build turns it into the same five Golden Residents as a local run. The onboarding stage's
 * remote runner sends the command and returns the projected run.
 */
@Testcontainers
class RemoteIdentityTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void outOfOrderAndRedeliveredRunEventsCompleteTheRunOnlyWhenWhole() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        processSample(connections);
        UUID run = UUID.randomUUID();
        List<JsonNode> events = runEvents(sql, run, null);
        JsonNode completed = events.getLast();
        List<JsonNode> pairs = events.stream().filter(e -> e.path("eventType").asText().endsWith("match-decided")).toList();
        List<JsonNode> clusters = events.stream().filter(e -> e.path("eventType").asText().endsWith("cluster-assigned"))
                .toList();

        var inbox = new Inbox(connections::open, "test");
        apply(inbox, completed, RemoteMatchingProjection::matchRunCompleted);
        clusters.forEach(e -> apply(inbox, e, RemoteMatchingProjection::clusterAssigned));
        pairs.subList(0, pairs.size() - 1).forEach(e -> apply(inbox, e, RemoteMatchingProjection::matchDecided));
        assertEquals("RECEIVING", status(sql, run));

        apply(inbox, pairs.getLast(), RemoteMatchingProjection::matchDecided);
        assertEquals("COMPLETED", status(sql, run));
        events.forEach(e -> apply(inbox, e, handlerFor(e)));
        assertEquals(21, sql.count("select count(*) from resident_match_pair where run_id = ?", run));
        assertEquals(7, sql.count("select count(*) from resident_match_cluster_member where run_id = ?", run));

        var platformEvents = new PlatformEvents(connections, true);
        platformEvents.setRemoteIdentity(true);
        var golden = SampleData.golden(connections, SampleData.loader(SampleData.STATE_PACKS));
        golden.setPlatformEvents(platformEvents);
        golden.build(run);
        assertEquals(5, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        assertEquals(0, sql.count("select count(*) from outbox_event where topic = ?", Topics.MATCH_DECIDED),
                "a remote run is identity-service's to publish");
        assertEquals(5, sql.count("select count(*) from outbox_event where topic = ?", Topics.GOLDEN_RESIDENT_CHANGED));
    }

    @Test
    void theRemoteRunnerRequestsARunAndReturnsItOnceProjectedFromKafka() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        processSample(connections);
        sql.execute("insert into matching_profile (profile_id, name, version, status, config, created_by)"
                + " values (gen_random_uuid(), 'resident', 1, 'RETIRED', '{}'::jsonb, 'maker'),"
                + " (gen_random_uuid(), 'resident', 2, 'APPROVED', '{\"thresholds\":{\"autoMatch\":0.9}}'::jsonb,"
                + " 'maker')");
        var runner = new RemoteMatchingRunner(connections, Duration.ofMinutes(2));
        CompletableFuture<UUID> result = CompletableFuture.supplyAsync(runner::run);

        long deadline = System.currentTimeMillis() + 30_000;
        while (sql.count("select count(*) from outbox_event where topic = ?", Topics.CMD_MATCHING_REQUESTED) == 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("No matching-requested command");
            }
            Thread.sleep(100);
        }
        UUID correlation = sql.column("select (payload->>'correlationId')::uuid from outbox_event where topic = ?",
                UUID.class, Topics.CMD_MATCHING_REQUESTED).getFirst();
        assertEquals("FULL", sql.column("select payload->'payload'->>'mode' from outbox_event where topic = ?",
                String.class, Topics.CMD_MATCHING_REQUESTED).getFirst());
        assertEquals(List.of("RETIRED", "APPROVED"), sql.column("select p->>'status' from outbox_event,"
                + " jsonb_array_elements(payload->'payload'->'settings'->'matching_profile') p where topic = ?",
                String.class, Topics.CMD_MATCHING_REQUESTED), "the steward-managed settings travel with the request,"
                + " the approved profile last");

        UUID run = UUID.randomUUID();
        List<JsonNode> events = new ArrayList<>(runEvents(sql, run, correlation));
        Collections.reverse(events);
        var messaging = new RemoteIdentityMessaging(connections, KAFKA.getBootstrapServers());
        messaging.start();
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            for (int delivery = 0; delivery < 2; delivery++) {
                for (JsonNode event : events) {
                    producer.send(new ProducerRecord<>(topicOf(event), event.path("partitionKey").asText(),
                            event.toString())).get();
                }
            }
            assertEquals(run, result.get(90, TimeUnit.SECONDS));
        } finally {
            messaging.destroy();
        }
        assertEquals("IDENTITY_SERVICE", sql.column("select origin from matching_run where run_id = ?", String.class,
                run).getFirst());
        assertEquals(21, sql.count("select count(*) from resident_match_pair where run_id = ?", run));
    }

    private static void processSample(PostgresConnectionFactory connections) {
        var processing = SampleData.processing(connections, SampleData.loader(SampleData.STATE_PACKS));
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
    }

    /** The events identity-service publishes for a run whose only AUTO_MATCH group is the three "Ram Singh" records. */
    private static List<JsonNode> runEvents(Sql sql, UUID run, UUID correlation) {
        List<UUID> all = sql.column("select canonical_resident_id from canonical_resident_current"
                + " order by canonical_resident_id", UUID.class);
        Set<UUID> ram = new HashSet<>(sql.canonicalsNamed("Ram Singh"));
        String ramCluster = ram.stream().map(UUID::toString).sorted().findFirst().orElseThrow();
        List<JsonNode> events = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                boolean same = ram.contains(all.get(i)) && ram.contains(all.get(j));
                ObjectNode p = Outbox.json().createObjectNode();
                p.put("matchingRunId", run.toString());
                p.put("canonicalRecordA", all.get(i).toString());
                p.put("canonicalRecordB", all.get(j).toString());
                p.put("matchProbability", same ? 0.99 : 0.01);
                p.put("matchWeight", same ? 10 : -10);
                p.put("decision", same ? "AUTO_MATCH" : "NO_MATCH");
                p.putObject("evidence").put("fixture", true);
                events.add(envelope("uddp.identity.match-decided", all.get(i).toString(), correlation, p));
            }
        }
        for (UUID id : all) {
            ObjectNode p = Outbox.json().createObjectNode();
            p.put("matchingRunId", run.toString());
            p.put("canonicalRecordId", id.toString());
            p.put("clusterId", ram.contains(id) ? ramCluster : id.toString());
            events.add(envelope("uddp.identity.cluster-assigned", id.toString(), correlation, p));
        }
        ObjectNode p = Outbox.json().createObjectNode();
        p.put("matchingRunId", run.toString());
        p.put("mode", "FULL");
        p.put("modelVersion", "resident-match-v1");
        p.put("autoMatchThreshold", 0.95);
        p.put("possibleMatchThreshold", 0.75);
        p.put("recordsConsidered", all.size());
        p.put("pairCount", all.size() * (all.size() - 1) / 2);
        p.put("clusterMemberCount", all.size());
        events.add(envelope("uddp.identity.match-run-completed", run.toString(), correlation, p));
        return events;
    }

    private static JsonNode envelope(String type, String key, UUID correlation, ObjectNode payload) {
        ObjectNode e = Outbox.json().createObjectNode();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("eventType", type);
        e.put("schemaVersion", 1);
        e.put("occurredAt", "2026-11-04T00:00:00Z");
        e.put("producer", "identity-service");
        e.put("partitionKey", key);
        e.put("correlationId", correlation == null ? null : correlation.toString());
        e.set("payload", payload);
        return e;
    }

    private static String topicOf(JsonNode event) {
        return switch (event.path("eventType").asText()) {
            case "uddp.identity.match-decided" -> Topics.MATCH_DECIDED;
            case "uddp.identity.cluster-assigned" -> Topics.CLUSTER_ASSIGNED;
            default -> Topics.MATCH_RUN_COMPLETED;
        };
    }

    private static Inbox.Handler handlerFor(JsonNode event) {
        return switch (event.path("eventType").asText()) {
            case "uddp.identity.match-decided" -> RemoteMatchingProjection::matchDecided;
            case "uddp.identity.cluster-assigned" -> RemoteMatchingProjection::clusterAssigned;
            default -> RemoteMatchingProjection::matchRunCompleted;
        };
    }

    private static void apply(Inbox inbox, JsonNode event, Inbox.Handler handler) {
        try {
            inbox.handle(new org.apache.kafka.clients.consumer.ConsumerRecord<>("t", 0, 0L,
                    event.path("partitionKey").asText(), event.toString()), handler);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String status(Sql sql, UUID run) {
        return sql.column("select status from matching_run where run_id = ?", String.class, run).getFirst();
    }
}
