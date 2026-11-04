package gov.uddp.core.gates;

import gov.uddp.core.statepack.LoadedStatePack;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** A change to a pack's matching or survivorship configuration always needs a human approver. */
@Component
public class MatchingChangeDetector implements SensitiveChangeDetector {
    @Override
    public Optional<String> sensitiveChange(LoadedStatePack previous, LoadedStatePack next) {
        if (!previous.matching().equals(next.matching())) {
            return Optional.of("matching configuration changed");
        }
        if (!previous.survivorship().equals(next.survivorship())) {
            return Optional.of("survivorship configuration changed");
        }
        return Optional.empty();
    }
}
