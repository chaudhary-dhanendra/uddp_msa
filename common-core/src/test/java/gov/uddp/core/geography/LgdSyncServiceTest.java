package gov.uddp.core.geography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.connector.ConnectorSecrets;
import gov.uddp.core.monitoring.AlertService;
import gov.uddp.core.monitoring.MonitoringService;
import gov.uddp.core.monitoring.MonitoringService.Metric;
import gov.uddp.core.read.Db;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Track F3: LGD master syncs never overlap, a failed sync keeps the last known good master, and a C6 alert rule on the
 * master's age fires once the master is stale.
 */
class LgdSyncServiceTest {
    private final PostgresConnectionFactory db = TestDatabase.freshMigrated();
    private final MovingClock clock = new MovingClock();
    private final List<Runnable> queued = new ArrayList<>();
    private Supplier<LgdSnapshotData> fetch = () -> FileLgdSourceClient.read(LgdMasterImportTest.FIXTURE);
    private final LgdSourceClient source = new LgdSourceClient() {
        @Override
        public String describe() {
            return "fixture";
        }

        @Override
        public Optional<LgdSnapshotData> fetch() {
            return Optional.of(fetch.get());
        }
    };
    private final Db reads = new Db(db, new ObjectMapper());
    private final LgdSyncService sync = new LgdSyncService(db, reads, source,
            new LgdImportService(db, new ObjectMapper(), clock), new AuditService(db, new ObjectMapper()), clock,
            queued::add, Duration.ofHours(24), Duration.ofHours(6));

    @Test
    void syncsNeverOverlap() {
        var first = sync.start("MANUAL", "admin");
        assertEquals("RUNNING", first.status());
        assertThrows(LgdSyncService.SyncAlreadyRunningException.class, () -> sync.start("MANUAL", "admin"));
        assertThrows(LgdSyncService.SyncAlreadyRunningException.class, () -> sync.runNow("SCHEDULED", "SYSTEM"));

        sync.saveSchedule(new LgdSyncService.SyncSchedule("0 * * * *", "UTC", true), "admin");
        clock.advance(Duration.ofHours(2));
        sync.tick(clock.instant());
        assertEquals(1, queued.size(), "a due schedule does not start a second sync while one runs");
        assertTrue(sync.status().running());

        queued.removeFirst().run();
        var done = sync.run(first.runId()).orElseThrow();
        assertEquals("SUCCEEDED", done.status());
        assertEquals("admin", done.actor());
        assertEquals(13, done.changeCount());

        clock.advance(Duration.ofDays(1));
        sync.tick(clock.instant());
        assertEquals(1, queued.size(), "the next fire time starts a scheduled sync");
        queued.removeFirst().run();
        var scheduled = sync.runs(0, 10).items().getFirst();
        assertEquals("SCHEDULED", scheduled.triggerKind());
        assertEquals("SYSTEM", scheduled.actor());
        assertEquals("UNCHANGED", scheduled.status());
        assertEquals(2, reads.count("select count(*) from app_audit_event where action='LGD_MASTER_SYNC'"));
    }

    @Test
    void aFailedSyncKeepsTheLastKnownGoodMaster() {
        var good = sync.runNow("MANUAL", "admin");
        assertEquals("SUCCEEDED", good.status());

        fetch = () -> {
            throw new IllegalStateException("LGD endpoint unreachable");
        };
        var unreachable = sync.runNow("SCHEDULED", "SYSTEM");
        assertEquals("FAILED", unreachable.status());
        assertTrue(unreachable.error().contains("unreachable"));

        fetch = () -> new LgdSnapshotData("broken", LocalDate.parse("2024-08-01"), List.of(
                new LgdRow(1, "DISTRICT", "901", "Renamed", "ACTIVE", "STATE", "90", null),
                new LgdRow(2, "VILLAGE", "500009", "Orphan", "ACTIVE", "SUB_DISTRICT", "0000", null)));
        var invalid = sync.runNow("SCHEDULED", "SYSTEM");
        assertEquals("FAILED", invalid.status());

        var status = sync.status();
        assertEquals(good.snapshotId(), status.activeSnapshotId(), "the master stays on the last good snapshot");
        assertEquals(2, status.consecutiveFailures());
        assertEquals("FAILED", status.lastRun().status());
        assertEquals("North District", reads.one("select name from lgd_entity_version where code='901'"
                + " and effective_to is null", r -> r.getString(1)).orElseThrow());
        assertEquals(13, sync.changes(null, null, null, null, 0, 50).totalItems());
        assertFalse(status.running());
    }

    @Test
    void anAlertFiresWhenTheMasterIsStale() {
        var monitoring = new MonitoringService(db, null, List.of(), clock);
        monitoring.setPlatformSources(List.of(sync));
        var alerts = new AlertService(db, monitoring, new NoSecrets(), (url, body, headers) -> { },
                new ObjectMapper(), clock, Duration.ofMinutes(1));
        alerts.saveRule(new AlertService.Rule(null, "LGD master stale", Metric.LGD_MASTER_AGE_HOURS, "GT", 48,
                "WARNING", null, null, true));
        alerts.saveRule(new AlertService.Rule(null, "LGD sync failing", Metric.LGD_SYNC_FAILURES, "GT", 2,
                "CRITICAL", null, null, true));

        sync.runNow("MANUAL", "admin");
        assertEquals(0, alerts.evaluate());
        clock.advance(Duration.ofHours(30));
        assertTrue(sync.status().stale(), "stale after the configured 24 hours");
        assertEquals(0, alerts.evaluate(), "the rule's own threshold is 48 hours");

        clock.advance(Duration.ofHours(20));
        assertEquals(1, alerts.evaluate());
        var notification = alerts.notifications(true, 10, state -> true).getFirst();
        assertEquals("PLATFORM", notification.get("stateCode"));
        assertEquals("LGD_MASTER_AGE_HOURS", notification.get("metric"));
        assertEquals(0, alerts.evaluate(), "one notification until it is read");

        fetch = () -> {
            throw new IllegalStateException("down");
        };
        for (int i = 0; i < 3; i++) {
            sync.runNow("SCHEDULED", "SYSTEM");
        }
        assertEquals(1, alerts.evaluate(), "three failures in a row breach the failure rule");
        assertEquals(3.0, monitoring.platformMetrics().get(Metric.LGD_SYNC_FAILURES));
    }

    private static final class NoSecrets implements ConnectorSecrets {
        @Override
        public Optional<String> get(String name) {
            return Optional.empty();
        }

        @Override
        public void put(String name, String value) {
        }
    }

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2025-01-01T00:30:00Z");

        void advance(Duration by) {
            now = now.plus(by);
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
}
