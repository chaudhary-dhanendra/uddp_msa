package gov.uddp.core.geography;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.monitoring.MonitoringService.Metric;
import gov.uddp.core.monitoring.PlatformMetricsSource;
import gov.uddp.core.onboarding.PlatformScheduledJob;
import gov.uddp.core.onboarding.SchedulerService;
import gov.uddp.core.read.Db;
import gov.uddp.core.read.Page;
import gov.uddp.core.read.PageQuery;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.AuditService.AuditEvent;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * LGD master sync operations (Track F3). A sync fetches a snapshot through the {@link LgdSourceClient} and imports it;
 * at most one runs at a time (a second start is refused), a failure keeps the last known good master, and every run
 * is recorded with its actor (SYSTEM when scheduled) and audited. The schedule is evaluated by the C2 scheduler loop.
 * The master's age and consecutive failures are platform metrics, so C6 alert rules can warn about a stale master.
 */
@Service
public class LgdSyncService implements PlatformScheduledJob, PlatformMetricsSource {
    public static final String SYSTEM = SchedulerService.SYSTEM;

    /** A second sync was asked for while one is running. */
    public static class SyncAlreadyRunningException extends IllegalStateException {
        public SyncAlreadyRunningException() {
            super("An LGD master sync is already running");
        }
    }

    public record SyncRun(UUID runId, String triggerKind, String actor, String status, UUID snapshotId,
                          int changeCount, String error, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                          String sourceLabel) {
    }

    public record SyncSchedule(String cron, String timezone, boolean enabled) {
    }

    public record LgdChange(long changeId, UUID snapshotId, String entityType, String code, String changeType,
                            String oldValue, String newValue, String effectiveFrom, OffsetDateTime createdAt) {
    }

    /** What the operations screen shows: the active snapshot, the last runs and whether the master is stale. */
    public record SyncStatus(String source, UUID activeSnapshotId, OffsetDateTime activatedAt,
                             OffsetDateTime lastSuccessAt, Double ageHours, boolean stale, int consecutiveFailures,
                             SyncRun lastRun, boolean running, SyncSchedule schedule, long entityCount) {
    }

    private final PostgresConnectionFactory connections;
    private final Db db;
    private final LgdSourceClient source;
    private final LgdImportService imports;
    private final AuditService audit;
    private final Clock clock;
    private final Executor executor;
    private final Duration staleAfter;
    private final Duration runTimeout;

    @Autowired
    public LgdSyncService(PostgresConnectionFactory connections, Db db, LgdSourceClient source,
                          LgdImportService imports, AuditService audit,
                          @Value("${uddp.geography.lgd.stale-after-hours:720}") long staleAfterHours,
                          @Value("${uddp.geography.lgd.run-timeout-minutes:120}") long runTimeoutMinutes) {
        this(connections, db, source, imports, audit, Clock.systemUTC(), Executors.newSingleThreadExecutor(),
                Duration.ofHours(staleAfterHours), Duration.ofMinutes(runTimeoutMinutes));
    }

    public LgdSyncService(PostgresConnectionFactory connections, Db db, LgdSourceClient source,
                          LgdImportService imports, AuditService audit, Clock clock, Executor executor,
                          Duration staleAfter, Duration runTimeout) {
        this.connections = connections;
        this.db = db;
        this.source = source;
        this.imports = imports;
        this.audit = audit;
        this.clock = clock;
        this.executor = executor;
        this.staleAfter = staleAfter;
        this.runTimeout = runTimeout;
    }

    @Override
    public String name() {
        return "lgd-master-sync";
    }

    // ---- running a sync --------------------------------------------------------------------------------------------

    /** Claims the single running slot and runs the sync on the executor; throws when a sync is already running. */
    public SyncRun start(String triggerKind, String actor) {
        UUID runId = claim(triggerKind, actor, null);
        executor.execute(() -> execute(runId, actor, this::fetch));
        return run(runId).orElseThrow();
    }

    /**
     * Claims the single running slot for an uploaded snapshot (trigger UPLOAD, the file name as source label) and
     * imports it on the executor; throws when a sync is already running. The upload is held in memory only.
     */
    public SyncRun startUpload(LgdSnapshotData snapshot, String actor) {
        UUID runId = claim("UPLOAD", actor, snapshot.sourceLabel());
        executor.execute(() -> execute(runId, actor, () -> snapshot));
        return run(runId).orElseThrow();
    }

    private LgdSnapshotData fetch() {
        return source.fetch().orElseThrow(() -> new IllegalStateException("No LGD source is configured"));
    }

    /** Claims the slot and runs the sync in the calling thread (the scheduler and tests). */
    public SyncRun runNow(String triggerKind, String actor) {
        UUID runId = claim(triggerKind, actor, null);
        execute(runId, actor, this::fetch);
        return run(runId).orElseThrow();
    }

    private UUID claim(String triggerKind, String actor, String sourceLabel) {
        abandonStuckRuns();
        UUID runId = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into lgd_sync_run(run_id,trigger_kind,actor,status,started_at,"
                        + "source_label) values(?,?,?,'RUNNING',?,?)")) {
            s.setObject(1, runId);
            s.setString(2, triggerKind);
            s.setString(3, actor);
            s.setTimestamp(4, Timestamp.from(clock.instant()));
            s.setString(5, sourceLabel);
            s.executeUpdate();
            return runId;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new SyncAlreadyRunningException();
            }
            throw new IllegalStateException("Cannot start the LGD master sync", e);
        }
    }

    private void execute(UUID runId, String actor, java.util.function.Supplier<LgdSnapshotData> snapshotSource) {
        String status;
        UUID snapshotId = null;
        int changes = 0;
        String error = null;
        try {
            var result = imports.importSnapshot(snapshotSource.get(), actor);
            snapshotId = result.snapshotId();
            changes = result.changeCount();
            status = switch (result.status()) {
                case "FAILED" -> "FAILED";
                case "UNCHANGED" -> "UNCHANGED";
                default -> "SUCCEEDED";
            };
            if (!result.errors().isEmpty()) {
                error = String.join("; ", result.errors().subList(0, Math.min(5, result.errors().size())));
            }
        } catch (RuntimeException e) {
            status = "FAILED";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        finish(runId, status, snapshotId, changes, error);
        audit.record(new AuditEvent(actor, "LGD_MASTER_SYNC", "LGD_SYNC_RUN", runId.toString(), null,
                "FAILED".equals(status) ? AuditService.FAILURE : AuditService.SUCCESS,
                AuditService.details("status", status, "changes", changes)));
    }

    private void finish(UUID runId, String status, UUID snapshotId, int changes, String error) {
        try (var c = connections.open();
                var s = c.prepareStatement("update lgd_sync_run set status=?,snapshot_id=?,change_count=?,error=?,"
                        + "finished_at=? where run_id=?")) {
            s.setString(1, status);
            s.setObject(2, snapshotId);
            s.setInt(3, changes);
            s.setString(4, error == null ? null : error.substring(0, Math.min(error.length(), 2000)));
            s.setTimestamp(5, Timestamp.from(clock.instant()));
            s.setObject(6, runId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record the LGD sync run", e);
        }
    }

    /** A run left RUNNING by a crashed process beyond the timeout is failed, so the next sync can start. */
    private void abandonStuckRuns() {
        try (var c = connections.open();
                var s = c.prepareStatement("update lgd_sync_run set status='FAILED',error='abandoned: no result within"
                        + " the run timeout',finished_at=? where status='RUNNING' and started_at < ?")) {
            s.setTimestamp(1, Timestamp.from(clock.instant()));
            s.setTimestamp(2, Timestamp.from(clock.instant().minus(runTimeout)));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot recover stuck LGD sync runs", e);
        }
    }

    // ---- schedule (driven by the C2 scheduler loop) ----------------------------------------------------------------

    public SyncSchedule saveSchedule(SyncSchedule schedule, String actor) {
        SchedulerService.parse(schedule.cron());
        ZoneId.of(schedule.timezone() == null ? "UTC" : schedule.timezone());
        try (var c = connections.open();
                var s = c.prepareStatement("insert into lgd_sync_schedule(singleton,cron_expression,timezone,enabled,"
                        + "last_evaluated_at,updated_by) values(true,?,?,?,?,?) on conflict (singleton) do update set"
                        + " cron_expression=excluded.cron_expression,timezone=excluded.timezone,enabled=excluded.enabled,"
                        + "last_evaluated_at=excluded.last_evaluated_at,updated_by=excluded.updated_by,updated_at=now()")) {
            s.setString(1, schedule.cron().strip());
            s.setString(2, schedule.timezone() == null ? "UTC" : schedule.timezone());
            s.setBoolean(3, schedule.enabled());
            s.setTimestamp(4, Timestamp.from(clock.instant()));
            s.setString(5, actor);
            s.executeUpdate();
            return schedule;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot store the LGD sync schedule", e);
        }
    }

    public Optional<SyncSchedule> schedule() {
        return db.one("select cron_expression,timezone,enabled from lgd_sync_schedule",
                r -> new SyncSchedule(r.getString(1), r.getString(2), r.getBoolean(3)));
    }

    /** Starts one scheduled sync when a fire time has passed (missed fire times run once); skips while one runs. */
    @Override
    public void tick(Instant ignored) {
        Instant now = clock.instant();
        var row = db.one("select cron_expression,timezone,enabled,last_evaluated_at from lgd_sync_schedule",
                r -> new Object[] {r.getString(1), r.getString(2), r.getBoolean(3),
                    r.getObject(4, OffsetDateTime.class)});
        if (row.isEmpty() || !(Boolean) row.get()[2]) {
            return;
        }
        var cron = SchedulerService.parse((String) row.get()[0]);
        ZoneId zone = ZoneId.of((String) row.get()[1]);
        var last = ((OffsetDateTime) row.get()[3]).toInstant().atZone(zone);
        var next = cron.next(last);
        if (next == null || next.toInstant().isAfter(now)) {
            return;
        }
        try (var c = connections.open();
                var s = c.prepareStatement("update lgd_sync_schedule set last_evaluated_at=?")) {
            s.setTimestamp(1, Timestamp.from(now));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot advance the LGD sync schedule", e);
        }
        try {
            start("SCHEDULED", SYSTEM);
        } catch (SyncAlreadyRunningException running) {
            audit.record(new AuditEvent(SYSTEM, "LGD_MASTER_SYNC_SKIPPED", "LGD_SYNC_RUN", null, null,
                    AuditService.SUCCESS, AuditService.details("reason", "a sync is already running")));
        }
    }

    // ---- history, change log, status and metrics -------------------------------------------------------------------

    public Optional<SyncRun> run(UUID runId) {
        return db.one(RUN + " where run_id=?", LgdSyncService::syncRun, runId);
    }

    public Page<SyncRun> runs(Integer page, Integer size) {
        PageQuery query = PageQuery.of(page, size, null, Map.of("startedAt", "started_at"), "startedAt,desc",
                "run_no desc");
        return Page.of(db.list(RUN + query.sql(), LgdSyncService::syncRun), query,
                db.count("select count(*) from lgd_sync_run"));
    }

    public Page<LgdChange> changes(UUID snapshotId, String entityType, String code, String changeType, Integer page,
                                   Integer size) {
        PageQuery query = PageQuery.of(page, size, null, Map.of("changeId", "change_id"), "changeId,desc", "change_id");
        String where = " where (cast(? as uuid) is null or snapshot_id=?) and (cast(? as text) is null or entity_type=?)"
                + " and (cast(? as text) is null or code=?) and (cast(? as text) is null or change_type=?)";
        String type = GeographyService.upper(entityType);
        String kind = GeographyService.upper(changeType);
        Object[] parameters = {snapshotId, snapshotId, type, type, code, code, kind, kind};
        return Page.of(db.list("select change_id,snapshot_id,entity_type,code,change_type,old_value,new_value,"
                        + "effective_from::text,created_at from lgd_change" + where + query.sql(),
                r -> new LgdChange(r.getLong(1), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8),
                        r.getObject(9, OffsetDateTime.class)), parameters),
                query, db.count("select count(*) from lgd_change" + where, parameters));
    }

    public SyncStatus status() {
        var active = db.one("select active_snapshot_id,activated_at from lgd_master_state",
                r -> new Object[] {r.getObject(1, UUID.class), r.getObject(2, OffsetDateTime.class)});
        OffsetDateTime lastSuccess = lastSuccess().orElse(null);
        Double age = ageHours();
        Optional<SyncRun> last = db.one(RUN + " order by run_no desc limit 1", LgdSyncService::syncRun);
        return new SyncStatus(source.describe(), active.map(a -> (UUID) a[0]).orElse(null),
                active.map(a -> (OffsetDateTime) a[1]).orElse(null), lastSuccess, age,
                age != null && age > staleAfter.toMinutes() / 60.0, consecutiveFailures(), last.orElse(null),
                db.count("select count(*) from lgd_sync_run where status='RUNNING'") > 0, schedule().orElse(null),
                db.count("select count(*) from lgd_entity"));
    }

    @Override
    public Map<Metric, Double> platformMetrics() {
        Map<Metric, Double> values = new LinkedHashMap<>();
        Double age = ageHours();
        if (age != null) {
            values.put(Metric.LGD_MASTER_AGE_HOURS, age);
        }
        if (db.count("select count(*) from lgd_sync_run") > 0) {
            values.put(Metric.LGD_SYNC_FAILURES, (double) consecutiveFailures());
        }
        return values;
    }

    private Optional<OffsetDateTime> lastSuccess() {
        return db.one("select max(coalesce(finished_at, started_at)) from lgd_sync_run"
                + " where status in ('SUCCEEDED','UNCHANGED') having count(*) > 0", r -> r.getObject(1, OffsetDateTime.class))
                .or(() -> db.one("select max(created_at) from lgd_snapshot where status in ('ACTIVE','UNCHANGED')"
                        + " having count(*) > 0", r -> r.getObject(1, OffsetDateTime.class)));
    }

    /** Hours since the last successful sync (or, when none ever succeeded, since the first attempt); null when idle. */
    private Double ageHours() {
        Optional<OffsetDateTime> since = lastSuccess()
                .or(() -> db.one("select min(started_at) from lgd_sync_run having count(*) > 0",
                        r -> r.getObject(1, OffsetDateTime.class)));
        return since.map(t -> Duration.between(t.toInstant(), clock.instant()).toMinutes() / 60.0).orElse(null);
    }

    private int consecutiveFailures() {
        int failures = 0;
        for (String status : db.list("select status from lgd_sync_run where status <> 'RUNNING'"
                + " order by run_no desc limit 100", r -> r.getString(1))) {
            if (!"FAILED".equals(status)) {
                break;
            }
            failures++;
        }
        return failures;
    }

    private static final String RUN = "select run_id,trigger_kind,actor,status,snapshot_id,change_count,error,started_at,"
            + "finished_at,source_label from lgd_sync_run";

    private static SyncRun syncRun(java.sql.ResultSet r) throws SQLException {
        return new SyncRun(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                r.getObject(5, UUID.class), r.getInt(6), r.getString(7), r.getObject(8, OffsetDateTime.class),
                r.getObject(9, OffsetDateTime.class), r.getString(10));
    }
}
