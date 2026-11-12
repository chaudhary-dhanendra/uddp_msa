package gov.uddp.statepack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import gov.uddp.core.setup.LocalSettingsStore;
import gov.uddp.core.studio.StudioModels;
import gov.uddp.statepack.studio.IngestionDatabaseSources;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The Studio hosted here keeps no source credentials: it lists the connections ingestion-service has, reads preview
 * rows through it by connection name, and saves a new connection through it (ingestion-service tests it and keeps the
 * password encrypted). The stub answers with the examples of the ingestion-service HTTP contract.
 */
class IngestionDatabaseSourcesTest {

    @Test
    void connectionsAndPreviewsComeFromIngestionService() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer ingestion = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ingestion.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
            // The answers are the ingestion-service HTTP contract's examples (consumer side of the contract).
            String path = exchange.getRequestURI().getPath();
            String answer = gov.uddp.contracts.HttpContracts.find("ingestion-service",
                    "PUT".equals(exchange.getRequestMethod()) ? "save a database connection"
                            : path.endsWith("/connections") ? "list the database connections"
                            : "preview a database source").example().toString();
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ingestion.start();
        try {
            var sources = new IngestionDatabaseSources(new LocalSettingsStore(Files.createTempDirectory("uddp-s")),
                    "http://127.0.0.1:" + ingestion.getAddress().getPort());
            var listed = sources.list();
            assertThat(listed).extracting(StudioModels.SourceConnection::name).containsExactly("registry-db");
            var rows = sources.read("registry-db", "citizens", null, 5);
            assertThat(rows.header()).containsExactly("id", "name");
            assertThat(rows.rows().get(0).get("name")).isEqualTo("Asha");
            assertThat(seen.get(1)).startsWith("POST /api/ingestion/sources/rows")
                    .contains("\"connectionRef\":\"registry-db\"").contains("\"table\":\"citizens\"");
            var saved = sources.save("studio-saved", new StudioModels.SourceConnectionRequest("db", 5432, "reg", "ro",
                    "secret"));
            assertThat(saved.name()).isEqualTo("studio-saved");
            assertThat(saved.passwordSet()).isTrue();
            assertThat(seen.get(2)).startsWith("PUT /api/ingestion/connections/studio-saved")
                    .contains("\"host\":\"db\"").contains("\"port\":5432").contains("\"password\":\"secret\"");
            assertThatThrownBy(() -> sources.save("Not A Name", new StudioModels.SourceConnectionRequest("db", 5432,
                    "reg", "ro", "secret"))).isInstanceOf(IllegalArgumentException.class);
        } finally {
            ingestion.stop(0);
        }
    }
}
