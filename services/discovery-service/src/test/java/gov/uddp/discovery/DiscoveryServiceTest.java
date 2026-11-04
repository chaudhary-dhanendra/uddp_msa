package gov.uddp.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** The registry starts, reports healthy and serves its (empty) application list. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServiceTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void servesTheRegistry() {
        assertThat(http.getForObject("/actuator/health", String.class)).contains("\"UP\"");
        var headers = new HttpHeaders();
        headers.add("Accept", "application/json");
        var apps = http.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(apps.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(apps.getBody()).contains("applications");
    }
}
