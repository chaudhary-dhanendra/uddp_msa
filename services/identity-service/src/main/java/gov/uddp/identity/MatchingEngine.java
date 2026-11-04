package gov.uddp.identity;

import java.util.UUID;

/**
 * The MatchingService boundary inside identity-service: scores the current candidates and persists one completed
 * matching run (run, pairs, clusters) in the identity schema. The production engine is the Python Splink adapter.
 */
public interface MatchingEngine {

    /** Runs a FULL or INCREMENTAL match and returns the completed run's id. */
    UUID run(boolean incremental);
}
