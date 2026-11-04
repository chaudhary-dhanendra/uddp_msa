package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.ProcessHopRunner;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.messaging.Outbox;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * MSA Phase 3, ingestion-service extraction, monolith side: the INGESTED stage sends the source's generic template and
 * allow-listed parameters as a command, ingestion-service's Raw events (in any order, twice) are projected with the
 * same raw ids, the stage succeeds once the whole batch is here, and canonical processing maps it unchanged. A failed
 * batch fails the stage with ingestion-service's message.
 */
@Testcontainers
class RemoteIngestionTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void aRemotelyLandedBatchIsProjectedAndProcessedAsALocalOne() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        LoadedStatePack pack = firstPack();
        SourceDefinition source = pack.sources().getFirst();
        UUID batch = startBatch(connections, pack, source);
        var runner = new RemoteHopRunner(connections, localRunner(connections), Duration.ofMinutes(2));
        CompletableFuture<HopRunner.Result> result = CompletableFuture.supplyAsync(() ->
                runner.ingest(source, pack.manifest().stateCode(), pack.manifest().version(), batch));

        JsonNode command = awaitCommand(sql);
        JsonNode parameters = command.path("payload").path("parameters");
        assertEquals("generic-file-ingest.hpl", command.path("payload").path("pipelineTemplate").asText());
        assertEquals(batch.toString(), parameters.path("BATCH_ID").asText());
        assertTrue(Files.exists(Path.of(parameters.path("SOURCE_FILE").asText())));

        List<JsonNode> events = new ArrayList<>(landedEvents(command, pack, source, "COMPLETED"));
        events.addFirst(events.removeLast());
        var messaging = new RemoteIngestionMessaging(connections, KAFKA.getBootstrapServers());
        messaging.start();
        try {
            deliverTwice(events);
            HopRunner.Result landed = result.get(90, TimeUnit.SECONDS);
            assertTrue(landed.succeeded(), landed.output());
        } finally {
            messaging.destroy();
        }
        int rows = events.size() - 1;
        assertEquals(rows, sql.count("select count(*) from raw_record where batch_id = ?", batch));
        for (JsonNode event : events.subList(1, events.size())) {
            assertEquals(1, sql.count("select count(*) from raw_record where raw_record_id = ?::uuid",
                    event.path("payload").path("rawRecordId").asText()));
        }

        var summary = SampleData.processing(connections, SampleData.loader(SampleData.STATE_PACKS)).processBatch(batch);
        assertEquals(rows, summary.recordsRead());
        assertEquals(0, summary.recordsFailed());
        assertTrue(summary.recordsMapped() > 0);
    }

    @Test
    void withIngestionServicesUrlTheFileIsUploadedAndNamedByFileRef() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        LoadedStatePack pack = firstPack();
        SourceDefinition source = pack.sources().getFirst();
        UUID batch = startBatch(connections, pack, source);
        var uploaded = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/ingestion/files", exchange -> {
            uploaded.set(exchange.getRequestBody().readAllBytes());
            byte[] body = ("{\"fileRef\":\"sha256:" + "a".repeat(64) + "/landed.csv\"}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            var runner = new RemoteHopRunner(connections, localRunner(connections), Duration.ofSeconds(2));
            runner.setIngestionUrl("http://127.0.0.1:" + stub.getAddress().getPort());
            CompletableFuture.runAsync(() -> {
                try {
                    runner.ingest(source, pack.manifest().stateCode(), pack.manifest().version(), batch);
                } catch (RuntimeException expectedTimeout) {
                    // nothing answers here; only the command matters
                }
            });
            JsonNode command = awaitCommand(sql);
            assertEquals("sha256:" + "a".repeat(64) + "/landed.csv", command.path("payload").path("fileRef").asText());
            assertFalse(command.path("payload").path("parameters").has("SOURCE_FILE"),
                    "no path of this machine travels in the command");
            assertTrue(command.path("payload").path("parameters").has("CSV_HEADER"));
            assertEquals(Files.size(SampleData.REPOSITORY_ROOT.resolve(source.location())), uploaded.get().length);
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void aFailedRemoteBatchFailsTheStageWithItsMessage() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        LoadedStatePack pack = firstPack();
        SourceDefinition source = pack.sources().getFirst();
        UUID batch = startBatch(connections, pack, source);
        var runner = new RemoteHopRunner(connections, localRunner(connections), Duration.ofMinutes(2));
        CompletableFuture<HopRunner.Result> result = CompletableFuture.supplyAsync(() ->
                runner.ingest(source, pack.manifest().stateCode(), pack.manifest().version(), batch));
        JsonNode command = awaitCommand(sql);

        var messaging = new RemoteIngestionMessaging(connections, KAFKA.getBootstrapServers());
        messaging.start();
        try {
            deliverTwice(List.of(landedEvents(command, pack, source, "FAILED").getLast()));
            HopRunner.Result failed = result.get(90, TimeUnit.SECONDS);
            assertFalse(failed.succeeded());
            assertTrue(failed.output().contains("file not readable"), failed.output());
        } finally {
            messaging.destroy();
        }
    }

    private static LoadedStatePack firstPack() {
        return SampleData.loader(SampleData.STATE_PACKS).loadAll(SampleData.STATE_PACKS).values().stream()
                .sorted(Comparator.comparing(p -> p.manifest().packId()))
                .findFirst().orElseThrow();
    }

    private static ProcessHopRunner localRunner(PostgresConnectionFactory connections) {
        var properties = new StatePackProperties();
        properties.setRepositoryRoot(SampleData.REPOSITORY_ROOT.toString());
        return new ProcessHopRunner(connections, null, properties);
    }

    /** The batch row the onboarding INGESTED stage creates before it calls the Hop runner. */
    private static UUID startBatch(PostgresConnectionFactory connections, LoadedStatePack pack, SourceDefinition source) {
        UUID batch = UUID.randomUUID();
        new Sql(connections).execute("insert into ingestion_batch (batch_id, state_code, source_system, source_type,"
                + " source_schema_version, state_pack_version, pipeline_template, status)"
                + " values (?, ?, ?, ?, ?, ?, ?, 'STARTED')", batch, pack.manifest().stateCode(), source.sourceId(),
                source.sourceType(), source.sourceSchemaVersion(), pack.manifest().version(), source.hopTemplate());
        return batch;
    }

    private static JsonNode awaitCommand(Sql sql) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (sql.count("select count(*) from outbox_event where topic = ?", Topics.CMD_INGEST_REQUESTED) == 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("No ingest-requested command");
            }
            Thread.sleep(100);
        }
        return Outbox.json().readTree(sql.text("select payload::text from outbox_event where topic = ?",
                Topics.CMD_INGEST_REQUESTED));
    }

    /** What ingestion-service publishes after landing the command's file as generic-file-ingest.hpl does. */
    private static List<JsonNode> landedEvents(JsonNode command, LoadedStatePack pack, SourceDefinition source,
                                               String status) throws Exception {
        JsonNode p = command.path("payload").path("parameters");
        UUID correlation = UUID.fromString(command.path("correlationId").asText());
        List<JsonNode> events = new ArrayList<>();
        int count = 0;
        if ("COMPLETED".equals(status)) {
            List<String> lines = Files.readAllLines(Path.of(p.path("SOURCE_FILE").asText()), StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                ObjectNode r = Outbox.json().createObjectNode();
                String raw = UUID.randomUUID().toString();
                r.put("rawRecordId", raw);
                r.put("batchId", p.path("BATCH_ID").asText());
                r.put("sourceSystem", source.sourceId());
                r.put("sourceLine", i + 1);
                r.put("payloadFormat", "CSV_ROW");
                r.put("rawPayload", "{\"csvHeader\":\"" + p.path("CSV_HEADER").asText() + "\",\"rawLine\":\""
                        + lines.get(i) + "\"}");
                r.put("landedAt", "2026-11-04T00:00:0" + (i % 10) + "Z");
                r.putObject("sourceMetadata").put("sourceEntity", source.sourceId());
                r.put("statePackVersion", pack.manifest().version());
                r.put("sourceSchemaVersion", source.sourceSchemaVersion());
                r.put("pipelineTemplate", source.hopTemplate());
                r.put("sourceType", source.sourceType());
                events.add(envelope("uddp.ingestion.raw-record-received", source.sourceId() + ":" + raw,
                        pack.manifest().stateCode(), correlation, r));
                count++;
            }
        }
        ObjectNode done = Outbox.json().createObjectNode();
        done.put("batchId", p.path("BATCH_ID").asText());
        done.put("recordCount", count);
        done.put("status", status);
        done.put("message", "FAILED".equals(status) ? "Generic Hop exited 1: file not readable" : null);
        events.add(envelope("uddp.ingestion.batch-completed", p.path("BATCH_ID").asText(), pack.manifest().stateCode(),
                correlation, done));
        return events;
    }

    private static JsonNode envelope(String type, String key, String stateCode, UUID correlation, ObjectNode payload) {
        ObjectNode e = Outbox.json().createObjectNode();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("eventType", type);
        e.put("schemaVersion", 1);
        e.put("occurredAt", "2026-11-04T00:00:00Z");
        e.put("producer", "ingestion-service");
        e.put("partitionKey", key);
        e.put("correlationId", correlation.toString());
        e.put("stateCode", stateCode);
        e.set("payload", payload);
        return e;
    }

    private static void deliverTwice(List<JsonNode> events) throws Exception {
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            for (int delivery = 0; delivery < 2; delivery++) {
                for (JsonNode event : events) {
                    String topic = event.path("eventType").asText().endsWith("batch-completed")
                            ? Topics.BATCH_COMPLETED : Topics.RAW_RECORD_RECEIVED;
                    producer.send(new ProducerRecord<>(topic, event.path("partitionKey").asText(), event.toString()))
                            .get();
                }
            }
        }
    }
}
