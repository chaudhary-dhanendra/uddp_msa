package gov.uddp.core.read;

import gov.uddp.core.read.statepack.StatePackQueryService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Ingestion batches with their canonical outcome and data-quality results. */
@Service
public class BatchReadService {
    /** The latest DQ evaluation of each batch; earlier evaluations of the same batch are superseded. */
    static final String LATEST_EVALUATIONS = "(select distinct on (batch_id) * from dq_batch_evaluation"
            + " order by batch_id, evaluated_at desc, dq_evaluation_id desc)";

    public record BatchSummary(UUID batchId, String stateCode, String sourceSystem, String sourceType,
                               String sourceSchemaVersion, String statePackVersion, String pipelineTemplate,
                               String status, OffsetDateTime startedAt, OffsetDateTime completedAt, Long durationMs,
                               int recordCount, long rawRecords, long canonicalResidents, long processingErrors,
                               Integer dqErrors, Integer dqWarnings, String errorMessage) {
    }

    public record DqEvaluationView(UUID dqEvaluationId, OffsetDateTime evaluatedAt, String ruleConfigVersion,
                                   int recordsEvaluated, int rulesEvaluated, int errorCount, int warningCount,
                                   int affectedRecordCount) {
    }

    public record DqRuleSummary(String ruleId, String ruleType, String severity, String fieldPath, long findings,
                                long records, long batches) {
    }

    public record DqFinding(UUID dqResultId, UUID batchId, String stateCode, String sourceSystem, UUID rawRecordId,
                            UUID canonicalResidentId, String ruleId, String ruleType, String severity, String fieldPath,
                            String message, OffsetDateTime evaluatedAt) {
    }

    public record ProcessingErrorView(UUID rawRecordId, String message, OffsetDateTime occurredAt) {
    }

    /** A batch with its latest DQ evaluation (null when none ran), rule breakdown and canonical processing errors. */
    public record BatchDetail(BatchSummary batch, DqEvaluationView dq, List<DqRuleSummary> rules,
                              List<DqFinding> sampleFindings, List<ProcessingErrorView> processingErrors) {
    }

    static final int SAMPLE_LIMIT = 20;
    private static final Map<String, String> BATCH_SORT = Map.of(
            "startedAt", "b.started_at", "stateCode", "b.state_code", "sourceSystem", "b.source_system",
            "recordCount", "b.record_count", "status", "b.status");
    private static final Map<String, String> FINDING_SORT = Map.of(
            "evaluatedAt", "r.evaluated_at", "ruleId", "r.rule_id", "severity", "r.severity",
            "stateCode", "e.state_code");
    private static final String BATCH_COLUMNS = "select b.*,"
            + " (select count(*) from raw_record x where x.batch_id = b.batch_id) as raw_records,"
            + " (select count(*) from canonical_resident x where x.batch_id = b.batch_id"
            + " and x.canonical_entity_type = 'Resident') as canonical_residents,"
            + " (select count(*) from canonical_processing_error x where x.batch_id = b.batch_id) as processing_errors,"
            + " e.error_count as dq_errors, e.warning_count as dq_warnings"
            + " from ingestion_batch b left join " + LATEST_EVALUATIONS + " e on e.batch_id = b.batch_id";

    private final Db db;
    private final StatePackQueryService packs;

    public BatchReadService(Db db, StatePackQueryService packs) {
        this.db = db;
        this.packs = packs;
    }

    public Page<BatchSummary> batches(String state, String status, Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, BATCH_SORT, "startedAt,desc", "b.batch_id");
        String code = stateCode(state);
        String where = " where (cast(? as text) is null or b.state_code = cast(? as text))"
                + " and (cast(? as text) is null or b.status = cast(? as text))" + Db.inScope("b.state_code");
        String statusFilter = status == null || status.isBlank() ? null : status.strip().toUpperCase(java.util.Locale.ROOT);
        Object[] parameters = Db.withScope(code, code, statusFilter, statusFilter);
        long total = db.count("select count(*) from ingestion_batch b" + where, parameters);
        var items = db.list(BATCH_COLUMNS + where + query.sql(), BatchReadService::summary, parameters);
        return Page.of(items, query, total);
    }

    public BatchDetail batch(UUID batchId) {
        var batch = db.one(BATCH_COLUMNS + " where b.batch_id = ?", BatchReadService::summary, batchId)
                .orElseThrow(() -> new ReadNotFoundException("Ingestion batch does not exist: " + batchId));
        Db.requireInScope(batch.stateCode(), "Ingestion batch does not exist: " + batchId);
        var dq = db.one("select * from " + LATEST_EVALUATIONS + " e where e.batch_id = ?",
                r -> new DqEvaluationView(Db.uuid(r, "dq_evaluation_id"), Db.time(r, "evaluated_at"),
                        r.getString("rule_config_version"), r.getInt("records_evaluated"), r.getInt("rules_evaluated"),
                        r.getInt("error_count"), r.getInt("warning_count"), r.getInt("affected_record_count")),
                batchId).orElse(null);
        var errors = db.list("select raw_record_id, error_message, occurred_at from canonical_processing_error"
                        + " where batch_id = ? order by occurred_at, canonical_processing_error_id limit 100",
                r -> new ProcessingErrorView(Db.uuid(r, "raw_record_id"), r.getString("error_message"),
                        Db.time(r, "occurred_at")), batchId);
        var samples = findings(null, batchId, null, null, 0, SAMPLE_LIMIT, null).items();
        return new BatchDetail(batch, dq, rules(null, batchId), samples, errors);
    }

    /** Findings per rule and severity over the latest evaluation of every batch, optionally for one state or batch. */
    public List<DqRuleSummary> rules(String state, UUID batchId) {
        String code = stateCode(state);
        return db.list("select r.rule_id, r.rule_type, r.severity, min(r.field_path) as field_path, count(*) as findings,"
                        + " count(distinct r.raw_record_id) as records, count(distinct e.batch_id) as batches"
                        + " from dq_result r join " + LATEST_EVALUATIONS + " e on e.dq_evaluation_id = r.dq_evaluation_id"
                        + " where (cast(? as text) is null or e.state_code = cast(? as text))"
                        + " and (cast(? as uuid) is null or e.batch_id = cast(? as uuid))" + Db.inScope("e.state_code")
                        + " group by r.rule_id, r.rule_type, r.severity order by r.severity, r.rule_id",
                r -> new DqRuleSummary(r.getString("rule_id"), r.getString("rule_type"), r.getString("severity"),
                        r.getString("field_path"), r.getLong("findings"), r.getLong("records"), r.getLong("batches")),
                Db.withScope(code, code, batchId, batchId));
    }

    public Page<DqFinding> findings(String state, UUID batchId, String ruleId, String severity, Integer page,
                                    Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, FINDING_SORT, "evaluatedAt,desc", "r.dq_result_id");
        String code = stateCode(state);
        String level = severity == null || severity.isBlank() ? null : severity.strip().toUpperCase(java.util.Locale.ROOT);
        if (level != null && !level.equals("ERROR") && !level.equals("WARNING")) {
            throw new IllegalArgumentException("severity must be ERROR or WARNING");
        }
        String rule = ruleId == null || ruleId.isBlank() ? null : ruleId.strip();
        String from = " from dq_result r join " + LATEST_EVALUATIONS + " e on e.dq_evaluation_id = r.dq_evaluation_id"
                + " where (cast(? as text) is null or e.state_code = cast(? as text))"
                + " and (cast(? as uuid) is null or e.batch_id = cast(? as uuid))"
                + " and (cast(? as text) is null or r.rule_id = cast(? as text))"
                + " and (cast(? as text) is null or r.severity = cast(? as text))" + Db.inScope("e.state_code");
        Object[] parameters = Db.withScope(code, code, batchId, batchId, rule, rule, level, level);
        long total = db.count("select count(*)" + from, parameters);
        var items = db.list("select r.*, e.batch_id, e.state_code, e.source_system" + from + query.sql(),
                r -> new DqFinding(Db.uuid(r, "dq_result_id"), Db.uuid(r, "batch_id"), r.getString("state_code"),
                        r.getString("source_system"), Db.uuid(r, "raw_record_id"), Db.uuid(r, "canonical_resident_id"),
                        r.getString("rule_id"), r.getString("rule_type"), r.getString("severity"),
                        r.getString("field_path"), r.getString("message"), Db.time(r, "evaluated_at")),
                parameters);
        return Page.of(items, query, total);
    }

    /** The state code for a pack ID or state code filter; an unknown value is used as given (it matches nothing). */
    String stateCode(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return packs.stateCode(state).orElse(state.strip());
    }

    private static BatchSummary summary(java.sql.ResultSet r) throws java.sql.SQLException {
        var started = Db.time(r, "started_at");
        var completed = Db.time(r, "completed_at");
        return new BatchSummary(Db.uuid(r, "batch_id"), r.getString("state_code"), r.getString("source_system"),
                r.getString("source_type"), r.getString("source_schema_version"), r.getString("state_pack_version"),
                r.getString("pipeline_template"), r.getString("status"), started, completed, Db.millis(started, completed),
                r.getInt("record_count"), r.getLong("raw_records"), r.getLong("canonical_residents"),
                r.getLong("processing_errors"), Db.integer(r, "dq_errors"), Db.integer(r, "dq_warnings"),
                r.getString("error_message"));
    }
}
