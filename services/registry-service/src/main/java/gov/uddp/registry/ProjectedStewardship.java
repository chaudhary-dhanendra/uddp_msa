package gov.uddp.registry;

import gov.uddp.core.golden.GoldenTargetGroups;
import gov.uddp.core.golden.StewardshipConstraints;
import gov.uddp.core.stewardship.StewardshipStore;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * Stewardship's part in a Golden build inside registry-service: the effective link constraints come from the
 * projection of stewardship.decision-recorded (read exactly as the monolith reads them), and no review task is
 * created here: edge-service creates them from identity's POSSIBLE_MATCH decisions.
 */
public class ProjectedStewardship implements StewardshipConstraints {

    private final StewardshipStore store = new StewardshipStore();

    @Override
    public List<GoldenTargetGroups.LinkConstraint> effective(Connection c) throws Exception {
        return store.effective(c);
    }

    @Override
    public int createReviewTasks(Connection c, UUID matchingRun, UUID build) {
        return 0;
    }
}
