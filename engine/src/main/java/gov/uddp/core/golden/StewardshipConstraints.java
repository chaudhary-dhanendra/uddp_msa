package gov.uddp.core.golden;

import gov.uddp.core.golden.GoldenTargetGroups.LinkConstraint;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * Stewardship's part in a Golden build (M13, ADR-0010), inside the build's transaction: the effective link
 * constraints that shape the groups, and the review tasks the build's matching run calls for.
 */
public interface StewardshipConstraints {
    /** Effective constraints (the latest decision per source-record-key pair), in decision order. */
    List<LinkConstraint> effective(Connection c) throws Exception;

    /** Creates review tasks for the run's POSSIBLE_MATCH pairs that have none yet; returns how many. */
    int createReviewTasks(Connection c, UUID matchingRun, UUID build) throws Exception;
}
