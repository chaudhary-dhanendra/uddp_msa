package gov.uddp.core.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * A provider-neutral HTTP adapter: POSTs {@code {"model","instruction","content"}} to the configured URL and reads
 * {@code {"text"}} back. A gateway in front of the actual model translates this contract; the platform names no vendor.
 */
public class HttpAiDraftService implements AiDraftService {
    private final AiProperties properties;
    private final ObjectMapper json;
    private final HttpClient client;

    public HttpAiDraftService(AiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public String complete(String instruction, String content) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", properties.getModel(),
                    "instruction", instruction,
                    "content", content));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.getProviderUrl()))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            String key = properties.getApiKeyEnv() == null ? null : System.getenv(properties.getApiKeyEnv());
            if (key != null && !key.isBlank()) {
                request.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("The AI provider answered HTTP " + response.statusCode());
            }
            return json.readTree(response.body()).path("text").asText("");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The AI provider call was interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("The AI provider could not be reached", e);
        }
    }
}
