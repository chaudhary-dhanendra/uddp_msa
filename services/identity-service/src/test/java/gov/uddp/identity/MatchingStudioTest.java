package gov.uddp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Matching Studio now runs in identity-service on its own tables: profiles are created and versioned with the
 * caller's name (X-UDDP-Actor, forwarded by the edge), data readiness reads the canonical candidates projected here,
 * monitoring bounds are kept here, and steward link constraints arrive from stewardship.decision-recorded into the
 * effective view the impact preview uses.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MatchingStudioTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_identity");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void profilesReadinessAndConstraintsLiveInIdentityService() throws Exception {
        for (JsonNode event : IdentitySplinkMatchingTest.sampleEvents()) {
            try (var c = dataSource.getConnection()) {
                Projections.residentCreated(c, event);
            }
        }

        var created = exchange(HttpMethod.POST, "/api/v1/matching/profiles", "maker",
                Map.of("name", "resident-v2", "config", Map.of("blocking", java.util.List.of("l.dob = r.dob"),
                        "thresholds", Map.of("autoMatch", 0.9,
                        "possibleMatch", 0.7))));
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("createdBy").asText()).isEqualTo("maker");
        assertThat(json.readTree(http.getForObject("/api/v1/matching/profiles", String.class))).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from matching_profile", Integer.class)).isEqualTo(1);

        JsonNode readiness = json.readTree(http.getForObject("/api/v1/matching/readiness", String.class));
        assertThat(readiness.toString()).contains("pds");

        exchange(HttpMethod.PUT, "/api/v1/matching/monitoring/bounds/match_rate", "steward",
                Map.of("minValue", 0.1, "maxValue", 0.9));
        assertThat(jdbc.queryForObject("select updated_by from matching_metric_bound where metric = 'match_rate'",
                String.class)).isEqualTo("steward");

        UUID decision = UUID.randomUUID();
        String event = """
                {"eventId":"%s","eventType":"uddp.stewardship.decision-recorded","payload":{"decisionId":"%s","rows":{
                 "stewardship_decision":[{"decision_id":"%s","decision_sequence":7,"action":"SPLIT"}],
                 "stewardship_link_constraint":[{"decision_id":"%s","record_key_a":"A","record_key_b":"B",
                 "constraint_type":"CANNOT_LINK"}]}}}
                """.formatted(UUID.randomUUID(), decision, decision, decision);
        try (var c = dataSource.getConnection()) {
            Projections.decisionRecorded(c, json.readTree(event));
        }
        assertThat(jdbc.queryForList("select constraint_type from stewardship_link_constraint_effective",
                String.class)).isEqualTo(List.of("CANNOT_LINK"));
    }

    private JsonNode exchange(HttpMethod method, String path, String actor, Object body) throws Exception {
        var headers = new HttpHeaders();
        headers.add("X-UDDP-Actor", actor);
        headers.add("Content-Type", "application/json");
        var response = http.exchange(path, method, new HttpEntity<>(json.writeValueAsString(body), headers),
                String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(path + " " + response.getBody()).isTrue();
        return response.getBody() == null ? json.createObjectNode() : json.readTree(response.getBody());
    }
}
