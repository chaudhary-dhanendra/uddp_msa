package gov.uddp.canonical;

import gov.uddp.contracts.StatePackClient;
import gov.uddp.core.canonical.CanonicalProcessingSummary;
import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.statepack.StatePackLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Processes a Raw batch once canonical-service holds all of it (ingestion-service reported it COMPLETED and every
 * announced record has been projected). The batch's State Pack is fetched from statepack-service by state and version,
 * verified by digest and cached for the loader first; then the monolith's unchanged engine maps, converts,
 * crosswalks and validates it and its events go to the outbox (resident-created, family-observed, the DLQ and
 * batch-processed). Each batch is processed once; {@code processed_at} marks it.
 */
@Service
public class CanonicalBatches {

    private final PostgresConnectionFactory connections;
    private final CanonicalResidentProcessingService processing;
    private final StatePackClient packs;
    private final StatePackLoader loader;
    private final Path cache;
    private final Duration wait;

    public CanonicalBatches(PostgresConnectionFactory canonicalConnections, CanonicalResidentProcessingService processing,
                            StatePackClient packs, StatePackLoader loader,
                            @Value("${uddp.canonical.pack-cache}") String cache,
                            @Value("${uddp.canonical.batch-wait:PT10M}") Duration wait) {
        this.connections = canonicalConnections;
        this.processing = processing;
        this.packs = packs;
        this.loader = loader;
        this.cache = Path.of(cache).toAbsolutePath();
        this.wait = wait;
    }

    /** Batches ingestion-service completed that are not processed yet (for a restart). */
    public List<UUID> pending() {
        List<UUID> out = new ArrayList<>();
        try (var c = connections.open(); var s = c.prepareStatement("select batch_id from ingestion_batch"
                + " where remote_status = 'COMPLETED' and processed_at is null order by started_at");
                var r = s.executeQuery()) {
            while (r.next()) {
                out.add(r.getObject(1, UUID.class));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list pending batches", e);
        }
        return out;
    }

    /** Waits for the whole batch, then processes it; empty when it was processed already or failed upstream. */
    public synchronized Optional<CanonicalProcessingSummary> process(UUID batchId) {
        Instant deadline = Instant.now().plus(wait);
        while (true) {
            var state = state(batchId);
            if (state.isPresent() && (state.get().processed() || "FAILED".equals(state.get().remoteStatus()))) {
                return Optional.empty();
            }
            if (state.isPresent() && "COMPLETED".equals(state.get().remoteStatus())
                    && state.get().received() >= state.get().expected()) {
                ensurePack(state.get().stateCode(), state.get().packVersion());
                var summary = processing.processBatch(batchId);
                markProcessed(batchId);
                return Optional.of(summary);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Batch " + batchId + " is not complete in canonical-service");
            }
            sleep();
        }
    }

    private void ensurePack(String stateCode, String version) {
        try {
            var pack = packs.resolve(stateCode, version);
            StatePackClient.materialize(pack, cache);
            loader.reload();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot obtain the State Pack for " + stateCode + " " + version, e);
        }
    }

    private record State(String remoteStatus, int expected, long received, boolean processed, String stateCode,
                         String packVersion) {
    }

    private Optional<State> state(UUID batchId) {
        try (var c = connections.open(); var s = c.prepareStatement("select b.remote_status,"
                + " coalesce(b.remote_record_count, 0), (select count(*) from raw_record r where r.batch_id = b.batch_id),"
                + " b.processed_at is not null, b.state_code, b.state_pack_version from ingestion_batch b"
                + " where b.batch_id = ?")) {
            s.setObject(1, batchId);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(new State(r.getString(1), r.getInt(2), r.getLong(3), r.getBoolean(4),
                        r.getString(5), r.getString(6))) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read batch " + batchId, e);
        }
    }

    private void markProcessed(UUID batchId) {
        try (var c = connections.open(); var s = c.prepareStatement("update ingestion_batch set processed_at = now(),"
                + " status = 'COMPLETED', completed_at = coalesce(completed_at, now()),"
                + " record_count = remote_record_count where batch_id = ?")) {
            s.setObject(1, batchId);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot mark batch " + batchId + " processed", e);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a batch", e);
        }
    }
}
