package gov.uddp.core.golden;

import java.util.UUID;

/** Published after a Golden build has committed; listeners must not fail the build. */
public record GoldenBuildCompletedEvent(UUID matchingRunId, GoldenBuildSummary summary) {
}
