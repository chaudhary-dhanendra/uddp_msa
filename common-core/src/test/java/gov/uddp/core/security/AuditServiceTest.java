package gov.uddp.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditServiceTest {
    @Test
    void redactsAuthenticationAndConnectionSecretsByKeyName() {
        Map<String, Object> metadata = Map.ofEntries(
                Map.entry("password", "password-value"),
                Map.entry("passwordHash", "password-hash"),
                Map.entry("Authorization", "Bearer authorization-value"),
                Map.entry("cookie", "cookie-value"),
                Map.entry("sessionId", "session-value"),
                Map.entry("csrfToken", "csrf-value"),
                Map.entry("XSRF-TOKEN", "xsrf-value"),
                Map.entry("token", "token-value"),
                Map.entry("accessToken", "access-token-value"),
                Map.entry("refresh_token", "refresh-token-value"),
                Map.entry("idToken", "id-token-value"),
                Map.entry("clientSecret", "client-secret-value"),
                Map.entry("databasePassword", "database-password-value"),
                Map.entry("source_password", "source-password-value"),
                Map.entry("encryption key", "encryption-key-value"),
                Map.entry("nested", Map.of("auth_header", "nested-header-value")));

        Map<String, Object> safe = AuditService.redact(metadata, List.of("known-secret-value"));

        metadata.keySet().stream().filter(key -> !key.equals("nested"))
                .forEach(key -> assertEquals(SecretRedactor.MASK, safe.get(key), key));
        assertEquals(SecretRedactor.MASK, ((Map<?, ?>) safe.get("nested")).get("auth_header"));
        assertFalse(safe.toString().contains("value"));
    }

    @Test
    void redactsKnownSecretValuesEvenUnderAnOrdinaryKey() {
        Map<String, Object> safe = AuditService.redact(Map.of("message", "connection known-secret-value failed"),
                List.of("known-secret-value"));

        assertEquals("connection ****** failed", safe.get("message"));
    }
}
