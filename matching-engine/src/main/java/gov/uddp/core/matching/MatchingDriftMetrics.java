package gov.uddp.core.matching;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.gates.MetricsSource;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Matching Studio's contribution to the quality gates and monitoring (Track B): MATCH_RATE_CHANGE_PCT is the change
 * of the match rate between the two latest evaluated matching runs, in percentage points. Matching runs span every
 * source, so each source reports the same value; with fewer than two runs the metric is absent.
 */
@Component
public class MatchingDriftMetrics implements MetricsSource {
    private final PostgresConnectionFactory connections;

    public MatchingDriftMetrics(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public String name() {
        return "Matching Studio";
    }

    @Override
    public Map<String, Double> metrics(String stateCode, String sourceSystem, UUID runId, UUID batchId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select match_rate from matching_run_metrics order by created_at desc"
                        + " limit 2")) {
            var r = s.executeQuery();
            if (!r.next()) {
                return Map.of();
            }
            double latest = r.getDouble(1);
            if (!r.next()) {
                return Map.of();
            }
            return Map.of("MATCH_RATE_CHANGE_PCT", (latest - r.getDouble(1)) * 100.0);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the matching run metrics", e);
        }
    }
}
