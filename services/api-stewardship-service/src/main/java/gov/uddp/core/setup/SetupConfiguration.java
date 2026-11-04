package gov.uddp.core.setup;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the platform connection to the active settings source: environment first, then the Setup screen's store. */
@Configuration
public class SetupConfiguration {
    @Bean
    LocalSettingsStore localSettingsStore(StatePackProperties properties) {
        return new LocalSettingsStore(Path.of(properties.getSettingsDirectory()));
    }

    @Bean
    PlatformDatabaseSettings platformDatabaseSettings(LocalSettingsStore store) {
        return new PlatformDatabaseSettings(System::getenv, store);
    }

    @Bean
    PostgresConnectionFactory postgresConnectionFactory(PlatformDatabaseSettings settings) {
        return PostgresConnectionFactory.forLookup(settings::lookup);
    }
}
