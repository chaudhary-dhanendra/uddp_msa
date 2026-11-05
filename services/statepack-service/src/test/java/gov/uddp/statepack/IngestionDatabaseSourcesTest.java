package gov.uddp.statepack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import gov.uddp.core.setup.LocalSettingsStore;
import gov.uddp.core.studio.StudioConflictException;
import gov.uddp.core.studio.StudioModels;
import gov.uddp.statepack.studio.IngestionDatabaseSources;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The Studio hosted here keeps no source credentials: it lists the connections ingestion-service has configured and
 * reads preview rows through it by connection name, and refuses to store a password.
 */
class IngestionDatabaseSourcesTest {

    @Test
    void connectionsAndPreviewsComeFromIngestionService() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer ingestion = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ingestion.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
            String answer = exchange.getRequestURI().getPath().endsWith("/connections")
                    ? "[{\"name\":\"registry-db\",\"host\":\"db\",\"port\":5432,\"database\":\"reg\",\"user\":\"ro\","
                    + "\"passwordSet\":true}]"
                    : "{\"header\":[\"id\",\"name\"],\"rows\":[{\"id\":\"1\",\"name\":\"Asha\"}]}";
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
            assertThatThrownBy(() -> sources.save("registry-db", new StudioModels.SourceConnectionRequest("db", 5432,
                    "reg", "ro", "secret"))).isInstanceOf(StudioConflictException.class)
                    .hasMessageContaining("ingestion-service");
        } finally {
            ingestion.stop(0);
        }
    }
}
