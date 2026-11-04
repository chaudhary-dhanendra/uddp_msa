package gov.uddp.core.read;

import gov.uddp.core.onboarding.OnboardingStage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Onboarding runs across states with stage timelines, attempts, counts and timings. */
@Service
public class OnboardingReadService {
    public record OnboardingRunSummary(UUID runId, UUID statePackId, String packId, String stateCode,
                                       String sourceSystem, String statePackVersion, String status,
                                       String currentStage, String processingMode, OffsetDateTime startedAt,
                                       OffsetDateTime completedAt, Long durationMs, String failureMessage,
                                       int stageExecutions, int completedStages, int totalStages) {
    }

    public record StageExecutionView(UUID executionId, String stage, String status, int attempt,
                                     OffsetDateTime startedAt, OffsetDateTime completedAt, Long durationMs,
                                     Integer inputCount, Integer outputCount, UUID ingestionBatchId,
                                     UUID matchingRunId, String errorMessage) {
    }

    /**
     * One stage of the pipeline in order. {@code status} is the latest attempt's status, or PENDING when the stage has
     * not run; {@code latest} is that attempt (null when pending).
     */
    public record StageTimelineEntry(String stage, int order, String status, int attempts, StageExecutionView latest) {
    }

    /** What the existing write endpoints would currently accept for this run. */
    public record RunActions(boolean canRunNextStage, boolean canResume) {
    }

    /** Processing rejections for the batch ingested by this run; null until a batch exists. */
    public record ProcessingRejections(UUID batchId, int count) {
    }

    public record OnboardingRunDetail(OnboardingRunSummary run, List<StageTimelineEntry> timeline,
                                      List<StageExecutionView> executions, RunActions actions,
                                      ProcessingRejections processingRejections) {
    }

    private static final Map<String, String> SORT = Map.of("startedAt", "r.started_at", "stateCode", "r.state_code",
            "status", "r.status");
    private static final Set<String> STATUSES = Set.of("RUNNING", "FAILED", "COMPLETED");
    private static final String COLUMNS = "select r.*, l.pack_id,"
            + " (select count(*) from onboarding_stage_execution e where e.onboarding_run_id = r.onboarding_run_id)"
            + " as executions,"
            + " (select count(distinct e.stage) from onboarding_stage_execution e"
            + " where e.onboarding_run_id = r.onboarding_run_id and e.status = 'COMPLETED') as completed_stages"
            + " from onboarding_run r join state_pack_lifecycle l on l.state_pack_id = r.state_pack_id";

    private final Db db;
    private final BatchReadService batches;

    public OnboardingReadService(Db db, BatchReadService batches) {
        this.db = db;
        this.batches = batches;
    }

    public Page<OnboardingRunSummary> runs(String state, String status, Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, SORT, "startedAt,desc", "r.created_at desc, r.onboarding_run_id");
        String code = batches.stateCode(state);
        String s = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (s != null && !STATUSES.contains(s)) {
            throw new IllegalArgumentException("status must be RUNNING, FAILED or COMPLETED");
        }
        String where = " where (cast(? as text) is null or r.state_code = cast(? as text))"
                + " and (cast(? as text) is null or r.status = cast(? as text))" + Db.inScope("r.state_code");
        Object[] parameters = Db.withScope(code, code, s, s);
        long total = db.count("select count(*) from onboarding_run r" + where, parameters);
        return Page.of(db.list(COLUMNS + where + query.sql(), OnboardingReadService::summary, parameters),
                query, total);
    }

    public OnboardingRunDetail run(UUID runId) {
        var run = db.one(COLUMNS + " where r.onboarding_run_id = ?", OnboardingReadService::summary, runId)
                .orElseThrow(() -> new ReadNotFoundException("Onboarding run does not exist: " + runId));
        Db.requireInScope(run.stateCode(), "Onboarding run does not exist: " + runId);
        var executions = db.list("select * from onboarding_stage_execution where onboarding_run_id = ?"
                + " order by started_at, attempt", r -> {
                    var started = Db.time(r, "started_at");
                    var completed = Db.time(r, "completed_at");
                    return new StageExecutionView(Db.uuid(r, "onboarding_stage_execution_id"), r.getString("stage"),
                            r.getString("status"), r.getInt("attempt"), started, completed, Db.millis(started, completed),
                            Db.integer(r, "input_count"), Db.integer(r, "output_count"), Db.uuid(r, "ingestion_batch_id"),
                            Db.uuid(r, "matching_run_id"), r.getString("error_message"));
                }, runId);
        List<StageTimelineEntry> timeline = new ArrayList<>();
        for (OnboardingStage stage : OnboardingStage.values()) {
            var attempts = executions.stream().filter(e -> e.stage().equals(stage.name())).toList();
            var latest = attempts.isEmpty() ? null : attempts.getLast();
            timeline.add(new StageTimelineEntry(stage.name(), stage.ordinal() + 1,
                    latest == null ? "PENDING" : latest.status(), attempts.size(), latest));
        }
        boolean stageRunning = executions.stream().anyMatch(e -> e.status().equals("RUNNING"));
        var actions = new RunActions(run.status().equals("RUNNING") && !stageRunning, run.status().equals("FAILED"));
        var processingRejections = executions.stream()
                .filter(e -> e.stage().equals("INGESTED") && e.status().equals("COMPLETED")
                        && e.ingestionBatchId() != null)
                .map(StageExecutionView::ingestionBatchId)
                .reduce((first, second) -> second)
                .map(batch -> new ProcessingRejections(batch, (int) db.count(
                        "select count(*) from canonical_processing_error where batch_id = ?", batch)))
                .orElse(null);
        return new OnboardingRunDetail(run, timeline, executions, actions, processingRejections);
    }

    private static OnboardingRunSummary summary(ResultSet r) throws SQLException {
        var started = Db.time(r, "started_at");
        var completed = Db.time(r, "completed_at");
        return new OnboardingRunSummary(Db.uuid(r, "onboarding_run_id"), Db.uuid(r, "state_pack_id"),
                r.getString("pack_id"), r.getString("state_code"), r.getString("source_system"),
                r.getString("state_pack_version"), r.getString("status"), r.getString("current_stage"),
                r.getString("processing_mode"), started, completed, Db.millis(started, completed),
                r.getString("failure_message"), r.getInt("executions"), r.getInt("completed_stages"),
                OnboardingStage.values().length);
    }
}
