package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;

class OnboardingLifecycleTest {
    @Test void lifecycleGatesRunsAndPersistsCompletedPrerequisiteStages() {
        var connections = TestDatabase.freshMigrated();
        var packs = SampleData.loader(SampleData.REPOSITORY_ROOT.resolve("m10-state-packs"));
        var lifecycle = new StatePackLifecycleService(connections, packs);
        var onboarding = new OnboardingService(connections, lifecycle, packs);
        lifecycle.register("state-4");
        assertThrows(IllegalStateException.class, () -> onboarding.start("state-4", "1.0.0", "citizen-register"));
        lifecycle.transition("state-4", "1.0.0", "VALIDATED");
        lifecycle.transition("state-4", "1.0.0", "ACTIVE");
        var run = onboarding.start("state-4", "1.0.0", "citizen-register");
        onboarding.executeNext(run);
        onboarding.executeNext(run);
        var detail = onboarding.detail(run);
        assertEquals("INGESTED", detail.get("currentStage"));
        assertEquals(2, ((java.util.List<?>) detail.get("stages")).size());
        lifecycle.transition("state-4", "1.0.0", "RETIRED");
        assertThrows(IllegalStateException.class, () -> onboarding.start("state-4", "1.0.0", "citizen-register"));
    }

    @Test void persists_stage_attempts_and_resumes_only_the_failed_stage() {
        var connections = TestDatabase.freshMigrated();
        var packs = SampleData.loader(SampleData.REPOSITORY_ROOT.resolve("m10-state-packs"));
        var lifecycle = new StatePackLifecycleService(connections, packs);
        lifecycle.register("state-4");
        lifecycle.transition("state-4", "1.0.0", "VALIDATED");
        lifecycle.transition("state-4", "1.0.0", "ACTIVE");
        HopRunner hop = (source, state, version, batch) -> new HopRunner.Result(0, "fixture transport");
        var onboarding = new OnboardingService(connections, lifecycle, packs, hop,
                SampleData.processing(connections, packs), null, null);
        var run = onboarding.start("state-4", "1.0.0", "citizen-register");
        onboarding.executeNext(run); // PACK_VALIDATED
        onboarding.executeNext(run); // SOURCE_TESTED
        onboarding.executeNext(run); // INGESTED (empty fixture transport)
        onboarding.executeNext(run); // MAPPED
        assertThrows(IllegalStateException.class, () -> onboarding.executeNext(run)); // no DQ evaluation
        var failed = onboarding.detail(run);
        assertEquals("FAILED", failed.get("status"));
        var stages = (java.util.List<java.util.Map<String, Object>>) failed.get("stages");
        assertEquals(5, stages.size());
        assertEquals("FAILED", stages.getLast().get("status"));
        assertEquals(1, stages.get(2).get("attempt"));
        assertEquals(0, stages.get(2).get("inputCount"));
        assertEquals(0, stages.get(2).get("outputCount"));
        assertEquals(false, stages.get(2).get("ingestionBatchId") == null);
        assertThrows(IllegalStateException.class, () -> onboarding.resume(run));
        var retried = (java.util.List<java.util.Map<String, Object>>) onboarding.detail(run).get("stages");
        assertEquals(6, retried.size());
        assertEquals("DQ_CHECKED", retried.get(4).get("stage"));
        assertEquals(1, retried.get(4).get("attempt"));
        assertEquals("DQ_CHECKED", retried.get(5).get("stage"));
        assertEquals(2, retried.get(5).get("attempt"));
        assertEquals("INGESTED", retried.get(2).get("stage"));
    }
}
