package gov.uddp.core.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresRawRecordRepository;
import gov.uddp.core.held.HeldRecordRepository;
import gov.uddp.core.onboarding.DeliveryChangeService;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.UddpAuthentication;
import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestSecurity;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/** A delivery whose held and rejected share is over the limit pauses for approval before activation. */
class HeldShareGateTest {
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
    void aDeliveryOverTheHeldLimitPausesAndOneWithinItContinues() {
        var harness = new OnboardingHarness(SampleData.REPOSITORY_ROOT.resolve("services/statepack-service/seed/m10-state-packs")).activate(PACK, "1.0.0");
        var json = new ObjectMapper();
        harness.onboarding.setDeliveryChanges(new DeliveryChangeService(harness.connections,
                new PostgresRawRecordRepository(harness.connections, json), harness.packs, json));
        var gates = new QualityGateService(harness.connections, List.of());
        var release = new ReleaseService(harness.connections, gates, new AuditService(harness.connections, json));
        // The gate reads the held share through the held store (canonical-service in the microservice deployment).
        var repository = new HeldRecordRepository(harness.connections, json);
        var held = org.mockito.Mockito.mock(gov.uddp.core.held.HeldStore.class);
        org.mockito.Mockito.when(held.share(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> repository.share(call.getArgument(0)));
        release.setHeldGate(held, 5);
        harness.onboarding.setReleaseCheckpoint(release);

        signInAs("alice");
        UUID first = harness.onboarding.start(PACK, "1.0.0", SOURCE);
        harness.onboarding.runToCompletion(first);
        signInAs("bob");
        release.approve(first, "first delivery looks right");
        harness.onboarding.runToCompletion(first);

        signInAs("alice");
        UUID within = harness.onboarding.start(PACK, "1.0.0", SOURCE);
        harness.onboarding.runToCompletion(within);
        assertEquals("COMPLETED", harness.onboarding.detail(within).get("status"), "no held rows: within the 5% limit");

        // A limit below zero stands for "any delivery is over", which exercises the pause without crafting bad rows.
        release.setHeldGate(held, -1);
        UUID over = harness.onboarding.start(PACK, "1.0.0", SOURCE);
        harness.onboarding.runToCompletion(over);

        assertEquals("PAUSED", harness.onboarding.detail(over).get("status"));
        assertTrue(String.valueOf(release.decisions(over).getFirst().get("reasons"))
                .contains("held and rejected rows are 0.0% of the delivery (limit -1.0%)"));
        signInAs("bob");
        release.approve(over, "accepted");
        harness.onboarding.runToCompletion(over);
        assertEquals("COMPLETED", harness.onboarding.detail(over).get("status"));
    }
}
