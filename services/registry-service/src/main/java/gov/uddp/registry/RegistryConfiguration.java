package gov.uddp.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.events.PlatformEvents;
import gov.uddp.core.golden.GoldenFamilyBuilder;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.golden.PlatformSurvivorshipPolicyLoader;
import gov.uddp.core.identity.UddpIdService;
import gov.uddp.core.statepack.StatePackLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the monolith's unchanged Golden build engine against this service's own schema, as the monolith's application
 * context wires it (platform survivorship policy, Golden Families, UDDP IDs), with two differences: link constraints
 * come from the stewardship projection and review tasks are not created here (they are api-stewardship's), and the
 * build's events are published by registry-service.
 */
@Configuration
public class RegistryConfiguration {

    @Bean
    PostgresConnectionFactory registryConnections(JdbcConnectionDetails database) {
        String url = database.getJdbcUrl();
        String schema = (url.contains("?") ? "&" : "?") + "currentSchema=registry,lineage";
        return PostgresConnectionFactory.forSettings(url + schema, database.getUsername(), database.getPassword());
    }

    @Bean
    PlatformEvents registryEvents(PostgresConnectionFactory registryConnections) {
        var events = new PlatformEvents(registryConnections, true, "registry-service");
        // Matching runs and Raw are other services' events; this service only publishes Golden changes.
        events.setRemoteIdentity(true);
        events.setRemoteIngestion(true);
        // Each build also publishes the rows it wrote, for api-stewardship-service's read model.
        events.setGoldenReplica(true);
        return events;
    }

    @Bean
    GoldenResidentService goldenResidents(PostgresConnectionFactory registryConnections, PlatformEvents registryEvents,
                                          @Value("${uddp.registry.platform-directory}") String platformDirectory,
                                          @Value("${uddp.registry.state-pack-directory}") String statePackDirectory) {
        var properties = new StatePackProperties();
        properties.setPlatformDirectory(platformDirectory);
        properties.setStatePackDirectory(statePackDirectory);
        var json = new ObjectMapper();
        var golden = new GoldenResidentService(registryConnections, json, new StatePackLoader(properties),
                new PlatformSurvivorshipPolicyLoader(properties));
        golden.setFamilyBuilder(new GoldenFamilyBuilder(json));
        golden.setStewardship(new ProjectedStewardship());
        golden.setUddpIds(new UddpIdService(registryConnections, new UddpIdService.Format(10,
                "23456789ABCDEFGHJKLMNPQRSTUVWXYZ", "", "", true)));
        golden.setPlatformEvents(registryEvents);
        return golden;
    }
}
