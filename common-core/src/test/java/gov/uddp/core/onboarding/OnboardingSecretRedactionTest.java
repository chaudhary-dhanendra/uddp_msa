package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A failed INGESTED stage whose Hop output contains the database password: neither the stored stage error, the
 * run's failure message, the ingestion batch error nor the returned exception message contains it.
 */
class OnboardingSecretRedactionTest {
    @TempDir
    Path temp;

    @Test
    void aFailedHopRunStoresAndReturnsNoPassword() throws Exception {
        String password = "Pw-" + UUID.randomUUID();
        var connections = TestDatabase.freshMigratedWithPassword(password);
        var packs = SampleData.loader(SampleData.STATE_PACKS);
        var lifecycle = new StatePackLifecycleService(connections, packs);
        var hop = new ProcessHopRunner(connections, null, SampleData.REPOSITORY_ROOT,
                ProcessHopRunnerTest.fakeHop(temp.resolve("hop")).toString(), "uddp-mvp");
        var onboarding = new OnboardingService(connections, lifecycle, packs, hop,
                SampleData.processing(connections, packs), null, null);
        lifecycle.register("state-1");
        lifecycle.transition("state-1", "1.0.0", "VALIDATED");
        lifecycle.transition("state-1", "1.0.0", "ACTIVE");
        UUID run = onboarding.start("state-1", "1.0.0", "pds");

        var failure = assertThrows(OnboardingStageFailedException.class, () -> onboarding.runToCompletion(run));

        assertEquals(OnboardingStage.INGESTED, failure.stage());
        List<String> stored = new ArrayList<>();
        stored.add(failure.getMessage());
        try (var c = connections.open(); var s = c.createStatement()) {
            for (String sql : List.of(
                    "select failure_message from onboarding_run",
                    "select error_message from onboarding_stage_execution where status='FAILED'",
                    "select error_message from ingestion_batch")) {
                try (var r = s.executeQuery(sql)) {
                    while (r.next()) {
                        stored.add(r.getString(1));
                    }
                }
            }
        }
        assertEquals(4, stored.size(), stored.toString());
        for (String text : stored) {
            assertFalse(text.contains(password), "password leaked: " + text);
            assertTrue(text.contains("Generic Hop exited 3"), text);
        }
        assertEquals("FAILED", onboarding.detail(run).get("status"));
    }
}
