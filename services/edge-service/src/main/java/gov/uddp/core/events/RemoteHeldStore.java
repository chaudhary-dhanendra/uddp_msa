package gov.uddp.core.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.contracts.ServiceCalls;
import gov.uddp.contracts.ServiceUrls;
import gov.uddp.core.held.HeldRecord;
import gov.uddp.core.held.HeldRecordRepository;
import gov.uddp.core.held.HeldStore;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.rules.ApprovalConflictException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Held records kept by canonical-service (MSA): the edge has checked permission, scope and maker-checker; this client
 * calls canonical-service's internal held-records API with the deciding user's name.
 */
@Component
@Primary
@ConditionalOnProperty(name = "uddp.canonical.remote", havingValue = "true")
public class RemoteHeldStore implements HeldStore {

    private static final String PATH = "/api/canonical/held-records";

    private final String canonical;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public RemoteHeldStore(@Value("${uddp.canonical.url:lb://canonical-service}") String canonical) {
        this.canonical = canonical;
    }

    @Override
    public PageOf list(HeldRecordRepository.Filter filter, int page, int size) {
        if (filter.stateCodes() != null && filter.stateCodes().isEmpty()) {
            return new PageOf(List.of(), page, size, 0);
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("batch", filter.batchId());
        query.put("source", filter.sourceSystem());
        query.put("department", filter.department());
        query.put("reason", filter.reason());
        query.put("status", filter.status());
        query.put("minAgeHours", filter.minAgeHours());
        query.put("states", filter.stateCodes());
        query.put("page", page);
        query.put("size", size);
        return get("" + query(query), json.constructType(PageOf.class));
    }

    @Override
    public List<Group> groups(List<String> stateCodes, int samples) {
        if (stateCodes != null && stateCodes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("states", stateCodes);
        query.put("samples", samples);
        return get("/groups" + query(query), json.getTypeFactory().constructCollectionType(List.class, Group.class));
    }

    @Override
    public List<HeldRecord> samples(UUID batch, String reason, boolean waitingOnly, List<String> stateCodes,
                                    int limit) {
        if (stateCodes != null && stateCodes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("batch", batch);
        query.put("reason", reason);
        query.put("waitingOnly", waitingOnly);
        query.put("states", stateCodes);
        query.put("limit", limit);
        return get("/samples" + query(query),
                json.getTypeFactory().constructCollectionType(List.class, HeldRecord.class));
    }

    @Override
    public Optional<Record> find(UUID heldId) {
        try {
            return Optional.of(get("/" + heldId, json.constructType(Record.class)));
        } catch (ReadNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public RectifyResult rectify(UUID heldId, Map<String, Object> fields, String note, String actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", fields);
        body.put("note", note);
        body.put("actor", actor);
        return post("/" + heldId + "/rectify", body, RectifyResult.class);
    }

    @Override
    public RectifyResult releaseOverride(UUID heldId, String justification, String actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("justification", justification);
        body.put("actor", actor);
        return post("/" + heldId + "/release-override", body, RectifyResult.class);
    }

    @Override
    public HeldRecord reject(UUID heldId, String reasonCode, String note, String actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reasonCode);
        body.put("note", note);
        body.put("actor", actor);
        return post("/" + heldId + "/reject", body, HeldRecord.class);
    }

    @Override
    public HeldRecord reopen(UUID heldId, String reason, String actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("actor", actor);
        return post("/" + heldId + "/reopen", body, HeldRecord.class);
    }

    @Override
    public int rejectWaiting(UUID batch, String reason, String rejectReasonCode, String note, String actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", rejectReasonCode);
        body.put("note", note);
        body.put("actor", actor);
        Map<String, Object> result = post("/groups/" + batch + "/" + encode(reason) + "/reject", body, Map.class);
        return ((Number) result.get("rejected")).intValue();
    }

    @Override
    public int waitingCount(UUID batch, String reason) {
        Map<String, Object> result = get("/groups/" + batch + "/" + encode(reason) + "/waiting",
                json.constructType(Map.class));
        return ((Number) result.get("count")).intValue();
    }

    @Override
    public Optional<String> batchState(UUID batch) {
        Map<String, Object> result = get("/groups/" + batch + "/-/waiting", json.constructType(Map.class));
        String state = (String) result.get("stateCode");
        return state == null || state.isEmpty() ? Optional.empty() : Optional.of(state);
    }

    @Override
    public ReprocessJob startReprocess(UUID batch, String reason, String actor) {
        return post("/groups/" + batch + "/" + encode(reason) + "/reprocess", Map.of("actor", actor),
                ReprocessJob.class);
    }

    @Override
    public Optional<ReprocessJob> reprocessJob(UUID jobId) {
        try {
            return Optional.of(get("/reprocess-jobs/" + jobId, json.constructType(ReprocessJob.class)));
        } catch (ReadNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public void exportCsv(UUID batch, String reason, boolean waitingOnly, Writer out) {
        HttpResponse<java.io.InputStream> response;
        try {
            var request = ServiceUrls.request(URI.create(base() + "/groups/" + batch + "/" + encode(reason)
                    + "/export?waitingOnly=" + waitingOnly)).GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ServiceCalls.Unavailable("Cannot reach canonical-service: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while exporting held records", e);
        }
        try (var in = new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
            if (response.statusCode() >= 400) {
                throw failure(response.statusCode(), new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            in.transferTo(out);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Metrics metrics() {
        return get("/metrics", json.constructType(Metrics.class));
    }

    // ---- HTTP --------------------------------------------------------------------------------------------------------

    private String base() {
        return ServiceUrls.resolve(canonical) + PATH;
    }

    private <T> T get(String path, JavaType type) {
        var request = ServiceUrls.request(URI.create(base() + path)).GET().build();
        return read(send(request, true), type);
    }

    private <T> T post(String path, Object body, Class<?> type) {
        try {
            var request = ServiceUrls.request(URI.create(base() + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            return read(send(request, false), json.constructType(type));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> send(HttpRequest request, boolean retryable) {
        try {
            var response = ServiceCalls.send(http, request, retryable);
            if (response.statusCode() >= 400) {
                throw failure(response.statusCode(), response.body());
            }
            return response;
        } catch (IOException e) {
            throw new ServiceCalls.Unavailable("Cannot reach canonical-service: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling canonical-service", e);
        }
    }

    private <T> T read(HttpResponse<String> response, JavaType type) {
        try {
            return json.readValue(response.body(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable answer from canonical-service", e);
        }
    }

    /** canonical-service's 404, 409 and 400 become the same exceptions a local store throws. */
    private RuntimeException failure(int status, String body) {
        String message = body;
        try {
            message = json.readTree(body).path("message").asText(body);
        } catch (IOException ignored) {
            // not JSON: keep the text
        }
        return switch (status) {
            case 404 -> new ReadNotFoundException(message);
            case 409 -> new ApprovalConflictException(message);
            case 400 -> new IllegalArgumentException(message);
            default -> new IllegalStateException("canonical-service answered " + status + ": " + message);
        };
    }

    private static String query(Map<String, Object> parameters) {
        List<String> parts = new ArrayList<>();
        parameters.forEach((name, value) -> {
            if (value instanceof List<?> list) {
                list.forEach(item -> parts.add(name + "=" + encode(String.valueOf(item))));
            } else if (value != null) {
                parts.add(name + "=" + encode(String.valueOf(value)));
            }
        });
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
