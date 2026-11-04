package gov.uddp.statepack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.uddp.contracts.StatePackClient;
import gov.uddp.contracts.StatePackDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The State 2 sample pack through the whole lifecycle over HTTP: publish (maker), approve (a different checker),
 * activate, a second version that retires the first, served and verified by digest through StatePackClient and
 * written to a loader cache exactly as approved. Published content is immutable.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatePackLifecycleTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path cache;

    @Test
    void publishApproveActivateServeAndRetireByDigest() throws Exception {
        Map<String, String> files = packFiles(Path.of("../../state-packs/state-2"));
        var published = http.postForEntity("/api/statepacks", Map.of("maker", "asha", "files", files), Map.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(published.getBody().get("status")).isEqualTo("PENDING_APPROVAL");
        String digest = (String) published.getBody().get("digest");
        assertThat(digest).isEqualTo(StatePackDigest.of(files));

        assertThat(http.postForEntity("/api/statepacks/state-2/1.0.0/approve", Map.of("checker", "asha"), Map.class)
                .getStatusCode()).as("the maker cannot approve").isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/statepacks/state-2/1.0.0/activate", Map.of("actor", "ravi"), Map.class)
                .getStatusCode()).as("only an approved version activates").isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/statepacks/state-2/1.0.0/approve", Map.of("checker", "ravi"), Map.class)
                .getBody().get("status")).isEqualTo("APPROVED");

        var client = new StatePackClient("http://localhost:" + port);
        var pack = client.resolve("ST2", "1.0.0");
        assertThat(pack.ref().digest()).isEqualTo(digest);
        assertThat(pack.files()).isEqualTo(files);
        Path dir = StatePackClient.materialize(pack, cache);
        assertThat(StatePackDigest.of(packFiles(dir))).isEqualTo(digest);
        assertThatThrownBy(() -> client.fetch("state-2", "1.0.0", "sha256:" + "0".repeat(64)))
                .hasMessageContaining("expected");

        assertThat(http.postForEntity("/api/statepacks", Map.of("maker", "asha", "files", files), Map.class)
                .getBody().get("digest")).as("publishing the same content again is idempotent").isEqualTo(digest);
        Map<String, String> changed = new LinkedHashMap<>(files);
        changed.put("crosswalks/gender.yaml", files.get("crosswalks/gender.yaml") + "\n# changed\n");
        assertThat(http.postForEntity("/api/statepacks", Map.of("maker", "asha", "files", changed), Map.class)
                .getStatusCode()).as("a published version never changes").isEqualTo(HttpStatus.CONFLICT);

        http.postForEntity("/api/statepacks/state-2/1.0.0/activate", Map.of("actor", "ravi"), Map.class);
        Map<String, String> next = new LinkedHashMap<>(changed);
        next.put("state.yaml", files.get("state.yaml").replace("version: 1.0.0", "version: 1.0.1"));
        http.postForEntity("/api/statepacks", Map.of("maker", "asha", "files", next), Map.class);
        http.postForEntity("/api/statepacks/state-2/1.0.1/approve", Map.of("checker", "ravi"), Map.class);
        assertThat(http.postForEntity("/api/statepacks/state-2/1.0.1/activate", Map.of("actor", "ravi"), Map.class)
                .getBody().get("status")).isEqualTo("ACTIVE");
        assertThat(client.fetch("state-2", "1.0.0", digest).status()).isEqualTo("RETIRED");

        assertThat(jdbc.queryForList("select event_type from outbox_event order by created_at", String.class))
                .containsExactly("uddp.statepack.pack-published", "uddp.statepack.pack-approved",
                        "uddp.statepack.pack-activated", "uddp.statepack.pack-published",
                        "uddp.statepack.pack-approved", "uddp.statepack.pack-retired",
                        "uddp.statepack.pack-activated");
        assertThatThrownBy(() -> jdbc.update("update state_pack_file set content = 'x'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update state_pack_version set digest = 'sha256:" + "1".repeat(64) + "'"))
                .hasMessageContaining("cannot be changed");
    }

    private static Map<String, String> packFiles(Path root) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(root.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return files;
    }
}
