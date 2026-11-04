package gov.uddp.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes the append-only audit trail ({@code app_audit_event}, ADR-0020): actor, time, action, resource, state,
 * result and metadata. Metadata is redacted before it is stored: values under a key that names a secret are
 * replaced, and every string is passed through {@link SecretRedactor} with the platform's known secrets.
 */
@Service
public class AuditService {
    public static final String SUCCESS = "SUCCESS";
    public static final String DENIED = "DENIED";
    public static final String FAILURE = "FAILURE";

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);
    /**
     * Case-insensitive, deliberately broad classification for metadata keys. This keeps authentication material out
     * of the audit database even where callers use camelCase, snake_case, or a vendor-specific prefix.
     */
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|secret|token|credential|passphrase"
            + "|apikey|api_key|private|authorization|auth[_-]?header|session|cookie|csrf|xsrf|jwt|oidc"
            + "|encryption[ _-]?key).*");
    private static final int MAX_TEXT = 500;

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;

    public AuditService(PostgresConnectionFactory connections, ObjectMapper json) {
        this.connections = connections;
        this.json = json;
    }

    /** One event to record; {@code actor} null means the current user (or the platform outside a request). */
    public record AuditEvent(String actor, String action, String resourceType, String resourceId, String stateCode,
                             String result, Map<String, Object> metadata) {
        public static AuditEvent of(String action, String resourceType, String resourceId, String stateCode,
                                    String result) {
            return new AuditEvent(null, action, resourceType, resourceId, stateCode, result, Map.of());
        }

        public AuditEvent by(String username) {
            return new AuditEvent(username, action, resourceType, resourceId, stateCode, result, metadata);
        }

        public AuditEvent with(Map<String, Object> details) {
            Map<String, Object> merged = new LinkedHashMap<>(metadata);
            merged.putAll(details);
            return new AuditEvent(actor, action, resourceType, resourceId, stateCode, result, merged);
        }
    }

    public void record(String action, String resourceType, String resourceId, String stateCode, String result) {
        record(AuditEvent.of(action, resourceType, resourceId, stateCode, result));
    }

    /**
     * Stores the event. An audit failure is never silent: it is logged and rethrown, so a write whose audit record
     * cannot be stored fails rather than going unrecorded. In setup mode there is no platform database yet, so
     * nothing can be stored; the first stored event is the setup that configures it.
     */
    public void record(AuditEvent event) {
        if (!connections.isConfigured()) {
            LOG.info("Audit event {} not stored: no platform database is configured yet", event.action());
            return;
        }
        String actor = event.actor() != null ? event.actor() : CurrentUser.username();
        if (actor == null) {
            actor = "platform";
        }
        try (var c = connections.open();
                var s = c.prepareStatement("insert into app_audit_event(event_id, actor_user_id, actor_username,"
                        + " action, resource_type, resource_id, state_code, result, metadata)"
                        + " values (?, (select user_id from app_user where lower(username) = lower(?)), ?, ?, ?, ?, ?, ?,"
                        + " cast(? as jsonb))")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, actor);
            s.setString(3, truncate(actor));
            s.setString(4, event.action());
            s.setString(5, event.resourceType());
            s.setString(6, truncate(event.resourceId()));
            s.setString(7, event.stateCode() == null ? null : event.stateCode().toUpperCase(Locale.ROOT));
            s.setString(8, event.result());
            s.setString(9, json.writeValueAsString(redact(event.metadata(), connections.secrets())));
            s.executeUpdate();
        } catch (Exception e) {
            LOG.error("Cannot persist audit event {} {}", event.action(), event.result());
            throw new IllegalStateException("Cannot persist audit event", e);
        }
    }

    /** Metadata safe to store: secret-named keys replaced, known secret values removed, long text shortened. */
    static Map<String, Object> redact(Map<String, Object> metadata, Collection<String> secrets) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (metadata == null) {
            return safe;
        }
        metadata.forEach((key, value) -> safe.put(key, redactValue(key, value, secrets)));
        return safe;
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(String key, Object value, Collection<String> secrets) {
        if (key != null && SECRET_KEY.matcher(key).matches()) {
            return SecretRedactor.MASK;
        }
        if (value instanceof Map<?, ?> map) {
            return redact((Map<String, Object>) map, secrets);
        }
        if (value instanceof Collection<?> list) {
            return list.stream().map(item -> redactValue(null, item, secrets)).toList();
        }
        if (value instanceof String text) {
            return truncate(SecretRedactor.redact(text, secrets));
        }
        return value == null || value instanceof Number || value instanceof Boolean ? value : truncate(value.toString());
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "...";
    }

    /** Convenience for metadata maps with a few entries. */
    public static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }
}
