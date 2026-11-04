package gov.uddp.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The Studio's publications in statepack-service (MSA), the system of record for State Pack versions: a submitted
 * version is published there by its maker, and the checker's decision is recorded there as approve (then activate) or
 * reject. statepack-service enforces that the checker is not the maker and announces the activation on Kafka. On only
 * with {@code uddp.statepack.url}.
 */
@Component
@ConditionalOnExpression("'${uddp.statepack.url:}' != ''")
public class StatePackPublisher {

    /** The checker recorded for a version published without a human checker (maker-checker off or auto-approved). */
    public static final String AUTO_CHECKER = "platform-auto-approval";

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public StatePackPublisher(@Value("${uddp.statepack.url}") String url) {
        this.base = URI.create(url.replaceAll("/+$", "") + "/api/statepacks");
    }

    /** Publishes the files as a PENDING_APPROVAL version (the same content again is accepted as it is). */
    public JsonNode publish(String maker, Map<String, String> files) {
        return post("", Map.of("maker", maker, "files", files));
    }

    /** Approves and activates the version; the previously ACTIVE version of the pack is retired. */
    public JsonNode approveAndActivate(String packId, String version, String checker) {
        JsonNode current = get(packId, version);
        if ("PENDING_APPROVAL".equals(current.path("status").asText())) {
            post(path(packId, version) + "/approve", Map.of("checker", checker));
            current = get(packId, version);
        }
        if ("APPROVED".equals(current.path("status").asText())) {
            current = post(path(packId, version) + "/activate", Map.of("actor", checker));
        }
        return current;
    }

    public JsonNode reject(String packId, String version, String checker, String reason) {
        return post(path(packId, version) + "/reject", Map.of("checker", checker, "reason", reason));
    }

    /** Publishes and activates at once, for a version that needs no human checker. */
    public JsonNode publishActive(String maker, Map<String, String> files) {
        JsonNode published = publish(maker, files);
        String checker = AUTO_CHECKER.equalsIgnoreCase(maker) ? AUTO_CHECKER + "-2" : AUTO_CHECKER;
        return approveAndActivate(published.path("packId").asText(), published.path("version").asText(), checker);
    }

    private static String path(String packId, String version) {
        return "/" + URLEncoder.encode(packId, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(version, StandardCharsets.UTF_8);
    }

    private JsonNode get(String packId, String version) {
        return send(HttpRequest.newBuilder(URI.create(base + path(packId, version))).GET());
    }

    private JsonNode post(String suffix, Object body) {
        try {
            return send(HttpRequest.newBuilder(URI.create(base + suffix)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot write the statepack-service request", e);
        }
    }

    private JsonNode send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofMinutes(2)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                String message = response.body();
                try {
                    message = json.readTree(response.body()).path("error").asText(response.body());
                } catch (Exception ignored) {
                    // plain text answer
                }
                throw new IllegalStateException("statepack-service refused the request: " + message);
            }
            return json.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot reach statepack-service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling statepack-service", e);
        }
    }
}
