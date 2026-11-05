package gov.uddp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Consumer side of the statepack-service HTTP contract: StatePackClient (canonical-service, identity-service, the
 * edge) reads the active versions and a version's files from answers exactly as the contract's examples give them.
 */
class StatePackClientContractTest {

    @Test
    void theClientUnderstandsTheContractsAnswers() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var read = HttpContracts.find("statepack-service", "read a version with its files");
        var list = HttpContracts.find("statepack-service", "list the active versions");
        Map<String, String> files = new LinkedHashMap<>();
        read.example().path("files").properties().forEach(e -> files.put(e.getKey(), e.getValue().asText()));
        String digest = StatePackDigest.of(files);
        String version = read.example().toString().replace("{digest}", digest);
        String active = list.example().toString().replace("{digest}", digest);

        HttpServer statepack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        statepack.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String answer = query != null && query.contains("status=ACTIVE") ? active : version;
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        statepack.start();
        try {
            var client = new StatePackClient("http://127.0.0.1:" + statepack.getAddress().getPort());
            var packs = client.active();
            assertEquals(1, packs.size());
            assertEquals("contract-pack", packs.get(0).ref().packId());
            assertEquals(digest, packs.get(0).ref().digest());
            assertEquals(files, packs.get(0).files());
            assertEquals("1.0.0", ((ObjectNode) json.readTree(version)).path("version").asText());
        } finally {
            statepack.stop(0);
        }
    }
}
