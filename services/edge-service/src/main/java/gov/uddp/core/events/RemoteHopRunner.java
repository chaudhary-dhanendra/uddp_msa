package gov.uddp.core.events;

import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.HopInvocations;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.messaging.Outbox;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The INGESTED stage with ingestion-service (MSA Phase 3): resolves the source to its generic template and
 * allow-listed parameters (ARCHITECTURE.md A, the same set {@link HopInvocations} computes), sends
 * cmd.ingestion.ingest-requested and waits until ingestion-service reported the batch and every landed row has been
 * projected here ({@link RemoteRawProjection}). Files are uploaded to ingestion-service when its URL is configured;
 * a database source is named by its connectionRef, whose credentials live only in ingestion-service.
 */
@Component
@Primary
@ConditionalOnProperty(name = "uddp.ingestion.remote", havingValue = "true")
public class RemoteHopRunner implements HopRunner {

    private final PostgresConnectionFactory connections;
    private final HopInvocations local;
    private final Duration timeout;

    public RemoteHopRunner(PostgresConnectionFactory connections, HopInvocations local,
                           @Value("${uddp.ingestion.remote-timeout:PT30M}") Duration timeout) {
        this.connections = connections;
        this.local = local;
        this.timeout = timeout;
    }

    private String ingestionUrl = "";

    /**
     * ingestion-service's address: with it, delivered files are uploaded (POST /api/ingestion/files) and named by
     * fileRef, so the services need no shared disk; without it the command carries the file's path.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setIngestionUrl(@Value("${uddp.ingestion.url:}") String ingestionUrl) {
        this.ingestionUrl = ingestionUrl == null ? "" : ingestionUrl.strip();
    }


    /** With ingestion-service's URL it stages connector and landing-folder deliveries itself. */
    @Override
    public boolean stagesDeliveries() {
        return !ingestionUrl.isEmpty();
    }

    /**
     * SOURCE_TESTED where the source is read: a file this machine has is uploaded at ingestion, so it only has to be
     * readable here; anything else (connector, landing folder, database table) is tested by ingestion-service.
     */
    @Override
    public void testSource(SourceDefinition source, String stateCode) {
        if (!source.database() && source.location() != null
                && java.nio.file.Files.isReadable(local.sourceFile(source))
                && java.nio.file.Files.isRegularFile(local.sourceFile(source))) {
            return;
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("stateCode", stateCode);
        body.put("sourceSystem", source.sourceId());
        body.put("location", source.location());
        body.put("connectionRef", source.connectionRef());
        body.put("table", source.table());
        body.put("query", source.query());
        try {
            var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(gov.uddp.contracts.ServiceUrls.resolve(ingestionUrl) + "/api/ingestion/sources/test"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(Outbox.json().writeValueAsString(body)))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(Outbox.json().readTree(response.body()).path("error")
                        .asText("The source is not ready in ingestion-service"));
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot test the source in ingestion-service", e);
        }
    }

    @Override
    public Result ingest(SourceDefinition source, String stateCode, String packVersion, UUID batchId) {
        UUID correlation = UUID.randomUUID();
        try (var c = connections.open()) {
            var payload = Outbox.json().createObjectNode();
            HopInvocations.Invocation invocation;
            if (source.database()) {
                // No credentials here: ingestion-service resolves connectionRef and reads the header itself.
                var parameters = new java.util.LinkedHashMap<String, String>();
                parameters.put("STATE_CODE", stateCode);
                parameters.put("SOURCE_SYSTEM", source.sourceId());
                parameters.put("SOURCE_ENTITY", source.sourceId());
                parameters.put("SOURCE_SCHEMA_VERSION", source.sourceSchemaVersion());
                parameters.put("STATE_PACK_VERSION", packVersion);
                parameters.put("BATCH_ID", batchId.toString());
                invocation = new HopInvocations.Invocation(source.hopTemplate(), parameters);
                payload.put("connectionRef", source.connectionRef());
                payload.put("table", source.table());
                payload.put("query", source.query());
            } else if (!ingestionUrl.isEmpty()) {
                // ingestion-service takes the delivery: the source's connector, the file uploaded from here (when
                // this machine has it), the source's landing folder there, in that order.
                payload.put("delivery", "auto");
                payload.put("location", source.location());
                java.nio.file.Path localFile = source.location() == null ? null
                        : local.sourceFile(source);
                var parameters = new java.util.LinkedHashMap<String, String>();
                if (localFile != null && java.nio.file.Files.isRegularFile(localFile)) {
                    parameters.putAll(local.fileInvocation(source, stateCode, packVersion, batchId).parameters());
                    parameters.remove("FILE");
                    parameters.remove("SOURCE_FILE");
                    payload.put("fileRef", upload(localFile));
                } else {
                    parameters.put("STATE_CODE", stateCode);
                    parameters.put("SOURCE_SYSTEM", source.sourceId());
                    parameters.put("SOURCE_ENTITY", source.sourceId());
                    parameters.put("SOURCE_SCHEMA_VERSION", source.sourceSchemaVersion());
                    parameters.put("STATE_PACK_VERSION", packVersion);
                    parameters.put("BATCH_ID", batchId.toString());
                    if ("xlsx".equals(source.formatOrDefault())) {
                        parameters.put("SHEETS", HopInvocations.sheets(source));
                    }
                }
                invocation = new HopInvocations.Invocation("xlsx".equals(source.formatOrDefault())
                        ? "generic-excel-ingest.hpl" : "generic-file-ingest.hpl", parameters);
            } else {
                invocation = local.fileInvocation(source, stateCode, packVersion, batchId);
            }
            payload.put("batchId", batchId.toString());
            payload.put("sourceSystem", source.sourceId());
            payload.put("sourceType", source.sourceType());
            payload.put("sourceSchemaVersion", source.sourceSchemaVersion());
            payload.put("statePackVersion", packVersion);
            payload.put("pipelineTemplate", invocation.template());
            var parameters = payload.putObject("parameters");
            invocation.parameters().forEach(parameters::put);
            Outbox.append(c, Topics.CMD_INGEST_REQUESTED, new EventEnvelope(UUID.randomUUID(),
                    "uddp.cmd.ingestion.ingest-requested", 1, Instant.now(), "edge-service", batchId.toString(),
                    correlation, null, stateCode, null, payload));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot request ingestion from ingestion-service", e);
        }
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (var c = connections.open(); var s = c.prepareStatement("select b.remote_status, b.remote_record_count,"
                    + " b.remote_message, (select count(*) from raw_record r where r.batch_id = b.batch_id)"
                    + " from ingestion_batch b where b.batch_id = ?")) {
                s.setObject(1, batchId);
                try (var r = s.executeQuery()) {
                    if (r.next() && r.getString(1) != null) {
                        if ("FAILED".equals(r.getString(1))) {
                            return new Result(1, r.getString(3));
                        }
                        if (r.getLong(4) >= r.getInt(2)) {
                            return new Result(0, "Landed by ingestion-service: " + r.getInt(2) + " records");
                        }
                    }
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for ingestion-service", e);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read the projected batch", e);
            }
        }
        throw new IllegalStateException("ingestion-service did not land batch " + batchId + " within " + timeout);
    }

    /** Uploads a delivered file to ingestion-service and returns its fileRef. */
    private String upload(java.nio.file.Path file) throws Exception {
        String url = gov.uddp.contracts.ServiceUrls.resolve(ingestionUrl) + "/api/ingestion/files?name="
                + java.net.URLEncoder.encode(file.getFileName().toString(), java.nio.charset.StandardCharsets.UTF_8);
        var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(url)).header("Content-Type", "application/octet-stream")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofFile(file)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("ingestion-service refused the file: " + response.statusCode());
        }
        return Outbox.json().readTree(response.body()).path("fileRef").asText();
    }
}
