package gov.uddp.core.matching;

import gov.uddp.core.model.Resident;

/** Boundary for the future Python/Splink identity-resolution adapter. */
public interface MatchingService {
    // Intentionally no implementation or synthetic matching result in Milestone 1.
    void match(Resident candidate);
}
