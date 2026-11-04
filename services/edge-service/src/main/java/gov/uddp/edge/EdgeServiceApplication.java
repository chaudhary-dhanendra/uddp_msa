package gov.uddp.edge;

import gov.uddp.core.EdgeApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * edge-service: the edge for the onboarding UI (authentication, permissions, read APIs, review queue,
 * stewardship decisions, onboarding orchestration, Studio). It is the monolith's remaining application, started with
 * {@code edge.yml} on top of the monolith's {@code application.yml}: events on, and ingestion, canonical
 * processing, matching and Golden builds handed to their services, whose results arrive as read models.
 * Database settings come from the UDDP_POSTGRES_* variables, pointed at uddp_platform with currentSchema=edge.
 */
public final class EdgeServiceApplication {

    private EdgeServiceApplication() {
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(EdgeApplication.class)
                .properties("spring.config.name=application,edge")
                .listeners((org.springframework.context.ApplicationListener<
                        org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent>) event -> {
                    // The monolith's static identifier helpers read the platform directory from this system property.
                    String platform = event.getEnvironment().getProperty("uddp.platform-directory");
                    if (platform != null) {
                        System.setProperty("uddp.platform-directory",
                                java.nio.file.Path.of(platform).toAbsolutePath().normalize().toString());
                    }
                });
    }

    public static void main(String[] args) {
        builder().run(args);
    }
}
