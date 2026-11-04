package gov.uddp.core.identity;

/**
 * Keeps the clear value of a {@code VAULT_REF} identifier outside canonical data and returns an opaque reference
 * (Track E2). The same value of the same type and scope always gets the same reference.
 */
public interface IdentifierVault {
    /** Stores the value under a reference derived from {@code referenceKey} and returns the reference. */
    String store(IdentifierType type, String referenceKey, String normalised);
}
