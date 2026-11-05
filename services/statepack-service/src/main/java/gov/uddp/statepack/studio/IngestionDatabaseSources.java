package gov.uddp.statepack.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.contracts.ServiceUrls;
import gov.uddp.core.onboarding.DatabaseSources;
import gov.uddp.core.setup.LocalSettingsStore;
import gov.uddp.core.studio.StudioConflictException;
import gov.uddp.core.studio.StudioModels;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database sources for the Studio hosted here: credentials live only in ingestion-service
 * ({@code uddp.ingestion.connections.<name>.*}), so this service lists the connections configured there and reads
 * preview rows through it by connection name. It keeps no source password, and refuses to store one.
 */
public class IngestionDatabaseSources extends DatabaseSources {

    private final String ingestion;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public IngestionDatabaseSources(LocalSettingsStore store, String ingestion) {
        super(store);
        this.ingestion = ingestion;
    }

    @Override
    public List<StudioModels.SourceConnection> list() {
        List<StudioModels.SourceConnection> out = new ArrayList<>();
        for (JsonNode c : call("GET", "/api/ingestion/connections", null)) {
            out.add(new StudioModels.SourceConnection(c.path("name").asText(), c.path("host").asText(),
                    c.path("port").asInt(), c.path("database").asText(), c.path("user").asText(),
                    c.path("passwordSet").asBoolean()));
        }
        return out;
    }

    @Override
    public StudioModels.SourceConnection save(String name, StudioModels.SourceConnectionRequest request) {
        throw new StudioConflictException("Source connections and their passwords are configured in ingestion-service"
                + " (uddp.ingestion.connections." + name + ".*, password from its environment), not in the Studio");
    }

    @Override
    public Rows read(String connectionName, String table, String query, int limit) {
        requireName(connectionName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connectionRef", connectionName);
        body.put("table", table);
        body.put("query", query);
        body.put("limit", limit);
        JsonNode answer = call("POST", "/api/ingestion/sources/rows", body);
        List<String> header = new ArrayList<>();
        answer.path("header").forEach(h -> header.add(h.asText()));
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode row : answer.path("rows")) {
            Map<String, String> values = new LinkedHashMap<>();
            header.forEach(h -> values.put(h, row.path(h).asText("")));
            rows.add(values);
        }
        return new Rows(header, rows);
    }

    @Override
    public Map<String, String> hopVariables(String connectionName) {
        throw new UnsupportedOperationException("Database sources are ingested by ingestion-service");
    }

    private JsonNode call(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = ServiceUrls.request(URI.create(ServiceUrls.resolve(ingestion) + path))
                    .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                String message = response.body();
                try {
                    JsonNode error = json.readTree(response.body());
                    message = error.path("message").asText(error.path("error").asText(response.body()));
                } catch (Exception ignored) {
                    // plain text answer
                }
                throw new IllegalArgumentException("ingestion-service: " + message);
            }
            return json.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot reach ingestion-service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling ingestion-service", e);
        }
    }
}
