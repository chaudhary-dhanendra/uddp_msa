package gov.uddp.core.gates;

import gov.uddp.core.statepack.LoadedStatePack;
import java.util.Optional;

/**
 * Recognises a pack edit that must never be auto-approved, for example a change to matching or to scheme eligibility
 * (Track C3). Track A and Track B add detectors for their own configuration by implementing this interface.
 */
public interface SensitiveChangeDetector {
    /** A short reason when {@code next} changes something sensitive compared with {@code previous}. */
    Optional<String> sensitiveChange(LoadedStatePack previous, LoadedStatePack next);
}
