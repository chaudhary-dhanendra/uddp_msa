package gov.uddp.core.privacy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the daily retention purge; {@code uddp.retention.schedule-enabled=false} leaves it to manual runs. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "uddp.retention.schedule-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionScheduling {
}
