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
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The edge's HTTP client for what ingestion-service owns (MSA): source connectors, landing folders and source tests.
 * Present only with {@code uddp.ingestion.remote=true} and {@code uddp.ingestion.url}; the edge's own connector and
 * landing-folder code then delegates here instead of reaching sources or credentials itself.
 */
@Component
@ConditionalOnExpression("${uddp.ingestion.remote:false} and '${uddp.ingestion.url:}' != ''")
public class IngestionClient {

    private final String base;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public IngestionClient(@Value("${uddp.ingestion.url}") String url) {
        this.base = url;
    }

    public Optional<JsonNode> get(String path) {
        var response = send(gov.uddp.contracts.ServiceUrls.request(at(path)).GET());
        return response.statusCode() == 404 ? Optional.empty() : Optional.of(body(response));
    }

    public JsonNode post(String path, Object body) {
        return body(send(gov.uddp.contracts.ServiceUrls.request(at(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body)))));
    }

    public JsonNode put(String path, Object body) {
        return body(send(gov.uddp.contracts.ServiceUrls.request(at(path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(write(body)))));
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot reach ingestion-service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling ingestion-service", e);
        }
    }

    /** The response body; a 4xx with an error message becomes IllegalArgumentException (the user's input). */
    private JsonNode body(HttpResponse<String> response) {
        try {
            JsonNode body = response.body() == null || response.body().isBlank() ? json.createObjectNode()
                    : json.readTree(response.body());
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                throw new IllegalArgumentException(body.path("error").asText("ingestion-service refused the request"));
            }
            if (response.statusCode() >= 500) {
                throw new IllegalStateException("ingestion-service failed: " + response.statusCode());
            }
            return body;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unreadable answer from ingestion-service", e);
        }
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The path at ingestion-service's address now (a registered instance when the address is lb://). */
    private URI at(String path) {
        return URI.create(gov.uddp.contracts.ServiceUrls.resolve(base) + "/" + path);
    }
}
