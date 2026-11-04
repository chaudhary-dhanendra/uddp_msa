package gov.uddp.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import gov.uddp.contracts.ServiceUrls;
import gov.uddp.contracts.StatePackClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Service discovery and the gateway with the real modules: statepack-service registers with the registry
 * (discovery-service) and is then called by its name ({@code lb://statepack-service}); the gateway finds the edge by
 * its registered name and forwards the UI's calls to it (an HTTP stand-in listed as edge-service).
 */
@Testcontainers
class DiscoveryAndGatewayTest {

    /** As a command-line argument, so it outranks the build's "no discovery in tests" system property. */
    static final String ON = "--eureka.client.enabled=true";
    /** The gateway's jars share this JVM's classpath; the servlet services are not gateways. */
    static final String NOT_A_GATEWAY = "spring.cloud.gateway.server.webflux.enabled";
    /** The registry and the gateway have no database; the services' JDBC jars share this JVM's classpath. */
    static final String NO_DATABASE = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform");

    @Test
    void servicesRegisterAndAreCalledByName() throws Exception {
        ConfigurableApplicationContext discovery = null;
        ConfigurableApplicationContext statepack = null;
        ConfigurableApplicationContext gateway = null;
        HttpServer edge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        edge.createContext("/", exchange -> {
            byte[] body = ("edge " + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        edge.start();
        try {
            discovery = new SpringApplicationBuilder(gov.uddp.discovery.DiscoveryServiceApplication.class)
                    .properties(Map.of("spring.config.name", "e2e-none", "server.port", "0",
                            "spring.application.name", "discovery-service", "eureka.client.enabled", "true",
                            "eureka.client.register-with-eureka", "false", "eureka.client.fetch-registry", "false",
                            "eureka.server.enable-self-preservation", "false", "spring.jmx.enabled", "false",
                            NOT_A_GATEWAY, "false", "spring.autoconfigure.exclude", NO_DATABASE))
                    .run(ON);
            String registry = "http://127.0.0.1:" + port(discovery) + "/eureka/";

            Map<String, Object> p = new HashMap<>(client(registry));
            p.put("spring.application.name", "statepack-service");
            p.put(NOT_A_GATEWAY, "false");
            p.put("spring.datasource.url", POSTGRES.getJdbcUrl());
            p.put("spring.datasource.username", POSTGRES.getUsername());
            p.put("spring.datasource.password", POSTGRES.getPassword());
            p.put("spring.datasource.hikari.schema", "statepack");
            p.put("spring.flyway.locations", "classpath:db/statepack");
            p.put("spring.flyway.schemas", "statepack");
            p.put("spring.flyway.default-schema", "statepack");
            p.put("spring.flyway.create-schemas", "true");
            p.put("uddp.studio.repository-root", Platform.ROOT.toString());
            p.put("uddp.shaping.approved-plugins-file",
                    Platform.ROOT.resolve("platform/shaping/approved-plugins.yaml").toString());
            statepack = new SpringApplicationBuilder(gov.uddp.statepack.StatepackServiceApplication.class)
                    .properties(p).run(ON);

            // The edge stand-in is listed under the edge's registered name.
            Map<String, Object> g = new HashMap<>(client(registry));
            g.put("spring.application.name", "gateway-service");
            g.put("uddp.gateway.edge-uri", "lb://edge-service");
            g.put("uddp.gateway.ui-origins", "http://localhost:4173");
            g.put("spring.cloud.discovery.client.simple.instances.edge-service[0].uri",
                    "http://127.0.0.1:" + edge.getAddress().getPort());
            g.put("spring.main.web-application-type", "reactive");
            g.put("spring.autoconfigure.exclude", NO_DATABASE);
            g.put("eureka.client.webclient.enabled", "true");
            gateway = new SpringApplicationBuilder(gov.uddp.gateway.GatewayServiceApplication.class).properties(g).run(ON);

            // statepack-service appears in the registry and is called by name.
            Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
            String resolved = null;
            while (resolved == null && Instant.now().isBefore(deadline)) {
                try {
                    resolved = ServiceUrls.resolve("lb://statepack-service");
                } catch (IllegalStateException notYet) {
                    Thread.sleep(500);
                }
            }
            assertThat(resolved).as("statepack-service registered").endsWith(":" + port(statepack));
            assertThat(new StatePackClient("lb://statepack-service").active()).isEmpty();

            var http = HttpClient.newHttpClient();
            var viaGateway = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(gateway)
                    + "/api/v1/golden-residents")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(viaGateway.body()).isEqualTo("edge /api/v1/golden-residents");
            var notRouted = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(gateway)
                    + "/eureka/apps")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(notRouted.statusCode()).isEqualTo(404);
        } finally {
            for (var context : new ConfigurableApplicationContext[] {gateway, statepack, discovery}) {
                if (context != null) {
                    context.close();
                }
            }
            edge.stop(0);
            ServiceUrls.useRegistry(null);
        }
    }

    /** A Eureka client of the test registry that registers and refreshes quickly. */
    private static Map<String, Object> client(String registry) {
        Map<String, Object> p = new HashMap<>();
        p.put("spring.config.name", "e2e-none");
        p.put("server.address", "127.0.0.1");
        p.put("server.port", "0");
        p.put("spring.jmx.enabled", "false");
        p.put("eureka.client.enabled", "true");
        p.put("eureka.client.service-url.defaultZone", registry);
        p.put("eureka.client.registry-fetch-interval-seconds", "1");
        p.put("eureka.client.initial-instance-info-replication-interval-seconds", "1");
        p.put("eureka.client.instance-info-replication-interval-seconds", "1");
        p.put("eureka.instance.lease-renewal-interval-in-seconds", "1");
        p.put("eureka.instance.prefer-ip-address", "true");
        p.put("eureka.instance.ip-address", "127.0.0.1");
        // The registry's Jersey jars share this JVM's classpath; a client on its own uses Spring's HTTP clients.
        p.put("eureka.client.jersey.enabled", "false");
        return p;
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }
}
