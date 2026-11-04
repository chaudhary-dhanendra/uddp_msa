package gov.uddp.core.identity;

/**
 * A check-digit algorithm an identifier type can name in {@code validation.checksum}. Implementations are Spring
 * beans, so a deployment adds an algorithm by adding a bean; the registry looks them up by {@link #name()}.
 */
public interface ChecksumValidator {
    /** The name a type configures, in capitals (for example {@code VERHOEFF}). */
    String name();

    /** True when the normalised value's check character is right. */
    boolean valid(String normalised);
}
