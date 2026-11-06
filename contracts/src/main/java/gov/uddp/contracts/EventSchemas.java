package gov.uddp.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates envelopes and payloads against the JSON Schemas in {@code events/}. Producers validate before writing
 * the outbox; consumer contract tests validate sample payloads.
 */
public final class EventSchemas {

    public static final String ENVELOPE = "envelope.v1";

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final ConcurrentHashMap<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private EventSchemas() {
    }

    /** Validation messages for {@code document} against {@code events/<name>.schema.json}; empty when valid. */
    public static Set<ValidationMessage> validate(String name, JsonNode document) {
        return CACHE.computeIfAbsent(name, EventSchemas::load).validate(document);
    }

    private static final ConcurrentHashMap<String, Boolean> EXISTS = new ConcurrentHashMap<>();

    /** Whether {@code events/<name>.schema.json} exists (looked up once per name: it was a file-system check per event). */
    public static boolean exists(String name) {
        return EXISTS.computeIfAbsent(name,
                n -> EventSchemas.class.getResource("/events/" + n + ".schema.json") != null);
    }

    private static JsonSchema load(String name) {
        String resource = "/events/" + name + ".schema.json";
        try (InputStream in = EventSchemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No event schema " + resource);
            }
            return FACTORY.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
