package gov.uddp.core.gates;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Configurable gates per source (Track C3), evaluated on every onboarding run and on dry runs. A gate compares one
 * measured value of a delivery with a threshold; every evaluation is stored.
 */
@Service
public class QualityGateService {
    public enum GateType {
        /** Share of delivered records that became a canonical record without a processing error (minimum). */
        MAPPING_COVERAGE_PCT(true),
        /** Share of records with a data-quality finding (maximum). */
        ERROR_RATE_PCT(false),
        /** Crosswalk values the pack does not map (maximum). */
        UNMAPPED_CODE_COUNT(false),
        /** Share of the previous delivery's records that are new, changed or removed (maximum). */
        CHANGE_VOLUME_PCT(false),
        /** Absolute change of the match rate, from a {@link MetricsSource} when one provides it (maximum). */
        MATCH_RATE_CHANGE_PCT(false);

        final boolean minimum;

        GateType(boolean minimum) {
            this.minimum = minimum;
        }
    }

    public record Threshold(GateType type, double value, boolean enabled) {
    }

    public record Result(GateType type, Double observed, double threshold, String status) {
        public boolean failed() {
            return "FAIL".equals(status);
        }
    }

    private final PostgresConnectionFactory connections;
    private final List<MetricsSource> metricSources;

    @Autowired
    public QualityGateService(PostgresConnectionFactory connections, ObjectProvider<MetricsSource> metricSources) {
        this(connections, metricSources.orderedStream().toList());
    }

    public QualityGateService(PostgresConnectionFactory connections, List<MetricsSource> metricSources) {
        this.connections = connections;
        this.metricSources = metricSources;
    }

    // ---- thresholds ------------------------------------------------------------------------------------------------

    public List<Threshold> thresholds(String state, String source) {
        List<Threshold> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select gate_type,threshold,enabled from source_quality_gate"
                        + " where state_code=? and source_system=? order by gate_type")) {
            s.setString(1, state);
            s.setString(2, source);
            var r = s.executeQuery();
            while (r.next()) {
                rows.add(new Threshold(GateType.valueOf(r.getString(1)), r.getDouble(2), r.getBoolean(3)));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the quality gates", e);
        }
    }

    public void saveThresholds(String state, String source, List<Threshold> thresholds) {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var delete = c.prepareStatement("delete from source_quality_gate where state_code=? and source_system=?")) {
                delete.setString(1, state);
                delete.setString(2, source);
                delete.executeUpdate();
            }
            for (Threshold t : thresholds) {
                try (var s = c.prepareStatement("insert into source_quality_gate(state_code,source_system,gate_type,"
                        + "threshold,enabled) values(?,?,?,?,?)")) {
                    s.setString(1, state);
                    s.setString(2, source);
                    s.setString(3, t.type().name());
                    s.setDouble(4, t.value());
                    s.setBoolean(5, t.enabled());
                    s.executeUpdate();
                }
            }
            c.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the quality gates", e);
        }
    }

    // ---- evaluation ------------------------------------------------------------------------------------------------

    /** Evaluates the enabled gates for the run's delivery and stores the results. */
    public List<Result> evaluate(UUID runId) {
        String state;
        String source;
        UUID batch;
        try (var c = connections.open();
                var s = c.prepareStatement("select r.state_code,r.source_system,(select e.ingestion_batch_id from"
                        + " onboarding_stage_execution e where e.onboarding_run_id=r.onboarding_run_id and"
                        + " e.stage='INGESTED' and e.status='COMPLETED' order by e.attempt desc limit 1)"
                        + " from onboarding_run r where r.onboarding_run_id=?")) {
            s.setObject(1, runId);
            var r = s.executeQuery();
            r.next();
            state = r.getString(1);
            source = r.getString(2);
            batch = r.getObject(3, UUID.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the run to gate", e);
        }
        return evaluate(state, source, runId, batch, false);
    }

    /** The batch the run's INGESTED stage landed, or null when it has none yet. */
    public UUID deliveryBatch(UUID runId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select e.ingestion_batch_id from onboarding_stage_execution e"
                        + " where e.onboarding_run_id=? and e.stage='INGESTED' and e.status='COMPLETED'"
                        + " order by e.attempt desc limit 1")) {
            s.setObject(1, runId);
            var r = s.executeQuery();
            return r.next() ? r.getObject(1, UUID.class) : null;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the run's delivery", e);
        }
    }

    /** Evaluates the gates against the source's latest completed delivery without a run. */
    public List<Result> dryRun(String state, String source) {
        return evaluate(state, source, null, latestBatch(state, source).orElse(null), true);
    }

    public List<Map<String, Object>> results(UUID runId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select gate_type,observed,threshold,status,evaluated_at from"
                        + " quality_gate_result where onboarding_run_id=? order by evaluated_at,gate_type")) {
            s.setObject(1, runId);
            var r = s.executeQuery();
            while (r.next()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("gate", r.getString(1));
                x.put("observed", r.getObject(2));
                x.put("threshold", r.getObject(3));
                x.put("status", r.getString(4));
                x.put("evaluatedAt", r.getObject(5));
                rows.add(x);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the gate results", e);
        }
    }

    private List<Result> evaluate(String state, String source, UUID runId, UUID batch, boolean dryRun) {
        Map<GateType, Double> measured = batch == null ? new EnumMap<>(GateType.class) : measure(batch);
        for (MetricsSource provider : metricSources) {
            Map<String, Double> extra = provider.metrics(state, source, runId, batch);
            for (GateType type : GateType.values()) {
                if (extra.containsKey(type.name())) {
                    measured.put(type, extra.get(type.name()));
                }
            }
        }
        List<Result> results = new ArrayList<>();
        for (Threshold t : thresholds(state, source)) {
            if (!t.enabled()) {
                continue;
            }
            Double observed = measured.get(t.type());
            String status = observed == null ? "NOT_AVAILABLE"
                    : (t.type().minimum ? observed >= t.value() : Math.abs(observed) <= t.value()) ? "PASS" : "FAIL";
            Result result = new Result(t.type(), observed, t.value(), status);
            results.add(result);
            store(state, source, runId, result, dryRun);
        }
        return results;
    }

    /** The measured value of each built-in gate for one delivery; a value that cannot be measured is absent. */
    private Map<GateType, Double> measure(UUID batch) {
        Map<GateType, Double> values = new EnumMap<>(GateType.class);
        int rows = number("select count(*) from raw_record where batch_id=?", batch);
        int errors = number("select count(*) from canonical_processing_error where batch_id=?", batch);
        if (rows > 0) {
            values.put(GateType.MAPPING_COVERAGE_PCT, 100.0 * (rows - errors) / rows);
        }
        values.put(GateType.UNMAPPED_CODE_COUNT, (double) number("select count(*) from canonical_processing_error"
                + " where batch_id=? and error_message like 'No crosswalk value%'", batch)
                + number("select count(*) from canonical_benefit_warning where batch_id=? and resolved_at is null", batch));
        int evaluated = number("select coalesce(max(records_evaluated),0) from dq_batch_evaluation where batch_id=?", batch);
        if (evaluated > 0) {
            values.put(GateType.ERROR_RATE_PCT, 100.0 * number("select coalesce(max(affected_record_count),0)"
                    + " from dq_batch_evaluation where batch_id=?", batch) / evaluated);
        }
        try (var c = connections.open();
                var s = c.prepareStatement("select new_count,changed_count,unchanged_count,removed_count"
                        + " from delivery_change_set where batch_id=?")) {
            s.setObject(1, batch);
            var r = s.executeQuery();
            if (r.next()) {
                int previous = r.getInt(2) + r.getInt(3) + r.getInt(4);
                if (previous > 0) {
                    values.put(GateType.CHANGE_VOLUME_PCT, 100.0 * (r.getInt(1) + r.getInt(2) + r.getInt(4)) / previous);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the delivery change counts", e);
        }
        return values;
    }

    private int number(String sql, UUID id) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            var r = s.executeQuery();
            r.next();
            return r.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot measure the delivery", e);
        }
    }

    private Optional<UUID> latestBatch(String state, String source) {
        try (var c = connections.open();
                var s = c.prepareStatement("select batch_id from ingestion_batch where state_code=? and source_system=?"
                        + " and status='COMPLETED' order by completed_at desc limit 1")) {
            s.setString(1, state);
            s.setString(2, source);
            var r = s.executeQuery();
            return r.next() ? Optional.of(r.getObject(1, UUID.class)) : Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot find the latest delivery", e);
        }
    }

    private void store(String state, String source, UUID runId, Result result, boolean dryRun) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into quality_gate_result(onboarding_run_id,state_code,source_system,"
                        + "gate_type,observed,threshold,status,dry_run) values(?,?,?,?,?,?,?,?)")) {
            s.setObject(1, runId);
            s.setString(2, state);
            s.setString(3, source);
            s.setString(4, result.type().name());
            s.setObject(5, result.observed());
            s.setDouble(6, result.threshold());
            s.setString(7, result.status());
            s.setBoolean(8, dryRun);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the gate result", e);
        }
    }
}
