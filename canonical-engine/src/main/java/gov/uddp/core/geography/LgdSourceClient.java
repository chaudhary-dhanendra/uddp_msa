package gov.uddp.core.geography;

import java.util.Optional;

/**
 * The adapter boundary to wherever LGD data comes from (Track F1). Common Core never embeds geography: a client
 * fetches a whole snapshot from a configured location, and {@link LgdImportService} decides what changed.
 */
public interface LgdSourceClient {
    /** Short description of the configured source, for run history. */
    String describe();

    /** The current snapshot, or empty when no source is configured. Throws when the source cannot be read. */
    Optional<LgdSnapshotData> fetch();
}
