package gov.uddp.core.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresRawRecordRepository;
import gov.uddp.core.gates.QualityGateService.GateType;
import gov.uddp.core.gates.QualityGateService.Threshold;
import gov.uddp.core.onboarding.DeliveryChangeService;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.UddpAuthentication;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestSecurity;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Track C3: gates pass and auto-approve, fail and pause for a human, and a new source always needs a human. */
class QualityGateReleaseTest {
    private static final String PACK = "state-4";
    private static final String SOURCE = "citizen-register";

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UddpAuthentication(TestSecurity.principal(username,
                java.util.Set.of("DATA_STEWARD"), EnumSet.of(Permission.ONBOARDING_RUN, Permission.ONBOARDING_APPROVE),
                List.of(gov.uddp.core.security.DepartmentScope.ALL))));
    }

    @Test
    void newSourceNeedsAHumanThenRoutineRedeliveriesAutoApproveUntilAGateFails() {
        var harness = new OnboardingHarness(SampleData.REPOSITORY_ROOT.resolve("services/statepack-service/seed/m10-state-packs")).activate(PACK, "1.0.0");
        var sql = new Sql(harness.connections);
        var audit = new AuditService(harness.connections, new ObjectMapper());
        harness.onboarding.setDeliveryChanges(new DeliveryChangeService(harness.connections,
                new PostgresRawRecordRepository(harness.connections, new ObjectMapper()), harness.packs,
                new ObjectMapper()));
        double[] matchRateChange = {1.0};
        MetricsSource matching = new MetricsSource() {
            @Override
            public String name() {
                return "test-matching";
            }

            @Override
            public Map<String, Double> metrics(String state, String source, UUID run, UUID batch) {
                return Map.of("MATCH_RATE_CHANGE_PCT", matchRateChange[0]);
            }
        };
        var gates = new QualityGateService(harness.connections, List.of(matching));
        var release = new ReleaseService(harness.connections, gates, audit);
        harness.onboarding.setReleaseCheckpoint(release);
        String state = harness.packs.find(PACK).orElseThrow().manifest().stateCode();
        gates.saveThresholds(state, SOURCE, List.of(new Threshold(GateType.MAPPING_COVERAGE_PCT, 90, true),
                new Threshold(GateType.CHANGE_VOLUME_PCT, 50, true),
                new Threshold(GateType.MATCH_RATE_CHANGE_PCT, 5, true)));

        // A new source: all gates pass, but a person must release it, and not the person who started the run.
        signInAs("alice");
        UUID first = harness.onboarding.start(PACK, "1.0.0", SOURCE);
        harness.onboarding.runToCompletion(first);
        assertEquals("PAUSED", harness.onboarding.detail(first).get("status"));
        assertEquals(List.of("PENDING"), decisions(release, first));
        assertEquals(0, sql.count("select count(*) from golden_resident"), "paused before the Golden build");
        assertThrows(AccessDeniedException.class, () -> release.approve(first, "mine"));
        signInAs("bob");
        release.approve(first, "first delivery looks right");
        harness.onboarding.runToCompletion(first);
        assertEquals("COMPLETED", harness.onboarding.detail(first).get("status"));
        assertEquals(List.of("PENDING", "APPROVED"), decisions(release, first));

        // A routine re-delivery that passes every gate continues on its own and is audited as AUTO_APPROVED.
        signInAs("alice");
        UUID second = harness.onboarding.start(PACK, "1.0.0", SOURCE);
        harness.onboarding.runToCompletion(second);
        assertEquals("COMPLETED", harness.onboarding.detail(second).get("status"));
        assertEquals(List.of("AUTO_APPROVED"), decisions(release, second));
        assertEquals(3, sql.count("select count(*) from quality_gate_result where onboarding_run_id=? and status='PASS'",
                second));
        assertEquals(1, sql.count("select count(*) from app_audit_event where actor_username='SYSTEM'"
                + " and action='AUTO_APPROVED' and resource_id=?", second.toString()));

        // A failing gate pauses the run before the Golden build, and a person can reject the release.
        matchRateChange[0] = 12.0;
        UUID third = harness.onboarding.start(PACK, "1.0.0", SOURCE);
        harness.onboarding.runToCompletion(third);
        assertEquals("PAUSED", harness.onboarding.detail(third).get("status"));
        assertEquals(1, sql.count("select count(*) from quality_gate_result where onboarding_run_id=?"
                + " and gate_type='MATCH_RATE_CHANGE_PCT' and status='FAIL'", third));
        assertTrue(String.valueOf(release.decisions(third).getFirst().get("reasons"))
                .contains("gate failed: MATCH_RATE_CHANGE_PCT"));
        signInAs("bob");
        release.reject(third, "match rate moved too far");
        assertEquals("FAILED", harness.onboarding.detail(third).get("status"));

        // A dry run evaluates the same gates against the latest delivery without a run.
        var dry = gates.dryRun(state, SOURCE);
        assertEquals(3, dry.size());
        assertEquals(3, sql.count("select count(*) from quality_gate_result where dry_run and onboarding_run_id is null"));
    }

    @Test
    void routinePackEditsAutoApproveButNewSourcesAndMatchingChangesNeedAHuman() {
        var harness = new OnboardingHarness(SampleData.STATE_PACKS);
        var policy = new AutoApprovalPolicy(List.of(new MatchingChangeDetector()),
                new AuditService(harness.connections, new ObjectMapper()));
        LoadedStatePack pack = harness.packs.find("state-1").orElseThrow();
        assertTrue(policy.packEdit(Optional.of(pack), pack, true).autoApproved());
        assertFalse(policy.packEdit(Optional.empty(), pack, true).autoApproved(), "a new source needs a human");
        assertFalse(policy.packEdit(Optional.of(pack), pack, false).autoApproved(), "a failed gate needs a human");
        var changed = new LoadedStatePack(pack.manifest(), pack.sources(), pack.mappings(), pack.crosswalks(),
                pack.validations(), List.of(), pack.survivorship());
        assertFalse(pack.matching().isEmpty());
        var decision = policy.packEdit(Optional.of(pack), changed, true);
        assertFalse(decision.autoApproved());
        assertEquals(List.of("matching configuration changed"), decision.reasons());
    }

    private static List<String> decisions(ReleaseService release, UUID run) {
        return release.decisions(run).stream().map(d -> (String) d.get("decision")).toList();
    }
}
