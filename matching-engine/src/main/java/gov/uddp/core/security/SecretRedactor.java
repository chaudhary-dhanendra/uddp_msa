package gov.uddp.core.security;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from text that is stored or returned (process output, failure messages). It replaces every
 * occurrence of each known secret value and any {@code password=...}, {@code password: ...} or
 * {@code -D..._PASSWORD=...} assignment, whatever its value.
 */
public final class SecretRedactor {
    public static final String MASK = "******";

    private static final Pattern PASSWORD_ASSIGNMENT = Pattern.compile(
            "(?i)([A-Za-z0-9_.-]*password[A-Za-z0-9_.-]*\"?\\s*[=:]\\s*\"?)([^\\s\"&;,]+)");

    private SecretRedactor() {
    }

    public static String redact(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                result = result.replace(secret, MASK);
            }
        }
        Matcher matcher = PASSWORD_ASSIGNMENT.matcher(result);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + MASK));
    }
}
