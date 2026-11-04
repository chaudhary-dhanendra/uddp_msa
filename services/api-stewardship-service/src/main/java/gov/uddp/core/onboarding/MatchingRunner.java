package gov.uddp.core.onboarding;

import java.util.UUID;

/**
 * The onboarding MATCHED stage's boundary to identity resolution. identity-service runs the Splink adapter
 * in-process (the default); {@code RemoteMatchingRunner} asks identity-service over Kafka (MSA Phase 3). Either way the
 * returned run is COMPLETED in this database before the Golden build reads it.
 */
public interface MatchingRunner {

    /** A full matching run over every latest canonical Resident. */
    UUID run();

    /** An incremental run: only pairs touching new or changed canonical Residents (ADR-0004). */
    UUID runIncremental();
}
