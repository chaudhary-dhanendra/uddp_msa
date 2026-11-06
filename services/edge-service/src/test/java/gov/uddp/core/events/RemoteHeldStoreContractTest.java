package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import gov.uddp.contracts.HttpContracts;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The edge's held-records client reads every answer canonical-service's HTTP contract promises. */
class RemoteHeldStoreContractTest {

    private static final UUID BATCH = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HELD = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID JOB = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String REASON = "RULE_FAILED:name-required";

    private HttpServer canonical;
    private RemoteHeldStore store;

    @BeforeEach
    void stub() throws Exception {
        canonical = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        canonical.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            var interaction = HttpContracts.of("canonical-service").stream()
                    .filter(i -> i.method().equals(exchange.getRequestMethod()))
                    .filter(i -> path.matches(i.path().replaceAll("\\?.*", "")
                            .replaceAll("\\{[a-z]+}", "[^/]+")))
                    // A literal path wins over a placeholder, as in Spring's own matching.
                    .min(java.util.Comparator.comparingInt(i -> i.path().split("\\{").length))
                    .orElse(null);
            byte[] body = interaction == null ? new byte[0]
                    : interaction.example().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(interaction == null ? 404 : interaction.status(),
                    body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        canonical.start();
        store = new RemoteHeldStore("http://127.0.0.1:" + canonical.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        canonical.stop(0);
    }

    @Test
    void readsEveryAnswerOfTheContract() {
        var groups = store.groups(null, 5);
        assertEquals(1, groups.size());
        assertEquals(BATCH, groups.getFirst().batchId());
        assertEquals("XX", groups.getFirst().stateCode());

        var record = store.find(HELD).orElseThrow();
        assertEquals("OPEN", record.record().status());
        assertEquals(REASON, record.record().reasons().getFirst().code());
        assertTrue(record.history().isEmpty());

        assertEquals(1, store.waitingCount(BATCH, REASON));
        assertEquals("XX", store.batchState(BATCH).orElseThrow());

        var started = store.startReprocess(BATCH, REASON, "sam.steward");
        assertEquals(JOB, started.jobId());
        assertEquals("RUNNING", started.status());
        var finished = store.reprocessJob(JOB).orElseThrow();
        assertEquals("COMPLETED", finished.status());
        assertEquals(1, finished.stillHeld());

        assertEquals(1, store.metrics().countsByStatus().get("OPEN"));
        assertTrue(store.groups(List.of(), 5).isEmpty(), "no states in scope asks nothing");
    }
}
