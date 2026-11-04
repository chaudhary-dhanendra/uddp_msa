package gov.uddp.core.matching;

import java.util.UUID;

/**
 * PostgreSQL orders uuid values by their bytes (unsigned), but {@link UUID#compareTo} compares the two halves as
 * signed longs, so the two disagree whenever a half has its top bit set. Scored pairs are stored with
 * {@code canonical_record_a < canonical_record_b} in database order; any Java code that orders a pair before looking
 * it up must use this comparison, or roughly half the pairs are looked up the wrong way round and "not found".
 */
public final class UuidOrder {
    private UuidOrder() {
    }

    /** Compares the way PostgreSQL does: unsigned, most significant half first. */
    public static int compare(UUID left, UUID right) {
        int most = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return most != 0 ? most : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    /** The lower of the two in database order. */
    public static UUID first(UUID a, UUID b) {
        return compare(a, b) <= 0 ? a : b;
    }

    /** The higher of the two in database order. */
    public static UUID second(UUID a, UUID b) {
        return compare(a, b) <= 0 ? b : a;
    }
}
