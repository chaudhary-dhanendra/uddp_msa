package gov.uddp.core.identity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One identifier as canonical data keeps it (Track E2): its type, subject and scope, the issuer of an ISSUER-scoped
 * value, the storage mode and the stored value (a keyed hash, a vault reference or, for PLAIN types, the normalised
 * value), and the last four characters when the type allows them.
 */
public record ProtectedIdentifier(String type, IdentifierType.Subject subject, IdentifierType.Scope scope,
                                  String issuer, IdentifierType.Storage storage, String value, String last4) {
    /** The replayable form written into a redacted raw payload: {@code uddp-id:TYPE:ISSUER:last4:value}. */
    public String token() {
        return IdentifierProtection.TOKEN_PREFIX + type + ":" + (issuer == null ? "" : issuer) + ":"
                + (last4 == null ? "" : last4) + ":" + value;
    }

    /** The entry of the {@code uddp.identifiers} extension. */
    public Map<String, Object> extensionEntry() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("subject", subject.name());
        entry.put("scope", scope.name());
        if (issuer != null) {
            entry.put("issuer", issuer);
        }
        entry.put("storage", storage.name());
        entry.put("value", value);
        if (last4 != null) {
            entry.put("last4", last4);
        }
        return entry;
    }
}
