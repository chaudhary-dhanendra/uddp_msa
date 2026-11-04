package gov.uddp.identity.studio;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.stewardship.StewardshipStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the Matching Studio engine (moved unchanged from the edge) against identity-service's own schema: profiles,
 * training and labels in the matching tables here, canonical candidates, Golden membership and stewardship link
 * constraints through the projections and views of V2-V4, and the Python modules on this schema (PGOPTIONS).
 */
@Configuration
@ComponentScan(basePackages = "gov.uddp.core.matching")
public class MatchingStudioConfiguration {

    @Bean
    PostgresConnectionFactory identityConnections(JdbcConnectionDetails database) {
        String url = database.getJdbcUrl();
        String schema = (url.contains("?") ? "&" : "?") + "currentSchema=identity";
        return PostgresConnectionFactory.forSettings(url + schema, database.getUsername(), database.getPassword());
    }

    @Bean
    StatePackProperties statePackProperties(@Value("${uddp.identity.repository-root:../..}") String repositoryRoot) {
        var properties = new StatePackProperties();
        properties.setRepositoryRoot(repositoryRoot);
        return properties;
    }

    @Bean
    StewardshipStore stewardshipConstraints() {
        return new StewardshipStore();
    }
}
