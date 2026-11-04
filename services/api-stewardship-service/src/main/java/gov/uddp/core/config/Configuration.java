package gov.uddp.core.config;

import gov.uddp.core.rules.RulesProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@org.springframework.context.annotation.Configuration
@EnableConfigurationProperties({StatePackProperties.class, StudioProperties.class, RulesProperties.class})
public class Configuration {
}
