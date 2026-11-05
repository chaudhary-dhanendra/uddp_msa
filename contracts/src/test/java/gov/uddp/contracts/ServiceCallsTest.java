package gov.uddp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Retries for calls that may be repeated, and a circuit breaker per target that fails fast once it opens. */
class ServiceCallsTest {

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(2)).build();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        ServiceCalls.reset();
    }

    @Test
    void repeatableCallsAreRetriedThroughBriefUnavailabilityOthersAreNot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = calls.incrementAndGet() <= 2 ? 503 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/x");

        assertEquals(200, ServiceCalls.send(client, HttpRequest.newBuilder(uri).GET().build(), true).statusCode());
        assertEquals(3, calls.get(), "two retries");

        calls.set(0);
        var post = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(503, ServiceCalls.send(client, post, false).statusCode(), "a change is never repeated");
        assertEquals(1, calls.get());
    }

    @Test
    void anUnreachableTargetOpensItsCircuitAndThenFailsFast() throws Exception {
        int port;
        try (var free = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort(); // closed again: nothing listens there
        }
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/x"))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
        for (int i = 0; i < ServiceCalls.FAILURE_THRESHOLD; i++) {
            var failure = assertThrows(ServiceCalls.Unavailable.class, () -> ServiceCalls.send(client, request, false));
            assertTrue(failure.getMessage().startsWith("Cannot reach"), failure.getMessage());
        }
        long started = System.nanoTime();
        var open = assertThrows(ServiceCalls.Unavailable.class, () -> ServiceCalls.send(client, request, true));
        assertTrue(open.getMessage().startsWith("Circuit open"), open.getMessage());
        assertTrue(System.nanoTime() - started < 50_000_000L, "an open circuit answers at once");
    }
}
