package gov.uddp.core.security;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The local password policy (ADR-0017): at least {@code uddp.security.password.min-length} characters (12 by
 * default, at most 128) with upper- and lower-case letters, a digit and a symbol; not containing the username; not a
 * well-known password. Also generates one-time passwords that satisfy it.
 */
@Component
public class PasswordPolicy {
    static final int MAX_LENGTH = 128;
    private static final Set<String> COMMON = Set.of("password", "password1", "password123", "passw0rd", "welcome",
            "welcome1", "letmein", "qwerty", "qwerty123", "admin", "admin123", "administrator", "changeme", "iloveyou",
            "monkey", "dragon", "123456", "12345678", "123456789", "1234567890", "abc123", "demo", "uddp");
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%^&*-_=+?";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int minLength;

    public PasswordPolicy(SecurityProperties properties) {
        this.minLength = Math.max(8, properties.getPassword().getMinLength());
    }

    public int minLength() {
        return minLength;
    }

    /** The rules the password breaks, in plain words; empty when it is acceptable. */
    public List<String> violations(String username, String password) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            problems.add("Enter a password");
            return problems;
        }
        if (password.length() < minLength) {
            problems.add("Use at least " + minLength + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            problems.add("Use at most " + MAX_LENGTH + " characters");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            problems.add("Include an upper-case letter");
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            problems.add("Include a lower-case letter");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            problems.add("Include a digit");
        }
        if (password.chars().allMatch(Character::isLetterOrDigit)) {
            problems.add("Include a symbol");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
            problems.add("Do not include the username");
        }
        String core = lower.replaceAll("[^a-z0-9]", "");
        if (COMMON.contains(lower) || COMMON.contains(core) || COMMON.contains(core.replaceAll("\\d+$", ""))) {
            problems.add("Choose a less common password");
        }
        return problems;
    }

    public void require(String username, String password) {
        List<String> problems = violations(username, password);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The password does not meet the policy: " + String.join("; ", problems));
        }
    }

    /** A random one-time password of 20 characters that meets the policy. */
    public String generate() {
        List<Character> characters = new ArrayList<>();
        characters.add(pick(UPPER));
        characters.add(pick(LOWER));
        characters.add(pick(DIGITS));
        characters.add(pick(SYMBOLS));
        String all = UPPER + LOWER + DIGITS + SYMBOLS;
        while (characters.size() < Math.max(20, minLength)) {
            characters.add(pick(all));
        }
        Collections.shuffle(characters, RANDOM);
        StringBuilder password = new StringBuilder();
        characters.forEach(password::append);
        return password.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }
}
