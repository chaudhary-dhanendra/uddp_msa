package gov.uddp.core.onboarding;

import java.util.UUID;

/** A stage ran and failed; the failure is persisted on the stage attempt and the run. */
public class OnboardingStageFailedException extends IllegalStateException {
    private final UUID runId;
    private final OnboardingStage stage;

    public OnboardingStageFailedException(UUID runId, OnboardingStage stage, String message, Throwable cause) {
        super(message, cause);
        this.runId = runId;
        this.stage = stage;
    }

    public UUID runId() {
        return runId;
    }

    public OnboardingStage stage() {
        return stage;
    }
}
