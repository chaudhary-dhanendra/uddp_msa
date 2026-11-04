package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.SampleData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Part 1: a full successful onboarding run, asserted from the database rather than from API responses. */
class OnboardingPersistenceTest {
    private static final String PACK = "state-4";
    private static final String VERSION = "1.0.0";
    private static final String SOURCE = "citizen-register";

    @Test
    void fullRunPersistsEveryStageInOrderWithCountsTimestampsAndIdentifiers() {
        var harness = new OnboardingHarness(SampleData.REPOSITORY_ROOT.resolve("services/statepack-service/seed/m10-state-packs")).activate(PACK, VERSION);
        UUID run = harness.onboarding.start(PACK, VERSION, SOURCE);
        var stages = OnboardingStage.values();

        for (int i = 0; i < stages.length; i++) {
            assertRun(harness, run, "RUNNING", stages[i].name(), false);
            harness.onboarding.executeNext(run);
        }
        assertRun(harness, run, "COMPLETED", OnboardingStage.PUBLISHED.name(), true);

        List<Row> rows = rows(harness, run);
        assertEquals(Arrays.stream(stages).map(Enum::name).toList(), rows.stream().map(Row::stage).toList(),
                "every stage persisted exactly once, in pipeline order");
        assertEquals(stages.length, new HashSet<>(rows.stream().map(Row::executionId).toList()).size(),
                "every stage execution has its own ID");
        assertEquals(stages.length, harness.count("select count(*) from onboarding_stage_execution"),
                "no stage execution exists outside this run");
        assertEquals(stages.length, harness.count(
                "select count(*) from onboarding_stage_execution where onboarding_run_id=?", run),
                "the run ID links all stages");

        Row previous = null;
        for (Row row : rows) {
            assertEquals("COMPLETED", row.status(), row.stage());
            assertEquals(1, row.attempt(), row.stage());
            assertNotNull(row.executionId(), row.stage());
            assertNotNull(row.startedAt(), row.stage());
            assertNotNull(row.completedAt(), row.stage());
            assertFalse(row.completedAt().isBefore(row.startedAt()), row.stage() + " finished before it started");
            assertNotNull(row.durationMillis(), row.stage() + " duration must be derivable");
            assertTrue(row.durationMillis() >= 0, row.stage());
            assertNotNull(row.input(), row.stage() + " input count");
            assertNotNull(row.output(), row.stage() + " output count");
            assertNull(row.error(), row.stage());
            if (previous != null) {
                assertFalse(row.startedAt().isBefore(previous.completedAt()),
                        row.stage() + " started before " + previous.stage() + " finished");
            }
            previous = row;
        }

        UUID batch = rows.get(2).batchId();
        UUID matchingRun = rows.get(5).matchingRunId();
        assertNotNull(batch, "INGESTED records its ingestion batch");
        assertNotNull(matchingRun, "MATCHED records its matching run");
        int sourceLines = 2;
        int raw = harness.count("select count(*) from raw_record where batch_id=?", batch);
        int canonical = harness.count("select count(*) from canonical_resident where batch_id=?", batch);
        int dqEvaluated = harness.count("select records_evaluated from dq_batch_evaluation where batch_id=?", batch);
        int dqAffected = harness.count("select affected_record_count from dq_batch_evaluation where batch_id=?", batch);
        int clusterMembers = harness.count("select count(*) from resident_match_cluster_member where run_id=?", matchingRun);
        int golden = harness.count("select count(*) from golden_resident");
        assertEquals(sourceLines, raw);
        assertEquals(sourceLines, canonical);
        assertEquals(2, golden, "Ram Singh and Neha Verma are different people in a State 4-only run");

        assertCounts(rows.get(0), 0, 0);                       // PACK_VALIDATED: configuration check only
        assertCounts(rows.get(1), 0, 0);                       // SOURCE_TESTED: readability check only
        assertCounts(rows.get(2), raw, raw);                   // INGESTED: raw records landed
        assertCounts(rows.get(3), raw, canonical);             // MAPPED: raw read, canonical written
        assertCounts(rows.get(4), dqEvaluated, dqAffected);    // DQ_CHECKED: persisted DQ evaluation
        assertCounts(rows.get(5), canonical, clusterMembers);  // MATCHED: canonical considered, cluster members
        assertCounts(rows.get(6), canonical, golden);          // GOLDEN_BUILT: canonical considered, Golden groups
        assertCounts(rows.get(7), 0, 0);                       // PUBLISHED: status change only
        assertEquals(sourceLines, dqEvaluated);
        assertEquals(sourceLines, clusterMembers);
        assertEquals(batch, rows.get(3).batchId());
        assertEquals(batch, rows.get(4).batchId());
        assertEquals(matchingRun, rows.get(6).matchingRunId());

        OffsetDateTime runCompleted = harness.query(
                "select completed_at from onboarding_run where onboarding_run_id=?", r -> value(r, OffsetDateTime.class), run);
        assertFalse(runCompleted.isBefore(rows.getLast().completedAt()), "run completes only after PUBLISHED");
    }

    private static void assertRun(OnboardingHarness harness, UUID run, String status, String stage, boolean completed) {
        String actualStatus = harness.query("select status from onboarding_run where onboarding_run_id=?",
                r -> value(r, String.class), run);
        String actualStage = harness.query("select current_stage from onboarding_run where onboarding_run_id=?",
                r -> value(r, String.class), run);
        OffsetDateTime completedAt = harness.query("select completed_at from onboarding_run where onboarding_run_id=?",
                r -> value(r, OffsetDateTime.class), run);
        assertEquals(status, actualStatus, "run status before/after " + stage);
        assertEquals(stage, actualStage);
        assertEquals(completed, completedAt != null, "run completed_at before/after " + stage);
    }

    private static void assertCounts(Row row, int input, int output) {
        assertEquals(input, row.input(), row.stage() + " input count");
        assertEquals(output, row.output(), row.stage() + " output count");
    }

    private static List<Row> rows(OnboardingHarness harness, UUID run) {
        return harness.query("select onboarding_stage_execution_id,stage,status,attempt,started_at,completed_at,"
                + "(extract(epoch from completed_at-started_at)*1000)::bigint,input_count,output_count,"
                + "ingestion_batch_id,matching_run_id,error_message from onboarding_stage_execution"
                + " where onboarding_run_id=? order by started_at,attempt", r -> {
                    try {
                        List<Row> rows = new ArrayList<>();
                        while (r.next()) {
                            rows.add(new Row(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getInt(4),
                                    r.getObject(5, OffsetDateTime.class), r.getObject(6, OffsetDateTime.class),
                                    (Long) r.getObject(7), (Integer) r.getObject(8), (Integer) r.getObject(9),
                                    r.getObject(10, UUID.class), r.getObject(11, UUID.class), r.getString(12)));
                        }
                        return rows;
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, run);
    }

    private static <T> T value(java.sql.ResultSet r, Class<T> type) {
        try {
            r.next();
            return r.getObject(1, type);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Row(UUID executionId, String stage, String status, int attempt, OffsetDateTime startedAt,
                       OffsetDateTime completedAt, Long durationMillis, Integer input, Integer output, UUID batchId,
                       UUID matchingRunId, String error) {
    }
}
