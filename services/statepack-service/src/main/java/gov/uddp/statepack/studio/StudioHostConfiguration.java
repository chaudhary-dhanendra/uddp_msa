package gov.uddp.statepack.studio;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.config.StudioProperties;
import gov.uddp.core.onboarding.DatabaseSources;
import gov.uddp.core.statepack.StatePackLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Hosts the Onboarding Studio engine (moved unchanged from the edge) on this service's schema: drafts, approvals and
 * suggestion reviews in the studio tables here (V3), uploads in this service's workspace folder, published packs as
 * this service's versions ({@link ServiceStudioPublications}) and the edge's onboarding runs as it reports them
 * ({@link ServiceStudioOnboarding}). The platform metadata (mapping targets, defaults, scheme catalogue) is read from
 * the repository root.
 */
@Configuration
@ComponentScan(basePackages = {"gov.uddp.core.studio", "gov.uddp.core.ai", "gov.uddp.shaping.ops",
        "gov.uddp.shaping.parsers"})
@EnableConfigurationProperties(StudioProperties.class)
@Import({DatabaseSources.class, gov.uddp.core.scheme.SchemeCatalogue.class,
        gov.uddp.core.mapping.RawCsvRecordParser.class, gov.uddp.shaping.ShapingEngine.class,
        gov.uddp.shaping.OperationRegistry.class, gov.uddp.core.mapping.ResidentMappingEngine.class,
        gov.uddp.core.mapping.FamilyMappingEngine.class, gov.uddp.core.mapping.BenefitMappingEngine.class,
        gov.uddp.core.validation.ResidentValidator.class, gov.uddp.core.validation.PlatformReferenceData.class,
        gov.uddp.core.read.Db.class, gov.uddp.core.geography.GeographyService.class})
public class StudioHostConfiguration {

    @Bean
    PostgresConnectionFactory statepackConnections(JdbcConnectionDetails database) {
        String url = database.getJdbcUrl();
        String schema = (url.contains("?") ? "&" : "?") + "currentSchema=statepack";
        return PostgresConnectionFactory.forSettings(url + schema, database.getUsername(), database.getPassword());
    }

    @Bean
    StatePackProperties statePackProperties(@Value("${uddp.studio.repository-root:../..}") String repositoryRoot,
                                            @Value("${uddp.studio.settings-directory:}") String settingsDirectory) {
        var properties = new StatePackProperties();
        properties.setRepositoryRoot(repositoryRoot);
        properties.setPlatformDirectory(java.nio.file.Path.of(repositoryRoot, "platform").toAbsolutePath().normalize()
                .toString());
        // The engine's static helpers (shaping reference data, identifiers) read the platform directory from here.
        System.setProperty("uddp.platform-directory", properties.getPlatformDirectory());
        properties.setStatePackDirectory(java.nio.file.Path.of(repositoryRoot, "services/statepack-service/seed/state-packs").toString());
        properties.setFixtureStatePacksEnabled(false);
        if (!settingsDirectory.isBlank()) {
            properties.setSettingsDirectory(settingsDirectory);
        }
        return properties;
    }

    /** The Studio's named database connections for previews (password encrypted in this service's settings). */
    @Bean
    gov.uddp.core.setup.LocalSettingsStore localSettingsStore(StatePackProperties properties) {
        return new gov.uddp.core.setup.LocalSettingsStore(java.nio.file.Path.of(properties.getSettingsDirectory()));
    }

    /** Identifier types for the identifier parsers (seeded from the platform directory on first use). */
    @Bean
    gov.uddp.core.identity.IdentifierTypeRegistry identifierTypes(PostgresConnectionFactory statepackConnections,
                                                                 StatePackProperties statePackProperties) {
        return new gov.uddp.core.identity.IdentifierTypeRegistry(statepackConnections, statePackProperties,
                java.util.List.of(new gov.uddp.core.identity.LuhnChecksum(),
                        new gov.uddp.core.identity.VerhoeffChecksum()));
    }

    @Bean
    StatePackLoader statePackLoader(StatePackProperties properties,
                                    gov.uddp.shaping.api.OperationLookup operations) {
        var loader = new StatePackLoader(properties);
        loader.setOperations(operations);
        return loader;
    }
}
