package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.SampleData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Part 2: a mid-pipeline failure, resume of only the failed stage, and the guards around resume. */
class OnboardingFailureResumeTest {
    private static final String PACK = "state-4";
    private static final String VERSION = "1.0.0";
    private static final String SOURCE = "citizen-register";
    private static final String INJECTED = "injected matching failure for test";

    @Test
    void failedMiddleStageIsRetriedAloneAndTheRunCompletes() {
        var harness = harness();
        UUID run = harness.onboarding.start(PACK, VERSION, SOURCE);
        failAtMatched(harness, run);

        List<Row> before = rows(harness, run);
        assertEquals(6, before.size());
        for (Row row : before.subList(0, 5)) {
            assertEquals("COMPLETED", row.status(), row.stage());
            assertEquals(1, row.attempt(), row.stage());
        }
        Row failed = before.get(5);
        assertEquals("MATCHED", failed.stage());
        assertEquals("FAILED", failed.status());
        assertEquals(1, failed.attempt());
        assertEquals(INJECTED, failed.error());
        assertNotNull(failed.completedAt(), "a failed attempt records when it ended");
        assertEquals("FAILED", runValue(harness, run, "status"));
        assertEquals("MATCHED", runValue(harness, run, "current_stage"));
        assertEquals(INJECTED, runValue(harness, run, "failure_message"));
        Snapshot data = Snapshot.of(harness);

        harness.failMatchingWith = null; // fix the cause
        harness.onboarding.resume(run);

        List<Row> afterResume = rows(harness, run);
        assertEquals(before, afterResume.subList(0, 6), "earlier stages and the failed attempt are left untouched");
        Row retried = afterResume.get(6);
        assertEquals("MATCHED", retried.stage());
        assertEquals("COMPLETED", retried.status());
        assertEquals(2, retried.attempt());
        assertNotEquals(failed.executionId(), retried.executionId());
        assertNull(retried.error());
        assertEquals(data, Snapshot.of(harness), "resume created no raw, batch or canonical rows");
        assertEquals(1, harness.hopCalls.get(), "INGESTED was not re-executed");
        assertEquals(2, harness.matchingCalls.get(), "MATCHED ran once per attempt");

        harness.onboarding.executeNext(run); // GOLDEN_BUILT
        harness.onboarding.executeNext(run); // PUBLISHED

        List<Row> done = rows(harness, run);
        assertEquals(List.of("PACK_VALIDATED", "SOURCE_TESTED", "INGESTED", "MAPPED", "DQ_CHECKED", "MATCHED", "MATCHED",
                "GOLDEN_BUILT", "PUBLISHED"), done.stream().map(Row::stage).toList());
        assertEquals(before, done.subList(0, 6));
        assertEquals(retried.matchingRunId(), done.get(7).matchingRunId(), "GOLDEN_BUILT used the retried matching run");
        assertEquals("COMPLETED", runValue(harness, run, "status"));
        assertEquals(data, Snapshot.of(harness));
        assertEquals(9, done.stream().map(Row::executionId).distinct().count());
    }

    @Test
    void resumingACompletedRunIsRejectedAndChangesNothing() {
        var harness = harness();
        UUID run = harness.onboarding.start(PACK, VERSION, SOURCE);
        for (int i = 0; i < OnboardingStage.values().length; i++) {
            harness.onboarding.executeNext(run);
        }
        List<Row> before = rows(harness, run);

        var error = assertThrows(IllegalStateException.class, () -> harness.onboarding.resume(run));
        assertTrue(error.getMessage().contains("Only a failed onboarding run can be resumed"), error.getMessage());
        assertThrows(IllegalStateException.class, () -> harness.onboarding.executeNext(run));
        assertEquals(before, rows(harness, run));
        assertEquals("COMPLETED", runValue(harness, run, "status"));
    }

    @Test
    void twoConcurrentResumesOfOneRunCannotBothExecute() throws Exception {
        var harness = harness();
        UUID run = harness.onboarding.start(PACK, VERSION, SOURCE);
        failAtMatched(harness, run);
        harness.failMatchingWith = null;
        var inMatching = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        harness.beforeMatching = () -> {
            inMatching.countDown();
            await(release);
        };
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        harness.onboarding.resume(run);
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                }));
            }
            assertTrue(inMatching.await(30, TimeUnit.SECONDS), "one resume reached the matching boundary");
            // Hold the winner inside MATCHED long enough for the other resume to finish its attempt.
            Throwable loser = null;
            for (Future<Throwable> outcome : outcomes) {
                try {
                    Throwable t = outcome.get(5, TimeUnit.SECONDS);
                    if (t != null) {
                        loser = t;
                    }
                } catch (java.util.concurrent.TimeoutException stillRunning) {
                    // the winner, blocked in MATCHED
                }
            }
            release.countDown();
            int succeeded = 0;
            for (Future<Throwable> outcome : outcomes) {
                Throwable t = outcome.get(30, TimeUnit.SECONDS);
                if (t == null) {
                    succeeded++;
                } else {
                    loser = t;
                }
            }
            assertEquals(1, succeeded, "exactly one resume executes");
            assertNotNull(loser);
            assertTrue(loser instanceof IllegalStateException, String.valueOf(loser));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(2, harness.matchingCalls.get(), "MATCHED executed once for the failure and once for one resume");
        List<Row> rows = rows(harness, run);
        assertEquals(7, rows.size());
        assertEquals(1, rows.stream().filter(r -> r.stage().equals("MATCHED") && r.attempt() == 2).count());
        assertEquals("COMPLETED", rows.getLast().status());
        assertEquals("RUNNING", runValue(harness, run, "status"));
        assertEquals("GOLDEN_BUILT", runValue(harness, run, "current_stage"));
    }

    @Test
    void aStageAlreadyInFlightCannotBeExecutedAgainConcurrently() throws Exception {
        var harness = harness();
        UUID run = harness.onboarding.start(PACK, VERSION, SOURCE);
        for (int i = 0; i < 5; i++) {
            harness.onboarding.executeNext(run);
        }
        var inMatching = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        harness.beforeMatching = () -> {
            if (first.getAndSet(false)) {
                inMatching.countDown();
                await(release);
            }
        };
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> winner = pool.submit(() -> harness.onboarding.executeNext(run));
            assertTrue(inMatching.await(30, TimeUnit.SECONDS));

            var error = assertThrows(IllegalStateException.class, () -> harness.onboarding.executeNext(run),
                    "a second caller must not start MATCHED while it is already running");
            assertTrue(error.getMessage().contains("already"), error.getMessage());

            release.countDown();
            winner.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, harness.matchingCalls.get(), "MATCHED executed once");
        List<Row> rows = rows(harness, run);
        assertEquals(6, rows.size());
        assertEquals("COMPLETED", rows.getLast().status());
        assertEquals(1, rows.getLast().attempt());
        assertEquals("RUNNING", runValue(harness, run, "status"));
        assertEquals("GOLDEN_BUILT", runValue(harness, run, "current_stage"));
    }

    private static OnboardingHarness harness() {
        return new OnboardingHarness(SampleData.REPOSITORY_ROOT.resolve("m10-state-packs")).activate(PACK, VERSION);
    }

    /** Runs PACK_VALIDATED..DQ_CHECKED, then fails MATCHED through the generic matching-boundary hook. */
    private static void failAtMatched(OnboardingHarness harness, UUID run) {
        for (int i = 0; i < 5; i++) {
            harness.onboarding.executeNext(run);
        }
        harness.failMatchingWith = INJECTED;
        var error = assertThrows(IllegalStateException.class, () -> harness.onboarding.executeNext(run));
        assertEquals(INJECTED, error.getMessage());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String runValue(OnboardingHarness harness, UUID run, String column) {
        return harness.query("select " + column + " from onboarding_run where onboarding_run_id=?", r -> {
            try {
                r.next();
                return r.getString(1);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, run);
    }

    private static List<Row> rows(OnboardingHarness harness, UUID run) {
        return harness.query("select onboarding_stage_execution_id,stage,status,attempt,started_at::text,completed_at::text,"
                + "input_count,output_count,ingestion_batch_id,matching_run_id,error_message from onboarding_stage_execution"
                + " where onboarding_run_id=? order by started_at,attempt", r -> {
                    try {
                        List<Row> rows = new ArrayList<>();
                        while (r.next()) {
                            rows.add(new Row(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getInt(4),
                                    r.getString(5), r.getString(6), (Integer) r.getObject(7), (Integer) r.getObject(8),
                                    r.getObject(9, UUID.class), r.getObject(10, UUID.class), r.getString(11)));
                        }
                        return rows;
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, run);
    }

    private record Row(UUID executionId, String stage, String status, int attempt, String startedAt, String completedAt,
                       Integer input, Integer output, UUID batchId, UUID matchingRunId, String error) {
    }

    private record Snapshot(int batches, int raw, int canonical, int dqEvaluations) {
        static Snapshot of(OnboardingHarness harness) {
            return new Snapshot(harness.count("select count(*) from ingestion_batch"),
                    harness.count("select count(*) from raw_record"),
                    harness.count("select count(*) from canonical_resident"),
                    harness.count("select count(*) from dq_batch_evaluation"));
        }
    }
}
