package gov.uddp.core.canonical;

import java.util.Optional;
import java.util.UUID;

public interface CanonicalResidentRepository {
    boolean insert(CanonicalResidentRecord record);
    Optional<CanonicalResidentRecord> findById(UUID canonicalResidentId);
    boolean exists(UUID rawRecordId, String statePackVersion);
    void recordFailure(CanonicalProcessingError error);
    void recordValidationWarning(CanonicalValidationWarning warning);

    /** The latest canonical version of a source record (M11); stores without versioning have none. */
    default Optional<LatestCanonicalVersion> latestVersion(String sourceRecordKey) {
        return Optional.empty();
    }

    /**
     * Inserts the canonical row and its version row together (M11); {@code supersedes} is the previous latest
     * version, or {@code null} for version 1. Stores without versioning just insert the row.
     */
    default boolean insertVersion(CanonicalResidentRecord record, String sourceRecordKey, int versionNumber,
                                  UUID supersedes) {
        return insert(record);
    }

    /** A source record's latest canonical version. */
    record LatestCanonicalVersion(UUID canonicalResidentId, int versionNumber, String statePackVersion,
                                  String canonicalPayload) {
    }
}
