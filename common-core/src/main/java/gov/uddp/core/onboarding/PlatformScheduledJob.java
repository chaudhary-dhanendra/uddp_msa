package gov.uddp.core.onboarding;

import java.time.Instant;

/**
 * A platform-wide job driven by the C2 scheduler loop ({@link SchedulerRunner}) next to the per-source schedules, for
 * example the LGD master sync (Track F3). A job keeps its own schedule and must never run twice at once.
 */
public interface PlatformScheduledJob {
    String name();

    /** Starts the job when it is due at {@code now}; returns quickly when it is not. */
    void tick(Instant now);
}
