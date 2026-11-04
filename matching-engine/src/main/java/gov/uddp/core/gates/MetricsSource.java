package gov.uddp.core.gates;

import java.util.Map;
import java.util.UUID;

/**
 * Metrics another part of the platform can contribute to the quality gates and to monitoring (Track C). Keys are
 * metric names, for example {@code MATCH_RATE_CHANGE_PCT}; a source reports only the metrics it has. Matching Studio
 * (Track B) and the rules engine (Track A) plug in here by implementing this interface; nothing here depends on them.
 */
public interface MetricsSource {
    /** Short name of the provider, for display. */
    String name();

    /**
     * Current metrics for a source. {@code runId} is the onboarding run being gated, or null for a dry run and for
     * monitoring; the batch is the delivery under evaluation, or null.
     */
    Map<String, Double> metrics(String stateCode, String sourceSystem, UUID runId, UUID batchId);
}
