package gov.uddp.core.privacy;

import gov.uddp.core.setup.LocalSettingsStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * National IDs (Aadhaar and similar) are never kept in clear (P1 Part 5b): a mapped value becomes a keyed hash
 * (HMAC-SHA256 of type and digits) plus its last four digits. The key comes from {@code UDDP_NATIONAL_ID_KEY}
 * (base64) or else from the encrypted local settings store; it is never stored in the database. The same ID gives
 * the same hash in every state, so matching can compare it exactly. A raw payload redacted after processing holds
 * the token form ({@code uddp-nid:TYPE:last4:hash}), which maps back to the same value.
 */
@Component
public class NationalIdProtector {
    public static final String EXTENSION = "uddp.nationalIds";
    private static final Pattern TOKEN = Pattern.compile("uddp-nid:([A-Z0-9_]+):(\\d{4}):([0-9a-f]{64})");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A protected national ID: the type, the keyed hash and the last four digits for display. */
    public record Protected(String type, String keyedHash, String last4) {
        public String token() {
            return "uddp-nid:" + type + ":" + last4 + ":" + keyedHash;
        }
    }

    private final byte[] key;

    public NationalIdProtector(@Value("${UDDP_NATIONAL_ID_KEY:}") String environmentKey, LocalSettingsStore store) {
        String encoded = environmentKey != null && !environmentKey.isBlank() ? environmentKey.strip()
                : store.secret("nationalIdKey", NationalIdProtector::newKey);
        this.key = Base64.getDecoder().decode(encoded);
    }

    /** A random 256-bit key, base64. */
    static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** HMAC-SHA256 (hex) of a scope key such as {@code TYPE:value}; the identifier registry builds the key (Track E2). */
    public String keyedHash(String scopeKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(scopeKey.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot hash the identifier", e);
        }
    }

    public Protected protect(String type, String value) {
        String idType = type.strip().toUpperCase(Locale.ROOT);
        Matcher token = TOKEN.matcher(value.strip());
        if (token.matches()) {
            return new Protected(token.group(1), token.group(3), token.group(2));
        }
        String digits = value.replaceAll("[\\s-]", "");
        if (!digits.matches("[0-9A-Za-z]{4,32}")) {
            throw new IllegalArgumentException("A " + idType + " value is not a valid identifier");
        }
        String normalized = digits.toUpperCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal((idType + ":" + normalized).getBytes(StandardCharsets.UTF_8));
            return new Protected(idType, HexFormat.of().formatHex(hash), normalized.substring(normalized.length() - 4));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot hash the national ID", e);
        }
    }
}
