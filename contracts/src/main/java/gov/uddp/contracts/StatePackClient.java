package gov.uddp.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fetches State Packs from statepack-service by version and digest, verifies the digest locally, and writes each
 * verified pack once into a cache directory ({@code <cache>/<packId>-<version>-<digest prefix>/}) that the State Pack
 * loader reads. A pack whose content does not match its digest is never written.
 */
public final class StatePackClient {

    /** One verified pack version. */
    public record Pack(StatePackRef ref, String stateCode, String status, Map<String, String> files) {
    }

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** {@code baseUrl}: statepack-service's address, {@code http://...} or {@code lb://statepack-service}. */
    public StatePackClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** The APPROVED or ACTIVE pack of this state with this version. */
    public Pack resolve(String stateCode, String version) {
        return read(get("api/statepacks/resolve?stateCode=" + encode(stateCode) + "&version=" + encode(version)));
    }

    /** Every ACTIVE pack version (one per pack), each fetched and verified by its digest. */
    public java.util.List<Pack> active() {
        var out = new java.util.ArrayList<Pack>();
        for (JsonNode row : get("api/statepacks?status=ACTIVE")) {
            out.add(fetch(row.path("packId").asText(), row.path("version").asText(), row.path("digest").asText()));
        }
        return out;
    }

    /** The pack version, which must have {@code expectedDigest} (null: whatever was approved). */
    public Pack fetch(String packId, String version, String expectedDigest) {
        Pack pack = read(get("api/statepacks/" + encode(packId) + "/" + encode(version)));
        if (expectedDigest != null && !expectedDigest.equals(pack.ref().digest())) {
            throw new IllegalStateException("State Pack " + packId + " " + version + " has digest "
                    + pack.ref().digest() + ", expected " + expectedDigest);
        }
        return pack;
    }

    /** Writes the verified pack under {@code cache} (once) and returns its directory. */
    public static Path materialize(Pack pack, Path cache) throws IOException {
        String suffix = pack.ref().digest().substring("sha256:".length(), "sha256:".length() + 12);
        Path dir = cache.resolve(pack.ref().packId() + "-" + pack.ref().version() + "-" + suffix);
        if (Files.isRegularFile(dir.resolve("state.yaml"))) {
            removeOtherVersions(cache, pack.ref().packId(), dir);
            return dir;
        }
        Path staging = Files.createDirectories(cache).resolve(dir.getFileName() + ".tmp-" + System.nanoTime());
        for (var file : pack.files().entrySet()) {
            Path target = staging.resolve(file.getKey()).normalize();
            if (!target.startsWith(staging)) {
                throw new IllegalStateException("State Pack file escapes its directory: " + file.getKey());
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
        try {
            Files.move(staging, dir);
        } catch (IOException raced) {
            if (!Files.isRegularFile(dir.resolve("state.yaml"))) {
                throw raced;
            }
        }
        removeOtherVersions(cache, pack.ref().packId(), dir);
        return dir;
    }

    /**
     * The cache holds one version per pack: callers load the whole cache, which rejects two folders with the same
     * packId, so the version just materialized replaces the pack's earlier versions (and earlier digests).
     */
    static void removeOtherVersions(Path cache, String packId, Path keep) throws IOException {
        var version = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(packId)
                + "-\\d+\\.\\d+\\.\\d+[^/\\\\]*-[0-9a-f]{12}");
        try (var entries = Files.list(cache)) {
            for (Path other : entries.filter(Files::isDirectory).toList()) {
                if (!other.equals(keep) && version.matcher(other.getFileName().toString()).matches()) {
                    deleteTree(other);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Pack read(JsonNode body) {
        Map<String, String> files = new LinkedHashMap<>();
        body.path("files").properties().forEach(e -> files.put(e.getKey(), e.getValue().asText()));
        String digest = StatePackDigest.of(files);
        if (!digest.equals(body.path("digest").asText())) {
            throw new IllegalStateException("State Pack " + body.path("packId").asText() + " content does not match"
                    + " its digest");
        }
        return new Pack(new StatePackRef(body.path("packId").asText(), body.path("version").asText(), digest),
                body.path("stateCode").asText(), body.path("status").asText(), files);
    }

    private JsonNode get(String path) {
        try {
            var response = ServiceCalls.send(http, ServiceUrls.request(URI.create(ServiceUrls.resolve(baseUrl) + "/"
                    + path)).timeout(Duration.ofSeconds(30)).GET().build(), true);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("statepack-service answered " + response.statusCode() + " for "
                        + path);
            }
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach statepack-service", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching a State Pack", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
