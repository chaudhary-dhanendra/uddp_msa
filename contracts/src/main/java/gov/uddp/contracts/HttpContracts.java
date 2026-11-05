package gov.uddp.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The HTTP contracts between the services (src/main/resources/http-contracts/&lt;provider&gt;.json): for each call a
 * consumer makes, the request and what the answer must hold. Providers verify them against their running API; the
 * consumers' clients are tested against the contracts' examples, so both sides are held to one file.
 */
public final class HttpContracts {

    /** One call a consumer makes. */
    public record Interaction(String name, List<String> consumers, String method, String path, JsonNode body,
                              String contentType, String rawBody, int status, List<String> fields,
                              List<String> forbidden, JsonNode example) {

        /** The path with the provider state's values substituted ({key} placeholders). */
        public String path(Map<String, String> values) {
            return substitute(path, values);
        }

        /** The JSON body with the provider state's values substituted. */
        public String body(Map<String, String> values) {
            return body == null || body.isNull() ? null : substitute(body.toString(), values);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpContracts() {
    }

    public static List<Interaction> of(String provider) {
        try (InputStream in = HttpContracts.class.getResourceAsStream("/http-contracts/" + provider + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("No HTTP contract for " + provider);
            }
            List<Interaction> out = new ArrayList<>();
            for (JsonNode i : JSON.readTree(in).path("interactions")) {
                JsonNode request = i.path("request");
                JsonNode response = i.path("response");
                out.add(new Interaction(i.path("name").asText(), strings(i.path("consumers")),
                        request.path("method").asText(), request.path("path").asText(), request.get("body"),
                        request.path("contentType").asText("application/json"), request.path("rawBody").asText(null),
                        response.path("status").asInt(), strings(response.path("fields")),
                        strings(response.path("forbidden")), response.get("example")));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Interaction find(String provider, String name) {
        return of(provider).stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No interaction " + name + " for " + provider));
    }

    /**
     * The problems of an answer against the interaction: missing required fields ({@code a.b}, {@code [].a} for every
     * array element) and forbidden ones. Empty when the answer meets the contract.
     */
    public static List<String> problems(Interaction interaction, int status, JsonNode body) {
        List<String> problems = new ArrayList<>();
        if (status != interaction.status()) {
            problems.add("status " + status + ", the contract says " + interaction.status());
        }
        for (String field : interaction.fields()) {
            if (!present(body, field)) {
                problems.add("missing " + field);
            }
        }
        for (String field : interaction.forbidden()) {
            if (present(body, field)) {
                problems.add("must not answer " + field);
            }
        }
        return problems;
    }

    private static boolean present(JsonNode node, String path) {
        if (path.startsWith("[].")) {
            if (node == null || !node.isArray() || node.isEmpty()) {
                return false; // an empty list proves nothing about its elements
            }
            for (JsonNode element : node) {
                if (!present(element, path.substring(3))) {
                    return false;
                }
            }
            return true;
        }
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            if (current == null || !current.has(part)) {
                return false;
            }
            current = current.get(part);
        }
        return true;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String substitute(String text, Map<String, String> values) {
        String out = text;
        for (var e : values.entrySet()) {
            String value = e.getValue();
            if (value.startsWith("{") || value.startsWith("[")) {
                // A JSON value replaces its quoted placeholder ("{files}") whole.
                out = out.replace("\"{" + e.getKey() + "}\"", value);
            }
            out = out.replace("{" + e.getKey() + "}", value);
        }
        return out;
    }
}
