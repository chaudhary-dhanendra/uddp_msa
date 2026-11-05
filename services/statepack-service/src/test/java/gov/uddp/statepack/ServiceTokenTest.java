package gov.uddp.statepack;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * With a service token configured, the internal API answers only callers that send it (in constant time), and health
 * and metrics stay open for probes and Prometheus.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "uddp.internal.token=test-service-token")
class ServiceTokenTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform");

    @Autowired
    private TestRestTemplate http;

    @Test
    void internalApisNeedTheServiceToken() {
        assertThat(http.getForEntity("/api/statepacks", String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(get("wrong-token")).isEqualTo(401);
        assertThat(get("test-service-token")).isEqualTo(200);
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
    }

    private int get(String token) {
        var headers = new HttpHeaders();
        headers.add("X-UDDP-Service-Token", token);
        return http.exchange("/api/statepacks", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode().value();
    }
}
