package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import gov.uddp.contracts.StatePackClient;
import gov.uddp.contracts.StatePackDigest;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.statepack.DatabaseStatePackRepository;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MSA, statepack-service as the pack system of record: a version it activates is fetched by digest, verified, loaded
 * by the existing loader and installed at the edge as the pack document, registered ACTIVE; installing it again
 * changes nothing, a newer version retires it, and content that does not match its digest is never installed.
 */
@Testcontainers
class StatePackInstallTest {

    @Test
    void activatedVersionsAreInstalledOnceAndSupersedeTheirPredecessor() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        StatePackLoader loader = SampleData.loader(Files.createTempDirectory("uddp-empty-packs"));
        var documents = new DatabaseStatePackRepository(connections, new ObjectMapper().findAndRegisterModules());
        var setDatabase = StatePackLoader.class.getDeclaredMethod("setDatabase", DatabaseStatePackRepository.class);
        setDatabase.setAccessible(true);
        setDatabase.invoke(loader, documents);
        var lifecycle = new StatePackLifecycleService(connections, loader);

        Map<String, String> v1 = files(SampleData.STATE_PACKS.resolve("state-2"));
        String packId = field(v1.get("state.yaml"), "packId");
        String version1 = field(v1.get("state.yaml"), "version");
        Map<String, String> v2 = new LinkedHashMap<>(v1);
        v2.put("state.yaml", v1.get("state.yaml").replace("version: " + version1, "version: 9.9.9"));
        AtomicReference<String> tamper = new AtomicReference<>();

        HttpServer statepacks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        statepacks.createContext("/api/statepacks/", exchange -> {
            String version = exchange.getRequestURI().getPath().replaceAll(".*/", "");
            Map<String, String> files = new LinkedHashMap<>(version.equals("9.9.9") ? v2 : v1);
            String digest = StatePackDigest.of(files);
            if (tamper.get() != null) {
                files.put("state.yaml", files.get("state.yaml") + "\n# " + tamper.get());
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("packId", packId, "version", version,
                    "stateCode", "X", "digest", digest, "status", "ACTIVE", "files", files));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        statepacks.start();
        try {
            var client = new StatePackClient("http://127.0.0.1:" + statepacks.getAddress().getPort());
            var installer = new RemoteStatePackMessaging.Installer(client, loader, documents, lifecycle, connections);

            installer.install(packId, version1, StatePackDigest.of(v1));
            installer.install(packId, version1, StatePackDigest.of(v1));
            assertEquals(1L, sql.count("select count(*) from state_pack_document where pack_id = ?", packId));
            assertEquals(List.of("ACTIVE"), statuses(sql, packId));
            assertEquals(version1, loader.find(packId).orElseThrow().manifest().version());

            installer.install(packId, "9.9.9", StatePackDigest.of(v2));
            assertEquals(List.of("ACTIVE", "RETIRED"), statuses(sql, packId), "9.9.9 active, the old one retired");
            assertEquals("9.9.9", loader.find(packId).orElseThrow().manifest().version());

            tamper.set("changed in transit");
            assertThrows(IllegalStateException.class, () -> installer.install(packId, version1, StatePackDigest.of(v1)));
        } finally {
            statepacks.stop(0);
        }
    }

    private static List<String> statuses(Sql sql, String packId) {
        return sql.column("select status from state_pack_lifecycle where pack_id = ? order by status", String.class,
                packId);
    }

    private static String field(String yaml, String name) {
        return yaml.lines().filter(l -> l.startsWith(name + ":")).findFirst().orElseThrow()
                .substring(name.length() + 1).trim().replace("\"", "");
    }

    private static Map<String, String> files(Path pack) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(pack)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(pack.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return files;
    }
}
