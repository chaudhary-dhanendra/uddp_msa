package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.onboarding.SchedulerService.CatchUp;
import gov.uddp.core.onboarding.SchedulerService.Schedule;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Track C2: schedules fire on time, queue per source, catch up after downtime, and recover stuck stages. */
class SchedulerServiceTest {
    private static final String PACK = "state-4";
    private static final String SOURCE = "citizen-register";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = START;

        void set(String instant) {
            now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();

    private OnboardingHarness harness() {
        return new OnboardingHarness(SampleData.REPOSITORY_ROOT.resolve("m10-state-packs")).activate(PACK, "1.0.0");
    }

    private SchedulerService scheduler(OnboardingHarness harness) {
        return new SchedulerService(harness.connections, harness.onboarding, harness.packs,
                new AuditService(harness.connections, new ObjectMapper()), clock, Runnable::run, Duration.ofMinutes(2),
                Duration.ofHours(1));
    }

    private String state(OnboardingHarness harness) {
        return harness.packs.find(PACK).orElseThrow().manifest().stateCode();
    }

    private void schedule(OnboardingHarness harness, SchedulerService scheduler, CatchUp catchUp) {
        scheduler.save(new Schedule(state(harness), SOURCE, "0 2 * * *", "UTC", true, catchUp));
    }

    @Test
    void firesOnTimeStartsAnAuditedSystemRunAndNeverEarly() {
        var harness = harness();
        var scheduler = scheduler(harness);
        var sql = new Sql(harness.connections);
        schedule(harness, scheduler, CatchUp.RUN_ONCE);

        clock.set("2026-01-01T01:59:00Z");
        scheduler.tick();
        assertEquals(0, sql.count("select count(*) from schedule_trigger"));
        assertEquals(0, sql.count("select count(*) from onboarding_run"));

        clock.set("2026-01-01T02:00:30Z");
        scheduler.tick();
        assertEquals(1, sql.count("select count(*) from schedule_trigger where status='STARTED' and kind='SCHEDULED'"));
        assertEquals(1, sql.count("select count(*) from onboarding_run where status='COMPLETED'"));
        assertEquals(1, sql.count("select count(*) from app_audit_event where actor_username='SYSTEM'"
                + " and action='SCHEDULED_ONBOARDING_START' and result='SUCCESS'"));

        scheduler.tick();
        assertEquals(1, sql.count("select count(*) from schedule_trigger"), "a fire time triggers only once");
    }

    @Test
    void runsForOneSourceQueueBehindTheRunningOne() {
        var harness = harness();
        var scheduler = scheduler(harness);
        var sql = new Sql(harness.connections);
        schedule(harness, scheduler, CatchUp.RUN_ONCE);
        UUID running = harness.onboarding.start(PACK, "1.0.0", SOURCE);

        clock.set("2026-01-01T02:00:30Z");
        scheduler.tick();
        assertEquals(1, sql.count("select count(*) from schedule_trigger where status='QUEUED'"));
        assertEquals(1, sql.count("select count(*) from onboarding_run"), "no second run while one is active");

        harness.onboarding.runToCompletion(running);
        scheduler.tick();
        assertEquals(0, sql.count("select count(*) from schedule_trigger where status='QUEUED'"));
        assertEquals(2, sql.count("select count(*) from onboarding_run"));
    }

    @Test
    void catchUpRunsOnceAfterDowntimeOrSkipsByPolicy() {
        var harness = harness();
        var scheduler = scheduler(harness);
        var sql = new Sql(harness.connections);
        schedule(harness, scheduler, CatchUp.RUN_ONCE);

        clock.set("2026-01-04T03:00:00Z"); // 02:00 on Jan 1 to 4 were missed
        scheduler.tick();
        assertEquals(1, sql.count("select count(*) from schedule_trigger where kind='CATCH_UP' and status='STARTED'"));
        assertEquals(3, sql.count("select count(*) from schedule_trigger where status='SKIPPED'"));
        assertEquals(1, sql.count("select count(*) from onboarding_run"));

        schedule(harness, scheduler, CatchUp.SKIP);
        clock.set("2026-01-08T03:00:00Z");
        scheduler.tick();
        assertEquals(1, sql.count("select count(*) from schedule_trigger where kind='CATCH_UP'"));
        assertEquals(1, sql.count("select count(*) from onboarding_run"), "SKIP starts nothing");
        assertEquals(3 + 4, sql.count("select count(*) from schedule_trigger where status='SKIPPED'"));
        assertEquals(2, sql.count("select count(*) from app_audit_event where action='SCHEDULE_MISSED'"
                + " and actor_username='SYSTEM'"));
    }

    @Test
    void stageStuckRunningBeyondTheTimeoutIsFailedAndResumable() {
        var harness = harness();
        var scheduler = scheduler(harness);
        var sql = new Sql(harness.connections);
        UUID run = harness.onboarding.start(PACK, "1.0.0", SOURCE);
        insertRunningStage(harness, run, Instant.parse("2025-12-31T21:00:00Z"));

        clock.set("2026-01-01T00:00:00Z");
        scheduler.tick();
        assertEquals("FAILED", harness.onboarding.detail(run).get("status"));
        assertEquals(1, sql.count("select count(*) from onboarding_stage_execution where status='FAILED'"
                + " and error_message like 'Stage timed out%'"));
        assertEquals(1, sql.count("select count(*) from app_audit_event where actor_username='SYSTEM'"
                + " and action='ONBOARDING_STAGE_TIMEOUT'"));

        harness.onboarding.resume(run);
        assertEquals("RUNNING", harness.onboarding.detail(run).get("status"));
        assertEquals(2, sql.count("select max(attempt) from onboarding_stage_execution where onboarding_run_id=?", run));
    }

    @Test
    void rejectsAnInvalidCronExpressionAndTimezone() {
        var harness = harness();
        var scheduler = scheduler(harness);
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.save(new Schedule("X", SOURCE, "not a cron", "UTC", true, CatchUp.SKIP)));
        assertThrows(java.time.DateTimeException.class,
                () -> scheduler.save(new Schedule("X", SOURCE, "0 2 * * *", "Mars/Base", true, CatchUp.SKIP)));
    }

    private void insertRunningStage(OnboardingHarness harness, UUID run, Instant startedAt) {
        try (var c = harness.connections.open();
                var s = c.prepareStatement("insert into onboarding_stage_execution(onboarding_run_id,stage,status,attempt,"
                        + "started_at) values(?,'PACK_VALIDATED','RUNNING',1,?)")) {
            s.setObject(1, run);
            s.setTimestamp(2, Timestamp.from(startedAt));
            s.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
