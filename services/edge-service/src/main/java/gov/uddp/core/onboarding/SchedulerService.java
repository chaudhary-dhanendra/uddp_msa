package gov.uddp.core.onboarding;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.AuditService.AuditEvent;
import gov.uddp.core.statepack.StatePackLoader;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * Per-source schedules (Track C2). {@link #tick()} turns due fire times into queued triggers, starts at most one
 * onboarding run per source at a time (the rest wait in the queue), applies the catch-up policy to fire times missed
 * while the backend was down, and fails stages stuck RUNNING beyond the timeout so the run can be resumed. Every
 * scheduled start is audited as actor SYSTEM.
 */
@Service
public class SchedulerService {
    public static final String SYSTEM = "SYSTEM";
    private static final int MAX_FIRE_TIMES = 1000;

    public enum CatchUp { RUN_ONCE, SKIP }

    public record Schedule(String stateCode, String sourceSystem, String cron, String timezone, boolean enabled,
                           CatchUp catchUp) {
    }

    private final PostgresConnectionFactory connections;
    private final OnboardingService onboarding;
    private final StatePackLoader packs;
    private final AuditService audit;
    private final Clock clock;
    private final Executor executor;
    private final Duration grace;
    private final Duration stageTimeout;
    private LandingFolder landing;

    @Autowired
    public SchedulerService(PostgresConnectionFactory connections, OnboardingService onboarding, StatePackLoader packs,
                            AuditService audit,
                            @Value("${uddp.scheduler.grace-seconds:120}") long graceSeconds,
                            @Value("${uddp.scheduler.stage-timeout-minutes:120}") long stageTimeoutMinutes) {
        this(connections, onboarding, packs, audit, Clock.systemUTC(), Executors.newCachedThreadPool(),
                Duration.ofSeconds(graceSeconds), Duration.ofMinutes(stageTimeoutMinutes));
    }

    public SchedulerService(PostgresConnectionFactory connections, OnboardingService onboarding, StatePackLoader packs,
                            AuditService audit, Clock clock, Executor executor, Duration grace, Duration stageTimeout) {
        this.connections = connections;
        this.onboarding = onboarding;
        this.packs = packs;
        this.audit = audit;
        this.clock = clock;
        this.executor = executor;
        this.grace = grace;
        this.stageTimeout = stageTimeout;
    }

    /** Enables landing-folder sources: a scheduled fire with no file waiting is skipped instead of failing a run. */
    @Autowired(required = false)
    public void setLandingFolder(LandingFolder landing) {
        this.landing = landing;
    }

    // ---- configuration ---------------------------------------------------------------------------------------------

    public Schedule save(Schedule schedule) {
        validate(schedule);
        try (var c = connections.open();
                var s = c.prepareStatement("insert into source_schedule(state_code,source_system,cron_expression,"
                        + "timezone,enabled,catch_up_policy,last_evaluated_at) values(?,?,?,?,?,?,?)"
                        + " on conflict (state_code,source_system) do update set cron_expression=excluded.cron_expression,"
                        + "timezone=excluded.timezone,enabled=excluded.enabled,catch_up_policy=excluded.catch_up_policy,"
                        + "last_evaluated_at=case when source_schedule.enabled then source_schedule.last_evaluated_at"
                        + " else excluded.last_evaluated_at end,updated_at=now()")) {
            s.setString(1, schedule.stateCode());
            s.setString(2, schedule.sourceSystem());
            s.setString(3, schedule.cron());
            s.setString(4, schedule.timezone());
            s.setBoolean(5, schedule.enabled());
            s.setString(6, schedule.catchUp().name());
            s.setTimestamp(7, Timestamp.from(clock.instant()));
            s.executeUpdate();
            return schedule;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the schedule", e);
        }
    }

    public java.util.Optional<Schedule> find(String stateCode, String sourceSystem) {
        return schedules("where state_code=? and source_system=?", stateCode, sourceSystem).stream().findFirst();
    }

    public List<Map<String, Object>> triggers(String stateCode, String sourceSystem) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select trigger_id,due_at,kind,status,onboarding_run_id,note,started_at"
                        + " from schedule_trigger where state_code=? and source_system=? order by due_at desc limit 50")) {
            s.setString(1, stateCode);
            s.setString(2, sourceSystem);
            var r = s.executeQuery();
            while (r.next()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("triggerId", r.getObject(1));
                x.put("dueAt", r.getObject(2));
                x.put("kind", r.getString(3));
                x.put("status", r.getString(4));
                x.put("onboardingRunId", r.getObject(5));
                x.put("note", r.getString(6));
                x.put("startedAt", r.getObject(7));
                rows.add(x);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the schedule triggers", e);
        }
    }

    private static void validate(Schedule schedule) {
        parse(schedule.cron());
        ZoneId.of(schedule.timezone());
    }

    /** Accepts Spring's six-field cron, or the usual five-field form (seconds are then 0). */
    public static CronExpression parse(String cron) {
        String text = cron == null ? "" : cron.trim();
        if (text.split("\\s+").length == 5) {
            text = "0 " + text;
        }
        return CronExpression.parse(text);
    }

    // ---- the scheduler loop ----------------------------------------------------------------------------------------

    public void tick() {
        Instant now = clock.instant();
        recoverStuckStages(now);
        for (Schedule schedule : schedules("where enabled", null, null)) {
            enqueueDue(schedule, now);
        }
        dispatch(now);
    }

    private void recoverStuckStages(Instant now) {
        for (UUID run : onboarding.failStuckStages(now.minus(stageTimeout), now)) {
            Map<String, Object> detail = onboarding.detail(run);
            audit.record(new AuditEvent(SYSTEM, "ONBOARDING_STAGE_TIMEOUT", "ONBOARDING_RUN", run.toString(),
                    (String) detail.get("stateCode"), AuditService.FAILURE,
                    AuditService.details("sourceSystem", detail.get("sourceSystem"), "resumable", true)));
        }
    }

    private void enqueueDue(Schedule schedule, Instant now) {
        Instant from = lastEvaluated(schedule);
        ZoneId zone = ZoneId.of(schedule.timezone());
        CronExpression cron = parse(schedule.cron());
        List<Instant> onTime = new ArrayList<>();
        List<Instant> missed = new ArrayList<>();
        var next = cron.next(from.atZone(zone));
        while (next != null && !next.toInstant().isAfter(now) && onTime.size() + missed.size() < MAX_FIRE_TIMES) {
            Instant due = next.toInstant();
            (Duration.between(due, now).compareTo(grace) <= 0 ? onTime : missed).add(due);
            next = cron.next(next);
        }
        if (!onTime.isEmpty()) {
            insertTrigger(schedule, onTime.getLast(), "SCHEDULED", "QUEUED", null);
            missed.forEach(due -> insertTrigger(schedule, due, "SCHEDULED", "SKIPPED", "Covered by a later run"));
        } else if (!missed.isEmpty()) {
            if (schedule.catchUp() == CatchUp.RUN_ONCE) {
                insertTrigger(schedule, missed.getLast(), "CATCH_UP", "QUEUED", null);
                missed.subList(0, missed.size() - 1)
                        .forEach(due -> insertTrigger(schedule, due, "SCHEDULED", "SKIPPED", "Covered by catch-up run"));
            } else {
                missed.forEach(due -> insertTrigger(schedule, due, "SCHEDULED", "SKIPPED", "Missed; catch-up policy SKIP"));
            }
        }
        if (!missed.isEmpty()) {
            audit.record(new AuditEvent(SYSTEM, "SCHEDULE_MISSED", "SOURCE_SCHEDULE", key(schedule),
                    stateOf(schedule), AuditService.FAILURE,
                    AuditService.details("missedFireTimes", missed.size(), "catchUp", schedule.catchUp().name())));
        }
        try (var c = connections.open();
                var s = c.prepareStatement("update source_schedule set last_evaluated_at=? where state_code=?"
                        + " and source_system=?")) {
            s.setTimestamp(1, Timestamp.from(now));
            s.setString(2, schedule.stateCode());
            s.setString(3, schedule.sourceSystem());
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot advance the schedule", e);
        }
    }

    private void dispatch(Instant now) {
        record Queued(UUID id, String state, String source) { }
        List<Queued> queue = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select trigger_id,state_code,source_system from schedule_trigger"
                        + " where status='QUEUED' order by due_at, created_at")) {
            var r = s.executeQuery();
            while (r.next()) {
                queue.add(new Queued(r.getObject(1, UUID.class), r.getString(2), r.getString(3)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the trigger queue", e);
        }
        for (Queued item : queue) {
            if (busy(item.state(), item.source())) {
                continue; // One run per source at a time; the trigger stays queued.
            }
            start(item.id(), item.state(), item.source(), now);
        }
    }

    private void start(UUID trigger, String state, String source, Instant now) {
        try {
            var pack = packs.find(state).orElseThrow(() -> new IllegalStateException("Unknown State Pack: " + state));
            if (landing != null && pack.sources().stream().anyMatch(d -> d.sourceId().equals(source)
                    && landing.isLandingFolder(d) && landing.waiting(d).isEmpty())) {
                markTrigger(trigger, "SKIPPED", null, "No file is waiting in the landing folder", now);
                return;
            }
            var mode = hasGolden() ? OnboardingService.ProcessingMode.INCREMENTAL : OnboardingService.ProcessingMode.FULL;
            UUID run = onboarding.start(pack.manifest().packId(), pack.manifest().version(), source, mode);
            markTrigger(trigger, "STARTED", run, null, now);
            audit.record(new AuditEvent(SYSTEM, "SCHEDULED_ONBOARDING_START", "ONBOARDING_RUN", run.toString(), state,
                    AuditService.SUCCESS, AuditService.details("sourceSystem", source, "processingMode", mode.name(),
                            "triggerId", trigger.toString())));
            executor.execute(() -> {
                try {
                    onboarding.runToCompletion(run);
                } catch (RuntimeException failed) {
                    // The failed stage is recorded on the run; it stays resumable.
                }
            });
        } catch (RuntimeException e) {
            String note = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            markTrigger(trigger, "START_FAILED", null, note, now);
            audit.record(new AuditEvent(SYSTEM, "SCHEDULED_ONBOARDING_START", "SOURCE_SCHEDULE", state + "/" + source,
                    state, AuditService.FAILURE, AuditService.details("reason", note)));
        }
    }

    private boolean busy(String state, String source) {
        return exists("select 1 from onboarding_run where state_code=? and source_system=?"
                + " and status in ('RUNNING','PAUSED') limit 1", state, source);
    }

    private boolean hasGolden() {
        return exists("select 1 from golden_resident limit 1", null, null);
    }

    private boolean exists(String sql, String a, String b) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            if (a != null) {
                s.setString(1, a);
                s.setString(2, b);
            }
            return s.executeQuery().next();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot query scheduler state", e);
        }
    }

    // ---- persistence helpers ---------------------------------------------------------------------------------------

    private void insertTrigger(Schedule schedule, Instant due, String kind, String status, String note) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into schedule_trigger(trigger_id,state_code,source_system,due_at,kind,"
                        + "status,note) values(?,?,?,?,?,?,?)")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, schedule.stateCode());
            s.setString(3, schedule.sourceSystem());
            s.setTimestamp(4, Timestamp.from(due));
            s.setString(5, kind);
            s.setString(6, status);
            s.setString(7, note);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot queue the scheduled run", e);
        }
    }

    private void markTrigger(UUID trigger, String status, UUID run, String note, Instant now) {
        try (var c = connections.open();
                var s = c.prepareStatement("update schedule_trigger set status=?,onboarding_run_id=?,note=?,started_at=?"
                        + " where trigger_id=?")) {
            s.setString(1, status);
            s.setObject(2, run);
            s.setString(3, note);
            s.setTimestamp(4, Timestamp.from(now));
            s.setObject(5, trigger);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot update the scheduled run", e);
        }
    }

    private Instant lastEvaluated(Schedule schedule) {
        try (var c = connections.open();
                var s = c.prepareStatement("select last_evaluated_at from source_schedule where state_code=?"
                        + " and source_system=?")) {
            s.setString(1, schedule.stateCode());
            s.setString(2, schedule.sourceSystem());
            var r = s.executeQuery();
            r.next();
            return r.getTimestamp(1).toInstant();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the schedule", e);
        }
    }

    private List<Schedule> schedules(String where, String state, String source) {
        List<Schedule> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select state_code,source_system,cron_expression,timezone,enabled,"
                        + "catch_up_policy from source_schedule " + where + " order by state_code,source_system")) {
            if (state != null) {
                s.setString(1, state);
                s.setString(2, source);
            }
            var r = s.executeQuery();
            while (r.next()) {
                rows.add(new Schedule(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getBoolean(5),
                        CatchUp.valueOf(r.getString(6))));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the schedules", e);
        }
    }

    private static String key(Schedule schedule) {
        return schedule.stateCode() + "/" + schedule.sourceSystem();
    }

    private static String stateOf(Schedule schedule) {
        return schedule.stateCode();
    }
}
