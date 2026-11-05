package gov.uddp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway resolves the edge by name (here through a simple discovery entry instead of Eureka) and forwards the
 * UI's API, sign-in and documentation paths to it, with forwarding headers and without a caller-supplied
 * X-UDDP-Actor; any other path is not routed, so the internal services stay unreachable through it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static final HttpServer EDGE = edge();

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry registry) {
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.discovery.client.simple.instances.edge-service[0].uri",
                () -> "http://127.0.0.1:" + EDGE.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @AfterAll
    static void stop() {
        EDGE.stop(0);
    }

    @Test
    void theUisCallsGoToTheEdgeAndNothingElseIsRouted() throws Exception {
        var http = HttpClient.newHttpClient();
        var api = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/golden-residents"))
                .header("X-UDDP-Actor", "someone-else").header("X-UDDP-Service-Token", "forged").header("Origin", "http://localhost:4173").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).isEqualTo("edge");
        assertThat(api.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:4173");
        assertThat(SEEN.get(0)).startsWith("GET /api/v1/golden-residents actor=nullnull forwarded=");
        assertThat(SEEN.get(0)).doesNotEndWith("forwarded=null");

        var login = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/oauth2/authorization/x"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);

        var internal = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/eureka/apps")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(internal.statusCode()).isEqualTo(404);
        assertThat(SEEN).hasSize(2);
    }

    private static HttpServer edge() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                SEEN.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " actor="
                        + exchange.getRequestHeaders().getFirst("X-UDDP-Actor")
                        + exchange.getRequestHeaders().getFirst("X-UDDP-Service-Token") + " forwarded="
                        + exchange.getRequestHeaders().getFirst("Forwarded")
                        + exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
                byte[] body = "edge".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
