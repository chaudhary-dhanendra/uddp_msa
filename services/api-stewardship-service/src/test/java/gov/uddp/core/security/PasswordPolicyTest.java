package gov.uddp.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy(new SecurityProperties());

    @Test
    void acceptsALongMixedPassword() {
        assertEquals(java.util.List.of(), policy.violations("anita", "Correct-Horse-7-Battery"));
    }

    @Test
    void namesEveryBrokenRule() {
        assertTrue(policy.violations("anita", "short").contains("Use at least 12 characters"));
        assertTrue(policy.violations("anita", "alllowercase-1x").contains("Include an upper-case letter"));
        assertTrue(policy.violations("anita", "ALLUPPERCASE-1X").contains("Include a lower-case letter"));
        assertTrue(policy.violations("anita", "No-Digits-Here!").contains("Include a digit"));
        assertTrue(policy.violations("anita", "NoSymbolsHere123").contains("Include a symbol"));
        assertTrue(policy.violations("anita", "My-anita-Pass-99").contains("Do not include the username"));
        assertTrue(policy.violations("someone", "Password123!").contains("Choose a less common password"));
        assertTrue(policy.violations("someone", "").contains("Enter a password"));
        assertTrue(policy.violations("someone", "A1-" + "x".repeat(200)).contains("Use at most 128 characters"));
    }

    @Test
    void generatedPasswordsMeetThePolicyAndDiffer() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String password = policy.generate();
            assertEquals(java.util.List.of(), policy.violations("platform.admin", password), password);
            assertTrue(seen.add(password));
        }
        assertNotEquals(policy.generate(), policy.generate());
    }

    @Test
    void theMinimumLengthIsConfigurableButNeverBelowEight() {
        var properties = new SecurityProperties();
        properties.getPassword().setMinLength(4);
        assertEquals(8, new PasswordPolicy(properties).minLength());
        properties.getPassword().setMinLength(16);
        assertTrue(new PasswordPolicy(properties).violations("x", "Abcdefgh-12345").contains("Use at least 16 characters"));
    }
}
