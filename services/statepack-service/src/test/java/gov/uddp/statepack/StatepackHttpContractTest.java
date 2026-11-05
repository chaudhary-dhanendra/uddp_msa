package gov.uddp.statepack;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.contracts.HttpContracts;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * Provider side of the statepack-service HTTP contract (contracts/http-contracts/statepack-service.json): every call
 * its consumers make, in the order a version's life needs them, answers what the contract promises.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatepackHttpContractTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform");

    @Autowired
    private TestRestTemplate http;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void everyInteractionOfTheContractIsHonoured() throws Exception {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("files", json.writeValueAsString(Map.of(
                "state.yaml", "packId: contract-pack\nstateCode: CX\nversion: 1.0.0\n")));
        state.put("packId", "contract-pack");
        state.put("version", "1.0.0");
        state.put("stateCode", "CX");

        var interactions = HttpContracts.of("statepack-service");
        assertThat(interactions).extracting(HttpContracts.Interaction::name).containsExactly("publish a version",
                "read a version with its files", "approve a version", "activate a version", "list the active versions",
                "resolve a state's version");
        for (var interaction : interactions) {
            var headers = new HttpHeaders();
            headers.add("Content-Type", interaction.contentType());
            var response = http.exchange(interaction.path(state), HttpMethod.valueOf(interaction.method()),
                    new HttpEntity<>(interaction.body(state), headers), String.class);
            JsonNode body = response.getBody() == null ? null : json.readTree(response.getBody());
            assertThat(HttpContracts.problems(interaction, response.getStatusCode().value(), body))
                    .as(interaction.name() + ": " + response.getBody()).isEmpty();
        }
    }
}
