package gov.uddp.core.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.core.inbox.ExceptionInboxService.Filter;
import gov.uddp.core.inbox.ExceptionSource.Item;
import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Track C4: items from several sources are aggregated, filtered, counted and limited to the caller's scope. */
class ExceptionInboxServiceTest {
    private static final Instant NOW = Instant.parse("2026-03-10T00:00:00Z");

    private static ExceptionSource source(List<String> types, Item... items) {
        return new ExceptionSource() {
            @Override
            public List<String> types() {
                return types;
            }

            @Override
            public List<Item> items() {
                return List.of(items);
            }
        };
    }

    private static Item item(String id, String type, String priority, String state, String createdAt) {
        return new Item(id, type, "title " + id, "detail", priority, state, "src", Instant.parse(createdAt), "/x/" + id);
    }

    @Test
    void aggregatesSourcesFiltersByTypePriorityAndAgeAndRespectsScope() {
        var harness = new OnboardingHarness(SampleData.STATE_PACKS);
        String state1 = harness.packs.find("state-1").orElseThrow().manifest().stateCode();
        String state2 = harness.packs.find("state-2").orElseThrow().manifest().stateCode();
        var inbox = new ExceptionInboxService(List.of(
                source(List.of("GATE_FAILURE", "PAUSED_RUN"),
                        item("a", "GATE_FAILURE", "HIGH", state1, "2026-03-09T00:00:00Z"),
                        item("b", "PAUSED_RUN", "HIGH", state2, "2026-03-01T00:00:00Z")),
                source(List.of("REVIEW_TASK"), item("c", "REVIEW_TASK", "LOW", state1, "2026-02-01T00:00:00Z"))),
                harness.packs, Clock.fixed(NOW, ZoneOffset.UTC));
        java.util.function.Predicate<String> all = state -> true;

        assertEquals(List.of("b", "a", "c"), ids(inbox.list(null, all)), "highest priority first, oldest first within it");
        assertEquals(Map.of("GATE_FAILURE", 1, "PAUSED_RUN", 1, "REVIEW_TASK", 1), inbox.counts(null, all));

        assertEquals(List.of("a"), ids(inbox.list(new Filter(null, "GATE_FAILURE", null, null), all)));
        assertEquals(List.of("c"), ids(inbox.list(new Filter(null, null, "LOW", null), all)));
        assertEquals(List.of("b", "c"), ids(inbox.list(new Filter(null, null, null, 7), all)), "at least 7 days old");

        String department = harness.packs.find("state-1").orElseThrow().manifest().departmentCode();
        assertEquals(department == null ? 0 : 2,
                inbox.list(new Filter(department, null, null, null), all).stream()
                        .filter(e -> e.item().stateCode().equals(state1)).count(),
                "the department filter keeps the items of that department's sources");

        // A caller who may only see one state sees only its items, and the counts follow.
        assertEquals(List.of("a", "c"), ids(inbox.list(null, state -> state.equals(state1))));
        assertEquals(Map.of("GATE_FAILURE", 1, "PAUSED_RUN", 0, "REVIEW_TASK", 1),
                inbox.counts(null, state -> state.equals(state1)));
        assertEquals(List.of(), inbox.list(null, state -> false));
    }

    @Test
    void platformSourcesReadPausedAndFailedRunsFromTheDatabase() {
        var harness = new OnboardingHarness(SampleData.REPOSITORY_ROOT.resolve("services/statepack-service/seed/m10-state-packs")).activate("state-4", "1.0.0");
        var sql = new Sql(harness.connections);
        UUID paused = harness.onboarding.start("state-4", "1.0.0", "citizen-register");
        UUID failed = harness.onboarding.start("state-4", "1.0.0", "citizen-register");
        try (var c = harness.connections.open(); var s = c.createStatement()) {
            s.executeUpdate("update onboarding_run set status='PAUSED' where onboarding_run_id='" + paused + "'");
            s.executeUpdate("insert into run_release_decision(onboarding_run_id,decision,reasons,decided_by)"
                    + " values('" + paused + "','PENDING','gate failed: ERROR_RATE_PCT','SYSTEM')");
            s.executeUpdate("insert into quality_gate_result(onboarding_run_id,state_code,source_system,gate_type,"
                    + "observed,threshold,status) select onboarding_run_id,state_code,source_system,'ERROR_RATE_PCT',"
                    + "40,10,'FAIL' from onboarding_run where onboarding_run_id='" + paused + "'");
            s.executeUpdate("update onboarding_run set status='FAILED',failure_message='boom' where onboarding_run_id='"
                    + failed + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        var connections = harness.connections;
        var inbox = new ExceptionInboxService(List.of(new PlatformExceptionSources.PausedRuns(connections),
                new PlatformExceptionSources.FailedRuns(connections), new PlatformExceptionSources.UnmappedCodes(connections),
                new PlatformExceptionSources.ReviewTasks(connections), new PlatformExceptionSources.PendingApprovals(connections)),
                harness.packs, Clock.systemUTC());

        var counts = inbox.counts(null, state -> true);
        assertEquals(1, counts.get("PAUSED_RUN"));
        assertEquals(1, counts.get("GATE_FAILURE"));
        assertEquals(1, counts.get("FAILED_RUN"));
        assertEquals(0, counts.get("FAILED_SCHEDULED_RUN"));
        assertEquals(0, counts.get("UNMAPPED_CODE") + counts.get("REVIEW_TASK") + counts.get("PACK_APPROVAL")
                + counts.get("PROPOSAL_APPROVAL"));
        assertEquals("/onboarding-runs/" + paused, inbox.list(new Filter(null, "PAUSED_RUN", null, null), s -> true)
                .getFirst().item().link());
        assertEquals(0, sql.count("select count(*) from schedule_trigger"));
    }

    private static List<String> ids(List<ExceptionInboxService.Entry> entries) {
        return entries.stream().map(e -> e.item().id()).toList();
    }
}
