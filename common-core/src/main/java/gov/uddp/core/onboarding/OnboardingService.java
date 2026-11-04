package gov.uddp.core.onboarding;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Persists synchronous orchestration state. Transport/matching adapters are intentionally replaceable boundaries. */
@Service
public class OnboardingService {
    private static final String UNIQUE_VIOLATION = "23505";

    private final PostgresConnectionFactory connections;
    private final StatePackLifecycleService lifecycle;
    private final StatePackLoader packs;
    private final HopRunner hop;
    private final CanonicalBatchProcessor canonical;
    private final MatchingRunner matching;
    private gov.uddp.core.events.PlatformEvents platformEvents;
    private final GoldenResidentService golden;
    private DatabaseSources databases;
    private DeliveryChangeService changes;
    private ReleaseCheckpoint release;
    private gov.uddp.core.canonical.ProcessingProgress processing;
    private LandingFolder landing;
    private gov.uddp.core.connector.ConnectorService connectors;

    @Autowired
    public OnboardingService(PostgresConnectionFactory connections, StatePackLifecycleService lifecycle,
                             StatePackLoader packs, HopRunner hop, CanonicalBatchProcessor canonical,
                             MatchingRunner matching, GoldenResidentService golden) {
        this.connections = connections;
        this.lifecycle = lifecycle;
        this.packs = packs;
        this.hop = hop;
        this.canonical = canonical;
        this.matching = matching;
        this.golden = golden;
    }

    /** Compatibility constructor for lifecycle-only tests; executable stages require the full constructor. */
    public OnboardingService(PostgresConnectionFactory connections, StatePackLifecycleService lifecycle,
                             StatePackLoader packs) {
        this(connections, lifecycle, packs, null, null, null, null);
    }

    /** MSA Phase 2: with events enabled, a completed matching stage publishes its run's events. */
    @Autowired(required = false)
    public void setPlatformEvents(gov.uddp.core.events.PlatformEvents platformEvents) {
        this.platformEvents = platformEvents;
    }

    /** Enables the SOURCE_TESTED check of database-table sources (ADR-0016). */
    @Autowired(required = false)
    public void setDatabaseSources(DatabaseSources databases) {
        this.databases = databases;
    }

    /** Enables delivery change detection (Track C1); without it MAPPED compares nothing. */
    @Autowired(required = false)
    public void setDeliveryChanges(DeliveryChangeService changes) {
        this.changes = changes;
    }

    /** Enables landing-folder sources: a file source whose location is a directory ingests the files waiting in it. */
    @Autowired(required = false)
    public void setLandingFolder(LandingFolder landing) {
        this.landing = landing;
    }

    /** Enables per-chunk progress and the rejected-row download for the MAPPED stage. */
    @Autowired(required = false)
    public void setProcessingProgress(gov.uddp.core.canonical.ProcessingProgress processing) {
        this.processing = processing;
    }

    /** Enables connector sources (Track C5): excel, jdbc, sftp and rest deliveries staged for the file pipeline. */
    @Autowired(required = false)
    public void setConnectors(gov.uddp.core.connector.ConnectorService connectors) {
        this.connectors = connectors;
    }

    /** Enables the quality-gate release checkpoint before the Golden build (Track C3). */
    @Autowired(required = false)
    public void setReleaseCheckpoint(ReleaseCheckpoint release) {
        this.release = release;
    }

    public UUID start(String packId, String version, String sourceSystem) {
        return start(packId, version, sourceSystem, ProcessingMode.FULL);
    }

    /**
     * Creates a run. {@code FULL} matches every latest canonical Resident and re-evaluates every Golden group (M10
     * behaviour); {@code INCREMENTAL} matches only new or changed records against the existing ones and joins them
     * to the current Golden set (ADR-0004).
     */
    public UUID start(String packId, String version, String sourceSystem, ProcessingMode processingMode) {
        var pack = packs.find(packId).orElseThrow(() -> new OnboardingNotFoundException("Unknown State Pack: " + packId));
        UUID statePackId = lifecycle.active(packId, version);
        if (!pack.sources().stream().anyMatch(source -> source.sourceId().equals(sourceSystem))) {
            throw new IllegalArgumentException("Unknown State Pack source: " + sourceSystem);
        }
        UUID run = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into onboarding_run(onboarding_run_id,state_pack_id,state_code,"
                        + "source_system,state_pack_version,status,current_stage,processing_mode,started_by)"
                        + " values(?,?,?,?,?,'RUNNING',?,?,?)")) {
            s.setObject(1, run);
            s.setObject(2, statePackId);
            s.setString(3, pack.manifest().stateCode());
            s.setString(4, sourceSystem);
            s.setString(5, version);
            s.setString(6, OnboardingStage.PACK_VALIDATED.name());
            s.setString(7, processingMode.name());
            s.setString(8, gov.uddp.core.security.CurrentUser.username());
            s.executeUpdate();
            return run;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create onboarding run", e);
        }
    }

    public void executeNext(UUID run) {
        Run row = read(run);
        OnboardingStage stage = OnboardingStage.valueOf(row.stage());
        if (!row.status().equals("RUNNING")) {
            throw new OnboardingConflictException("Onboarding run is not runnable: " + row.status());
        }
        if (stage == OnboardingStage.GOLDEN_BUILT && release != null && release.pauses(run)) {
            return; // Waiting for a person to release the run; approving it makes it RUNNING again.
        }
        int attempt = begin(run, stage);
        try {
            StageResult result = execute(row, stage);
            complete(run, stage, attempt, result);
        } catch (Exception e) {
            fail(run, stage, attempt, message(e));
            throw new OnboardingStageFailedException(run, stage, message(e), e);
        }
    }

    public void resume(UUID run) {
        Run row = read(run);
        if (!"FAILED".equals(row.status())) {
            throw new OnboardingConflictException("Only a failed onboarding run can be resumed");
        }
        // Only one caller can move the run from FAILED back to RUNNING; a concurrent resume finds no FAILED row.
        int claimed;
        try (var c = connections.open();
                var s = c.prepareStatement("update onboarding_run set status='RUNNING',failure_message=null"
                        + " where onboarding_run_id=? and status='FAILED'")) {
            s.setObject(1, run);
            claimed = s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resume onboarding run", e);
        }
        if (claimed == 0) {
            throw new OnboardingConflictException("Only a failed onboarding run can be resumed");
        }
        executeNext(run);
    }

    /**
     * Executes the named stage, which must be the run's current stage: stages run strictly in pipeline order and a
     * request for any other stage (for example MATCHED before MAPPED) is rejected without executing anything.
     */
    public void executeStage(UUID run, OnboardingStage stage) {
        Run row = read(run);
        if (!row.status().equals("RUNNING")) {
            throw new OnboardingConflictException("Onboarding run is not runnable: " + row.status());
        }
        if (!row.stage().equals(stage.name())) {
            throw new OnboardingConflictException("Invalid stage order: " + stage + " requested but the next stage is "
                    + row.stage());
        }
        executeNext(run);
    }

    /** Executes the remaining stages in order until the run is COMPLETED; a failing stage stops the run as FAILED. */
    public void runToCompletion(UUID run) {
        while (read(run).status().equals("RUNNING")) {
            executeNext(run);
        }
    }

    /**
     * Marks stages that have been RUNNING since before {@code cutoff} as FAILED, and their runs with them, so the run
     * can be resumed (a crashed process would otherwise block it for good). Returns the affected runs.
     */
    public List<UUID> failStuckStages(java.time.Instant cutoff, java.time.Instant now) {
        List<UUID> runs = new ArrayList<>();
        String error = "Stage timed out: no result after the configured stage timeout";
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("update onboarding_stage_execution set status='FAILED',completed_at=?,"
                    + "error_message=? where status='RUNNING' and started_at < ? returning onboarding_run_id")) {
                s.setObject(1, java.sql.Timestamp.from(now));
                s.setString(2, error);
                s.setObject(3, java.sql.Timestamp.from(cutoff));
                var r = s.executeQuery();
                while (r.next()) {
                    runs.add(r.getObject(1, UUID.class));
                }
            }
            for (UUID run : runs) {
                try (var s = c.prepareStatement("update onboarding_run set status='FAILED',failure_message=?"
                        + " where onboarding_run_id=? and status='RUNNING'")) {
                    s.setString(1, error);
                    s.setObject(2, run);
                    s.executeUpdate();
                }
            }
            c.commit();
            return runs;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot recover stuck onboarding stages", e);
        }
    }

    /** All runs for the State Pack's state, newest first; {@code state} is a pack ID or state code. */
    public List<Map<String, Object>> history(String state) {
        var pack = packs.find(state).orElseThrow(() -> new OnboardingNotFoundException("Unknown State Pack: " + state));
        List<Map<String, Object>> runs = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select onboarding_run_id,state_pack_id,state_code,source_system,"
                        + "state_pack_version,status,current_stage,started_at,completed_at,failure_message,processing_mode"
                        + " from onboarding_run where state_code=? order by created_at desc,started_at desc")) {
            s.setString(1, pack.manifest().stateCode());
            var r = s.executeQuery();
            while (r.next()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("runId", r.getObject(1));
                x.put("statePackId", r.getObject(2));
                x.put("stateCode", r.getString(3));
                x.put("sourceSystem", r.getString(4));
                x.put("statePackVersion", r.getString(5));
                x.put("status", r.getString(6));
                x.put("currentStage", r.getString(7));
                x.put("startedAt", r.getObject(8));
                x.put("completedAt", r.getObject(9));
                x.put("failureMessage", r.getString(10));
                x.put("processingMode", r.getString(11));
                runs.add(x);
            }
            runs.forEach(x -> x.put("deliveryChanges", deliveryChanges((UUID) x.get("runId"))));
            return runs;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read onboarding history", e);
        }
    }

    public Map<String, Object> detail(UUID run) {
        Run row = read(run);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run);
        out.put("statePackId", row.statePackId());
        out.put("stateCode", row.stateCode());
        out.put("sourceSystem", row.source());
        out.put("statePackVersion", row.version());
        out.put("status", row.status());
        out.put("currentStage", row.stage());
        out.put("processingMode", row.processingMode());
        out.put("failureMessage", row.failure());
        out.put("stages", stages(run));
        out.put("processing", processing(run));
        out.put("deliveryChanges", deliveryChanges(run));
        return out;
    }

    /**
     * Progress of the run's canonical processing (MAPPED): rows done of the delivery's total, per-chunk counts and
     * speed, and the rejected-row count. Null before the delivery has been ingested.
     */
    private Map<String, Object> processing(UUID run) {
        if (processing == null) {
            return null;
        }
        UUID batch = ingestedBatch(run);
        if (batch == null) {
            return null;
        }
        var chunks = processing.chunks(batch);
        var done = chunks.stream().map(gov.uddp.core.canonical.ProcessingProgress.Chunk::counts)
                .reduce(gov.uddp.core.canonical.ProcessingProgress.Counts.NONE,
                        gov.uddp.core.canonical.ProcessingProgress.Counts::plus);
        double seconds = chunks.stream().mapToDouble(gov.uddp.core.canonical.ProcessingProgress.Chunk::seconds).sum();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var chunk : chunks) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("chunk", chunk.number());
            x.put("rows", chunk.counts().read());
            x.put("mapped", chunk.counts().mapped());
            x.put("skipped", chunk.counts().skipped());
            x.put("rejected", chunk.counts().failed());
            x.put("held", chunk.counts().held());
            x.put("seconds", Math.round(chunk.seconds() * 100) / 100.0);
            x.put("rowsPerSecond", Math.round(chunk.counts().read() / chunk.seconds()));
            rows.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batch);
        out.put("totalRows", count("select coalesce(record_count,0) from ingestion_batch where batch_id=?", batch));
        out.put("rowsProcessed", done.read());
        out.put("rowsMapped", done.mapped());
        out.put("rowsSkipped", done.skipped());
        out.put("rowsPerSecond", seconds == 0 ? 0 : Math.round(done.read() / seconds));
        out.put("rejectedRows", processing.rejectedCount(batch));
        out.put("chunks", rows);
        return out;
    }

    /** The batch the run ingested, or null before INGESTED has completed. */
    private UUID ingestedBatch(UUID run) {
        try (var c = connections.open();
                var s = c.prepareStatement("select ingestion_batch_id from onboarding_stage_execution"
                        + " where onboarding_run_id=? and stage='INGESTED' and status='COMPLETED'"
                        + " order by attempt desc limit 1")) {
            s.setObject(1, run);
            var r = s.executeQuery();
            return r.next() ? r.getObject(1, UUID.class) : null;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the run's ingestion batch", e);
        }
    }

    /**
     * Writes the rows the run's MAPPED stage rejected as CSV (record ID, reason, original line, the line number in the
     * delivered file when it was stored). It streams, so a large
     * delivery is never held in memory.
     */
    public void writeRejectedRows(UUID run, java.io.Writer out) {
        read(run);
        UUID batch = ingestedBatch(run);
        try {
            var printer = new org.apache.commons.csv.CSVPrinter(out, org.apache.commons.csv.CSVFormat.RFC4180);
            printer.printRecord("rawRecordId", "reason", "rejectedAt", "originalRow", "sourceLine");
            if (processing != null && batch != null) {
                processing.forEachRejected(batch, r -> {
                    try {
                        printer.printRecord(r.rawRecordId(), r.reason(), r.occurredAt(), originalRow(r.rawPayload()),
                                r.sourceLine() == null ? "" : r.sourceLine());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
            printer.flush();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper PAYLOADS = new com.fasterxml.jackson.databind.ObjectMapper();

    /** The source line of a CSV-landed row; any other payload (a database row) as it is. */
    private static String originalRow(String payload) {
        if (payload == null) {
            return "";
        }
        try {
            var line = PAYLOADS.readTree(payload).path("rawLine");
            return line.isTextual() ? line.asText() : payload;
        } catch (Exception e) {
            return payload;
        }
    }

    /** New / changed / unchanged / removed counts of the run's delivery, or null before MAPPED (or without C1). */
    private Map<String, Object> deliveryChanges(UUID run) {
        return changes == null ? null : changes.countsForRun(run).map(DeliveryChangeService.Counts::asMap).orElse(null);
    }

    private StageResult execute(Run row, OnboardingStage stage) {
        var pack = packs.find(row.stateCode())
                .orElseThrow(() -> new IllegalStateException("State Pack no longer available"));
        var source = pack.sources().stream()
                .filter(s -> s.sourceId().equals(row.source()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown State Pack source: " + row.source()));
        return switch (stage) {
            case PACK_VALIDATED -> {
                if (!pack.manifest().version().equals(row.version())) {
                    throw new IllegalStateException("Configured State Pack version differs from onboarding run");
                }
                yield StageResult.empty();
            }
            case SOURCE_TESTED -> {
                if (connectors != null && connectors.find(row.stateCode(), source.sourceId()).isPresent()) {
                    connectors.test(row.stateCode(), source.sourceId());
                    yield StageResult.empty();
                }
                if (source.database()) {
                    if (databases == null) {
                        throw new IllegalStateException("Database sources are not available in this process");
                    }
                    databases.test(source.connectionRef(), source.table(), source.query());
                    yield StageResult.empty();
                }
                if (landing != null && landing.isLandingFolder(source)) {
                    if (landing.waiting(source).isEmpty()) {
                        throw new IllegalStateException("No file is waiting in the landing folder " + source.location());
                    }
                    yield StageResult.empty();
                }
                if (source.location() == null
                        || !Files.isReadable(Path.of("..").resolve(source.location()).normalize())) {
                    throw new IllegalStateException("Configured source is not readable: " + source.location());
                }
                yield StageResult.empty();
            }
            case INGESTED -> ingest(row, source);
            case MAPPED -> map(row);
            case DQ_CHECKED -> dq(row);
            case MATCHED -> match(row);
            case GOLDEN_BUILT -> golden(row);
            case PUBLISHED -> StageResult.empty();
        };
    }

    private StageResult ingest(Run row, SourceDefinition source) {
        UUID batch = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                        + "source_schema_version,state_pack_version,pipeline_template,status)"
                        + " values(?,?,?,?,?,?,?,'STARTED')")) {
            s.setObject(1, batch);
            s.setString(2, row.stateCode());
            s.setString(3, row.source());
            s.setString(4, source.sourceType());
            s.setString(5, source.sourceSchemaVersion());
            s.setString(6, row.version());
            s.setString(7, source.hopTemplate());
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create ingestion batch", e);
        }
        try {
            var staged = connectors == null ? java.util.Optional.<gov.uddp.core.connector.ConnectorService.Staged>empty()
                    : connectors.stage(row.stateCode(), source);
            if (staged.isEmpty() && landing != null && landing.isLandingFolder(source)) {
                var intake = landing.next(source).orElseThrow(
                        () -> new IllegalStateException("No file is waiting in the landing folder " + source.location()));
                staged = java.util.Optional.of(new gov.uddp.core.connector.ConnectorService.Staged(intake.source(),
                        intake.completed()));
            }
            var result = hop.ingest(staged.map(gov.uddp.core.connector.ConnectorService.Staged::source).orElse(source),
                    row.stateCode(), row.version(), batch);
            if (!result.succeeded()) {
                throw new IllegalStateException("Generic Hop exited " + result.exitCode() + ": " + result.output());
            }
            staged.ifPresent(delivery -> delivery.completed().run());
            int count = count("select count(*) from raw_record where batch_id=?", batch);
            try (var c = connections.open();
                    var s = c.prepareStatement("update ingestion_batch set status='COMPLETED',completed_at=now(),"
                            + "record_count=? where batch_id=?")) {
                s.setInt(1, count);
                s.setObject(2, batch);
                s.executeUpdate();
            }
            return new StageResult(count, count, batch, null);
        } catch (Exception e) {
            try (var c = connections.open();
                    var s = c.prepareStatement("update ingestion_batch set status='FAILED',completed_at=now(),"
                            + "error_message=? where batch_id=?")) {
                s.setString(1, message(e));
                s.setObject(2, batch);
                s.executeUpdate();
            } catch (Exception ignored) {
                // The original failure is the one worth reporting.
            }
            throw e instanceof RuntimeException r ? r : new IllegalStateException("Cannot complete ingestion batch", e);
        }
    }

    private StageResult map(Run row) {
        UUID batch = batch(row);
        if (changes != null) {
            changes.evaluate(batch, row.id());
        }
        var result = canonical.processBatch(batch);
        return new StageResult(result.recordsRead(), result.recordsMapped(), batch, null);
    }

    private StageResult dq(Run row) {
        UUID batch = batch(row);
        try (var c = connections.open();
                var s = c.prepareStatement("select records_evaluated,affected_record_count from dq_batch_evaluation"
                        + " where batch_id=? order by evaluated_at desc limit 1")) {
            s.setObject(1, batch);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new IllegalStateException("No persisted DQ evaluation for ingestion batch " + batch);
            }
            return new StageResult(r.getInt(1), r.getInt(2), batch, null);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read DQ evaluation", e);
        }
    }

    /** Input: the records the run considered (every latest canonical, or only new/changed ones when incremental). */
    private StageResult match(Run row) {
        UUID id = ProcessingMode.INCREMENTAL.name().equals(row.processingMode()) ? matching.runIncremental() : matching.run();
        if (platformEvents != null) {
            platformEvents.matchRunCompleted(id);
        }
        int input = count("select records_considered from matching_run where run_id=?", id);
        return new StageResult(input, count("select count(*) from resident_match_cluster_member where run_id=?", id), null, id);
    }

    private StageResult golden(Run row) {
        UUID id = matchingRun(row);
        var result = golden.build(id);
        return new StageResult(result.canonicalRecordsConsidered(), result.acceptedGroups(), null, id);
    }

    private UUID batch(Run row) {
        return id(row, "ingestion_batch_id", OnboardingStage.INGESTED);
    }

    private UUID matchingRun(Run row) {
        return id(row, "matching_run_id", OnboardingStage.MATCHED);
    }

    private UUID id(Run row, String column, OnboardingStage stage) {
        try (var c = connections.open();
                var s = c.prepareStatement("select " + column + " from onboarding_stage_execution"
                        + " where onboarding_run_id=? and stage=? and status='COMPLETED' order by attempt desc limit 1")) {
            s.setObject(1, row.id());
            s.setString(2, stage.name());
            var r = s.executeQuery();
            if (!r.next() || r.getObject(1) == null) {
                throw new IllegalStateException("No successful " + stage + " stage for onboarding run");
            }
            return r.getObject(1, UUID.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read onboarding execution identifier", e);
        }
    }

    private int count(String sql, UUID id) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            if (id != null) {
                s.setObject(1, id);
            }
            var r = s.executeQuery();
            r.next();
            return r.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot count onboarding records", e);
        }
    }

    /**
     * Claims the run's current stage by inserting its next attempt. The insert happens only while the run is RUNNING
     * at that stage, and V9's one-RUNNING-execution-per-run index rejects a second claim, so two callers can never
     * execute the same stage concurrently or re-execute a stage another caller has just completed.
     */
    private int begin(UUID run, OnboardingStage stage) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into onboarding_stage_execution(onboarding_run_id,stage,status,attempt)"
                        + " select r.onboarding_run_id,?,'RUNNING',coalesce((select max(e.attempt)"
                        + " from onboarding_stage_execution e where e.onboarding_run_id=r.onboarding_run_id and e.stage=?),0)+1"
                        + " from onboarding_run r where r.onboarding_run_id=? and r.status='RUNNING' and r.current_stage=?"
                        + " returning attempt")) {
            s.setString(1, stage.name());
            s.setString(2, stage.name());
            s.setObject(3, run);
            s.setString(4, stage.name());
            var r = s.executeQuery();
            if (!r.next()) {
                throw new OnboardingConflictException("Onboarding run is no longer runnable at stage " + stage);
            }
            return r.getInt(1);
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new OnboardingConflictException("A stage is already running for this onboarding run", e);
            }
            throw new IllegalStateException("Cannot start onboarding stage", e);
        }
    }

    private void complete(UUID run, OnboardingStage stage, int attempt, StageResult result) {
        OnboardingStage next = stage.next();
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("update onboarding_stage_execution set status='COMPLETED',completed_at=now(),"
                    + "input_count=?,output_count=?,ingestion_batch_id=?,matching_run_id=?"
                    + " where onboarding_run_id=? and stage=? and attempt=?")) {
                s.setInt(1, result.input());
                s.setInt(2, result.output());
                s.setObject(3, result.batch());
                s.setObject(4, result.match());
                s.setObject(5, run);
                s.setString(6, stage.name());
                s.setInt(7, attempt);
                s.executeUpdate();
            }
            // The run is COMPLETED only once its final stage (PUBLISHED) has completed.
            String status = next == null ? "COMPLETED" : "RUNNING";
            try (var s = c.prepareStatement("update onboarding_run set status=?,current_stage=?,"
                    + "completed_at=case when ?='COMPLETED' then now() else null end where onboarding_run_id=?")) {
                s.setString(1, status);
                s.setString(2, next == null ? stage.name() : next.name());
                s.setString(3, status);
                s.setObject(4, run);
                s.executeUpdate();
            }
            c.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot complete onboarding stage", e);
        }
    }

    private void fail(UUID run, OnboardingStage stage, int attempt, String error) {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("update onboarding_stage_execution set status='FAILED',completed_at=now(),"
                    + "error_message=? where onboarding_run_id=? and stage=? and attempt=?")) {
                s.setString(1, error);
                s.setObject(2, run);
                s.setString(3, stage.name());
                s.setInt(4, attempt);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("update onboarding_run set status='FAILED',failure_message=?"
                    + " where onboarding_run_id=?")) {
                s.setString(1, error);
                s.setObject(2, run);
                s.executeUpdate();
            }
            c.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record onboarding failure", e);
        }
    }

    private Run read(UUID run) {
        try (var c = connections.open();
                var s = c.prepareStatement("select state_pack_id,state_code,source_system,state_pack_version,status,"
                        + "current_stage,failure_message,processing_mode from onboarding_run where onboarding_run_id=?")) {
            s.setObject(1, run);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new OnboardingNotFoundException("Unknown onboarding run: " + run);
            }
            return new Run(run, r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                    r.getString(5), r.getString(6), r.getString(7), r.getString(8));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> stages(UUID run) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select stage,status,attempt,started_at,completed_at,input_count,output_count,"
                        + "ingestion_batch_id,matching_run_id,error_message,onboarding_stage_execution_id"
                        + " from onboarding_stage_execution where onboarding_run_id=? order by started_at,attempt")) {
            s.setObject(1, run);
            var r = s.executeQuery();
            while (r.next()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("executionId", r.getObject(11));
                x.put("stage", r.getString(1));
                x.put("status", r.getString(2));
                x.put("attempt", r.getInt(3));
                x.put("startedAt", r.getObject(4));
                x.put("completedAt", r.getObject(5));
                x.put("inputCount", r.getObject(6));
                x.put("outputCount", r.getObject(7));
                x.put("ingestionBatchId", r.getObject(8));
                x.put("matchingRunId", r.getObject(9));
                x.put("errorMessage", r.getString(10));
                rows.add(x);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The failure text that is stored and returned; secrets are always removed (see SecretRedactor). */
    private String message(Exception e) {
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return SecretRedactor.redact(text, connections.secrets());
    }

    private record Run(UUID id, UUID statePackId, String stateCode, String source, String version, String status,
                       String stage, String failure, String processingMode) {
    }

    /** How MATCHED and GOLDEN_BUILT treat existing data. */
    public enum ProcessingMode { FULL, INCREMENTAL }

    private record StageResult(int input, int output, UUID batch, UUID match) {
        static StageResult empty() {
            return new StageResult(0, 0, null, null);
        }
    }
}
