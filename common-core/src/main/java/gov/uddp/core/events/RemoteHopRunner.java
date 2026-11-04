package gov.uddp.core.events;

import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.ProcessHopRunner;
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
 * allow-listed parameters (ARCHITECTURE.md A, the same set {@link ProcessHopRunner} runs), sends
 * cmd.ingestion.ingest-requested and waits until ingestion-service reported the batch and every landed row has been
 * projected here ({@link RemoteRawProjection}). Database sources keep the local runner: their credentials are not
 * sent over Kafka.
 */
@Component
@Primary
@ConditionalOnProperty(name = "uddp.ingestion.remote", havingValue = "true")
public class RemoteHopRunner implements HopRunner {

    private final PostgresConnectionFactory connections;
    private final ProcessHopRunner local;
    private final Duration timeout;

    public RemoteHopRunner(PostgresConnectionFactory connections, ProcessHopRunner local,
                           @Value("${uddp.ingestion.remote-timeout:PT30M}") Duration timeout) {
        this.connections = connections;
        this.local = local;
        this.timeout = timeout;
    }

    @Override
    public Result ingest(SourceDefinition source, String stateCode, String packVersion, UUID batchId) {
        if (source.database()) {
            return local.ingest(source, stateCode, packVersion, batchId);
        }
        UUID correlation = UUID.randomUUID();
        try (var c = connections.open()) {
            var invocation = local.fileInvocation(source, stateCode, packVersion, batchId);
            var payload = Outbox.json().createObjectNode();
            payload.put("batchId", batchId.toString());
            payload.put("sourceSystem", source.sourceId());
            payload.put("sourceType", source.sourceType());
            payload.put("sourceSchemaVersion", source.sourceSchemaVersion());
            payload.put("statePackVersion", packVersion);
            payload.put("pipelineTemplate", invocation.template());
            var parameters = payload.putObject("parameters");
            invocation.parameters().forEach(parameters::put);
            Outbox.append(c, Topics.CMD_INGEST_REQUESTED, new EventEnvelope(UUID.randomUUID(),
                    "uddp.cmd.ingestion.ingest-requested", 1, Instant.now(), "common-core", batchId.toString(),
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
}
