package gov.uddp.edge;

import static org.assertj.core.api.Assertions.assertThat;

import gov.uddp.core.events.RemoteCanonicalProcessor;
import gov.uddp.core.events.RemoteGoldenBuilder;
import gov.uddp.core.events.RemoteHopRunner;
import gov.uddp.core.events.RemoteMatchingRunner;
import gov.uddp.core.onboarding.CanonicalBatchProcessor;
import gov.uddp.core.onboarding.GoldenBuildRunner;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.MatchingRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;

/**
 * The edge starts only with edge.yml: every pipeline stage boundary is the remote
 * one (ingestion, canonical processing, matching and Golden builds are other services', as are the Matching
 * and Onboarding Studios), and it answers health.
 * Started without database settings, it comes up in setup mode, as the monolith does.
 */
class EdgeServiceSmokeTest {

    @Test
    void everyPipelineStageIsHandedToItsService() throws Exception {
        try (var context = EdgeServiceApplication.builder()
                .properties("server.port=0", "server.address=127.0.0.1", "uddp.settings-directory=target/test-settings",
                        "uddp.database.migrate-on-startup=false", "spring.jmx.enabled=false")
                .run()) {
            assertThat(context.getBean(HopRunner.class)).isInstanceOf(RemoteHopRunner.class);
            assertThat(context.getBean(CanonicalBatchProcessor.class)).isInstanceOf(RemoteCanonicalProcessor.class);
            assertThat(context.getBean(MatchingRunner.class)).isInstanceOf(RemoteMatchingRunner.class);
            assertThat(context.getBean(GoldenBuildRunner.class)).isInstanceOf(RemoteGoldenBuilder.class);
            // Both Studios run in their services; the edge forwards after its own checks.
            assertThat(context.getBeansOfType(gov.uddp.core.events.RemoteStudioService.class)).hasSize(1);
            assertThat(context.getBeansOfType(gov.uddp.core.events.RemoteStudioDelegation.class)).hasSize(1);
            assertThat(context.getEnvironment().getProperty("spring.application.name"))
                    .isEqualTo("edge-service");

            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isIn(200, 401, 403);
        }
    }
}
