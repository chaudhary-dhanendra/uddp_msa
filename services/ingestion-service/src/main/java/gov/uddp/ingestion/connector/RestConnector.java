package gov.uddp.ingestion.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * REST API pull (Track C5). Settings: {@code url}, {@code recordsPath} (dot path to the JSON array, blank when the
 * response is the array), {@code pagination} (none, page or offset) with {@code pageParam}, {@code sizeParam},
 * {@code pageSize}, {@code firstPage} and {@code maxPages}, and an optional auth header: {@code authHeaderName}
 * (default Authorization) whose value is the credential {@code authValueRef} in the encrypted store.
 */
@Component
public class RestConnector implements SourceConnector {
    /** The HTTP boundary; replaced by a fixture in unit tests. */
    public interface Transport {
        String get(URI uri, Map<String, String> headers) throws IOException;
    }

    private final ObjectMapper json;
    private final Transport transport;

    @Autowired
    public RestConnector(ObjectMapper json) {
        this(json, new HttpTransport());
    }

    public RestConnector(ObjectMapper json, Transport transport) {
        this.json = json;
        this.transport = transport;
    }

    @Override
    public String type() {
        return "rest";
    }

    @Override
    public void test(Config config) {
        preview(config, 1);
    }

    @Override
    public Table preview(Config config, int limit) {
        return read(config, limit);
    }

    @Override
    public Delivery fetch(Config config) {
        return Delivery.of(read(config, Integer.MAX_VALUE));
    }

    private Table read(Config config, int limit) {
        String base = config.required("url");
        URI start = URI.create(base);
        if (start.getScheme() == null || !Set.of("http", "https").contains(start.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The URL must be http or https");
        }
        String mode = config.optional("pagination", "none").toLowerCase(Locale.ROOT);
        int pageSize = config.integer("pageSize", 100);
        int maxPages = config.integer("maxPages", 1000);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        config.secret("authValue").ifPresent(v -> headers.put(config.optional("authHeaderName", "Authorization"), v));
        List<JsonNode> records = new ArrayList<>();
        for (int page = 0; page < (mode.equals("none") ? 1 : maxPages) && records.size() < limit; page++) {
            URI uri = switch (mode) {
                case "none" -> start;
                case "page" -> withQuery(base, config.optional("pageParam", "page"), config.integer("firstPage", 1) + page,
                        config.optional("sizeParam", "size"), pageSize);
                case "offset" -> withQuery(base, config.optional("pageParam", "offset"), page * pageSize,
                        config.optional("sizeParam", "limit"), pageSize);
                default -> throw new IllegalArgumentException("pagination must be none, page or offset");
            };
            List<JsonNode> batch = records(get(uri, headers, config), config.optional("recordsPath", ""));
            records.addAll(batch);
            if (batch.isEmpty() || batch.size() < pageSize) {
                break;
            }
        }
        return table(records.subList(0, Math.min(limit, records.size())));
    }

    private JsonNode get(URI uri, Map<String, String> headers, Config config) {
        try {
            return json.readTree(transport.get(uri, headers));
        } catch (IOException e) {
            String secret = config.secret("authValue").orElse("");
            throw new IllegalArgumentException("Cannot read the REST source: "
                    + Redact.secrets(String.valueOf(e.getMessage()), secret.isEmpty() ? List.of() : List.of(secret)),
                    e);
        }
    }

    private static List<JsonNode> records(JsonNode response, String path) {
        JsonNode node = response;
        if (!path.isBlank()) {
            for (String part : path.split("\\.")) {
                node = node == null ? null : node.get(part);
            }
        }
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("The response has no JSON array at '" + path + "'");
        }
        List<JsonNode> out = new ArrayList<>();
        node.forEach(out::add);
        return out;
    }

    private static Table table(List<JsonNode> records) {
        Set<String> header = new LinkedHashSet<>();
        records.forEach(r -> r.fieldNames().forEachRemaining(header::add));
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode record : records) {
            Map<String, String> row = new LinkedHashMap<>();
            for (String name : header) {
                JsonNode value = record.get(name);
                row.put(name, value == null || value.isNull() ? "" : value.isValueNode() ? value.asText() : value.toString());
            }
            rows.add(row);
        }
        return new Table(new ArrayList<>(header), rows);
    }

    private static URI withQuery(String base, String name, int value, String sizeName, int size) {
        String separator = base.contains("?") ? "&" : "?";
        return URI.create(base + separator + URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + value + "&"
                + URLEncoder.encode(sizeName, StandardCharsets.UTF_8) + "=" + size);
    }

    /** JDK HTTP client with timeouts; redirects are not followed so credentials never leave the configured host. */
    static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        @Override
        public String get(URI uri, Map<String, String> headers) throws IOException {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET();
            headers.forEach(request::header);
            try {
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("The API answered HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling the API", e);
            }
        }
    }
}
