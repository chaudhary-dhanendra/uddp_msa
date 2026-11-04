package gov.uddp.core.onboarding;

import gov.uddp.core.golden.GoldenBuildSummary;
import java.util.UUID;

/**
 * The boundary to the Golden build used by the GOLDEN stage, stewardship decisions and the build API.
 * {@link gov.uddp.core.golden.GoldenResidentService} builds in-process (the default); {@code RemoteGoldenBuilder} asks
 * registry-service and waits for its Golden replica (MSA Phase 3).
 */
public interface GoldenBuildRunner {

    GoldenBuildSummary build(UUID matchingRunId);
}
