package gov.uddp.core.identity;

import gov.uddp.core.privacy.NationalIdProtector;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validates, normalises and protects one mapped identifier value according to its registry type (Track E2):
 * KEYED_HASH keeps an HMAC of the scope key, VAULT_REF a vault reference, PLAIN the normalised value. A rejected
 * value raises an error that names the type and rule, never the value. A raw payload redacted after processing
 * holds {@link ProtectedIdentifier#token()}, which protects back to the same identifier (replay); the P1 form
 * {@code uddp-nid:TYPE:last4:hash} is still accepted.
 */
@Component
public class IdentifierProtection {
    public static final String EXTENSION = "uddp.identifiers";
    public static final String TOKEN_PREFIX = "uddp-id:";
    /** Replaces a raw value that failed validation under {@code onInvalid: flag}; it replays as INVALID again. */
    public static final String INVALID_TOKEN_PREFIX = "uddp-id-invalid:";
    private static final Pattern TOKEN = Pattern.compile("uddp-id:([A-Z0-9_]+):([A-Z0-9_-]*):([0-9A-Z]{0,4}):(\\S+)");
    private static final Pattern LEGACY_TOKEN = Pattern.compile("uddp-nid:([A-Z0-9_]+):(\\d{4}):([0-9a-f]{64})");

    private final IdentifierTypeRegistry registry;
    private final NationalIdProtector hashes;
    private IdentifierVault vault;

    public IdentifierProtection(IdentifierTypeRegistry registry, NationalIdProtector hashes) {
        this.registry = registry;
        this.hashes = hashes;
    }

    /** Protection over the platform seed file's types only (callers without the registry bean). */
    public static IdentifierProtection withSeed(NationalIdProtector hashes) {
        return new IdentifierProtection(IdentifierTypeRegistry.fromSeed(defaultSeed()), hashes);
    }

    static Path defaultSeed() {
        return Path.of(System.getProperty("uddp.platform-directory", "../platform"), "identity", "identifier-types.yaml");
    }

    @Autowired(required = false)
    public void setVault(IdentifierVault vault) {
        this.vault = vault;
    }

    public IdentifierTypeRegistry registry() {
        return registry;
    }

    public static boolean isToken(String value) {
        return value != null && (value.startsWith(TOKEN_PREFIX) || value.startsWith(INVALID_TOKEN_PREFIX)
                || value.startsWith("uddp-nid:"));
    }

    /**
     * Protects {@code value} as an identifier of {@code typeCode}; {@code issuer} is used for ISSUER-scoped types.
     *
     * @throws IllegalArgumentException for an unknown type or a value its type rejects (the value is never named)
     */
    public ProtectedIdentifier protect(String typeCode, String issuer, String value) {
        IdentifierType type = registry.require(typeCode);
        String scopeIssuer = type.scope() == IdentifierType.Scope.ISSUER
                ? (issuer == null ? null : issuer.strip().toUpperCase(Locale.ROOT)) : null;
        String stripped = value == null ? "" : value.strip();
        if (stripped.equals(INVALID_TOKEN_PREFIX + type.code())) {
            throw new IllegalArgumentException(type.code() + " value was flagged invalid when it was first read");
        }
        Matcher token = TOKEN.matcher(stripped);
        if (token.matches() && token.group(1).equals(type.code())) {
            return new ProtectedIdentifier(type.code(), type.subject(), type.scope(),
                    token.group(2).isEmpty() ? null : token.group(2), type.storage(), token.group(4),
                    token.group(3).isEmpty() ? null : token.group(3));
        }
        Matcher legacy = LEGACY_TOKEN.matcher(stripped);
        if (legacy.matches() && legacy.group(1).equals(type.code())) {
            return new ProtectedIdentifier(type.code(), type.subject(), type.scope(), scopeIssuer, type.storage(),
                    legacy.group(3), type.displayLast4() ? legacy.group(2) : null);
        }
        var check = registry.check(type, stripped);
        if (!check.valid()) {
            throw new IllegalArgumentException(check.reason());
        }
        String normalised = check.normalised();
        String scopeKey = registry.scopeKey(type, scopeIssuer, normalised);
        String stored = switch (type.storage()) {
            case KEYED_HASH -> hashes.keyedHash(scopeKey);
            case VAULT_REF -> {
                if (vault == null) {
                    throw new IllegalStateException(type.code() + " is stored in the vault, but no vault is configured");
                }
                yield vault.store(type, scopeKey, normalised);
            }
            case PLAIN -> normalised;
        };
        String last4 = type.displayLast4() && normalised.length() >= 4
                ? normalised.substring(normalised.length() - 4) : null;
        return new ProtectedIdentifier(type.code(), type.subject(), type.scope(), scopeIssuer, type.storage(), stored,
                last4);
    }
}
