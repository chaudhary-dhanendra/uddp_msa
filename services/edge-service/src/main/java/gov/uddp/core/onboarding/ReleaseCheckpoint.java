package gov.uddp.core.onboarding;

import java.util.UUID;

/** Decides, before a run's Golden build, whether the run may continue (Track C3). */
public interface ReleaseCheckpoint {
    /** True when the run was paused for a human decision; false when it may proceed. */
    boolean pauses(UUID runId);
}
