package gov.uddp.core.monitoring;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.gates.MetricsSource;
import gov.uddp.core.statepack.StatePackLoader;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * What an operator watches (Track C6): per batch data-quality score, trends per source and rule, load failures, gate
 * failures, schedule misses and, when a {@link MetricsSource} reports it, matching drift. Read-only over existing
 * tables; nothing is stored here.
 */
@Service
public class MonitoringService {
    /** Window of the failure and miss counters. */
    static final Duration WINDOW = Duration.ofDays(7);

    /** The names of the built-in metrics an alert rule can watch. */
    public enum Metric {
        DQ_SCORE, LOAD_FAILURES, GATE_FAILURES, SCHEDULE_MISSES, MATCH_DRIFT, PENDING_APPROVALS,
        /** Hours since the LGD master last synced successfully (Track F3; platform-wide). */
        LGD_MASTER_AGE_HOURS,
        /** Consecutive failed LGD master syncs (Track F3; platform-wide). */
        LGD_SYNC_FAILURES,
        /** Held records waiting for a steward (platform-wide). */
        HELD_OPEN,
        /** Hours the oldest waiting held record has waited (platform-wide). */
        HELD_OLDEST_HOURS,
        /** Held records a steward rectified (platform-wide). */
        HELD_RECTIFIED,
        /** Held records a steward rejected for good (platform-wide). */
        HELD_REJECTED,
        /** Mean hours from hold to a steward's decision (platform-wide). */
        HELD_HOURS_TO_DECIDE;

        /** True for metrics measured once for the platform rather than per source. */
        public boolean platformWide() {
            return this == LGD_MASTER_AGE_HOURS || this == LGD_SYNC_FAILURES || name().startsWith("HELD_");
        }
    }

    public record SourceKey(String stateCode, String sourceSystem) {
    }

    /** The key platform-wide notifications are stored under. */
    public static final SourceKey PLATFORM = new SourceKey("PLATFORM", "platform");

    private final PostgresConnectionFactory connections;
    private final StatePackLoader packs;
    private final List<MetricsSource> metricSources;
    private final Clock clock;
    private List<PlatformMetricsSource> platformSources = List.of();

    @Autowired
    public MonitoringService(PostgresConnectionFactory connections, StatePackLoader packs,
                             ObjectProvider<MetricsSource> metricSources) {
        this(connections, packs, metricSources.orderedStream().toList(), Clock.systemUTC());
    }

    public MonitoringService(PostgresConnectionFactory connections, StatePackLoader packs,
                             List<MetricsSource> metricSources, Clock clock) {
        this.connections = connections;
        this.packs = packs;
        this.metricSources = metricSources;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setPlatformSources(List<PlatformMetricsSource> platformSources) {
        this.platformSources = List.copyOf(platformSources);
    }

    /** Platform-wide metrics (Track F3), merged from every {@link PlatformMetricsSource}. */
    public Map<Metric, Double> platformMetrics() {
        Map<Metric, Double> values = new LinkedHashMap<>();
        platformSources.forEach(source -> values.putAll(source.platformMetrics()));
        return values;
    }

    /** Every source that has delivered or been scheduled, optionally limited by department code. */
    public List<SourceKey> sources(String department) {
        List<SourceKey> keys = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select state_code,source_system from ingestion_batch union"
                        + " select state_code,source_system from source_schedule order by 1,2")) {
            var r = s.executeQuery();
            while (r.next()) {
                SourceKey key = new SourceKey(r.getString(1), r.getString(2));
                if (department == null || department.isBlank() || department.equalsIgnoreCase(department(key.stateCode()))) {
                    keys.add(key);
                }
            }
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list the monitored sources", e);
        }
    }

    public String department(String stateCode) {
        return packs.find(stateCode).map(p -> p.manifest().departmentCode()).orElse(null);
    }

    /** All metrics of one source; a metric that cannot be measured yet is absent. */
    public Map<Metric, Double> metrics(String state, String source) {
        Instant since = clock.instant().minus(WINDOW);
        Map<Metric, Double> values = new LinkedHashMap<>();
        value("select 100.0 - 100.0 * affected_record_count / nullif(records_evaluated, 0) from dq_batch_evaluation"
                + " where state_code=? and source_system=? order by evaluated_at desc limit 1", state, source, null)
                .ifPresent(v -> values.put(Metric.DQ_SCORE, v));
        values.put(Metric.LOAD_FAILURES, value("select count(*) from ingestion_batch where state_code=? and"
                + " source_system=? and status='FAILED' and started_at >= ?", state, source, since).orElse(0.0));
        values.put(Metric.GATE_FAILURES, value("select count(*) from quality_gate_result where state_code=? and"
                + " source_system=? and status='FAIL' and not dry_run and evaluated_at >= ?", state, source, since)
                .orElse(0.0));
        values.put(Metric.SCHEDULE_MISSES, value("select count(*) from schedule_trigger where state_code=? and"
                + " source_system=? and (status='SKIPPED' or status='START_FAILED') and due_at >= ?", state, source, since)
                .orElse(0.0));
        for (MetricsSource provider : metricSources) {
            Map<String, Double> reported = provider.metrics(state, source, null, null);
            Double drift = reported.get("MATCH_RATE_CHANGE_PCT");
            if (drift != null) {
                values.put(Metric.MATCH_DRIFT, Math.abs(drift));
            }
            Double pending = reported.get(Metric.PENDING_APPROVALS.name());
            if (pending != null) {
                values.merge(Metric.PENDING_APPROVALS, pending, Double::sum);
            }
        }
        return values;
    }

    /** One row per source for the monitoring screen, limited to the department and the sources the caller may see. */
    public List<Map<String, Object>> overview(String department, String source,
                                              java.util.function.Predicate<String> stateAllowed) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SourceKey key : sources(department)) {
            if (!stateAllowed.test(key.stateCode()) || (source != null && !source.isBlank()
                    && !source.equals(key.sourceSystem()))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stateCode", key.stateCode());
            row.put("sourceSystem", key.sourceSystem());
            row.put("department", department(key.stateCode()));
            Map<Metric, Double> metrics = metrics(key.stateCode(), key.sourceSystem());
            for (Metric metric : Metric.values()) {
                if (metric.platformWide()) {
                    continue;
                }
                row.put(metric.name(), metrics.get(metric));
            }
            rows.add(row);
        }
        return rows;
    }

    /** Data-quality score per batch, oldest first (the trend of a source). */
    public List<Map<String, Object>> batchTrend(String state, String source, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select * from (select batch_id,evaluated_at,records_evaluated,"
                        + "affected_record_count,error_count,warning_count,100.0 - 100.0 * affected_record_count /"
                        + " nullif(records_evaluated, 0) as score from dq_batch_evaluation where state_code=? and"
                        + " source_system=? order by evaluated_at desc limit ?) x order by evaluated_at")) {
            s.setString(1, state);
            s.setString(2, source);
            s.setInt(3, Math.max(1, Math.min(limit, 200)));
            var r = s.executeQuery();
            while (r.next()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("batchId", r.getObject(1));
                x.put("evaluatedAt", r.getObject(2));
                x.put("recordsEvaluated", r.getInt(3));
                x.put("affectedRecords", r.getInt(4));
                x.put("errors", r.getInt(5));
                x.put("warnings", r.getInt(6));
                x.put("score", r.getObject(7));
                rows.add(x);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the quality trend", e);
        }
    }

    /** Findings per rule and batch (the trend of a rule), oldest batch first. */
    public List<Map<String, Object>> ruleTrend(String state, String source, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select r.rule_id,e.batch_id,e.evaluated_at,count(*) from dq_result r"
                        + " join dq_batch_evaluation e on e.dq_evaluation_id=r.dq_evaluation_id"
                        + " where e.state_code=? and e.source_system=? group by r.rule_id,e.batch_id,e.evaluated_at"
                        + " order by e.evaluated_at desc, r.rule_id limit ?")) {
            s.setString(1, state);
            s.setString(2, source);
            s.setInt(3, Math.max(1, Math.min(limit, 1000)));
            var r = s.executeQuery();
            while (r.next()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("ruleId", r.getString(1));
                x.put("batchId", r.getObject(2));
                x.put("evaluatedAt", r.getObject(3));
                x.put("findings", r.getInt(4));
                rows.add(x);
            }
            java.util.Collections.reverse(rows);
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the rule trend", e);
        }
    }

    private java.util.Optional<Double> value(String sql, String state, String source, Instant since) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setString(1, state);
            s.setString(2, source);
            if (sql.contains(">= ?")) {
                s.setTimestamp(3, Timestamp.from(since));
            }
            var r = s.executeQuery();
            if (r.next()) {
                double v = r.getDouble(1);
                return r.wasNull() ? java.util.Optional.empty() : java.util.Optional.of(v);
            }
            return java.util.Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot measure the source", e);
        }
    }
}
