package gov.uddp.core.monitoring;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Evaluates the alert rules and sends due webhooks every minute; off with {@code uddp.alerts.enabled=false}. */
@Component
@ConditionalOnProperty(name = "uddp.alerts.enabled", havingValue = "true", matchIfMissing = true)
public class AlertRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AlertRunner.class);

    private final AlertService alerts;
    private final PostgresConnectionFactory connections;

    public AlertRunner(AlertService alerts, PostgresConnectionFactory connections) {
        this.alerts = alerts;
        this.connections = connections;
    }

    @Scheduled(fixedDelayString = "${uddp.alerts.tick-millis:60000}",
            initialDelayString = "${uddp.alerts.initial-delay-millis:30000}")
    public void tick() {
        if (!connections.isConfigured()) {
            return;
        }
        try {
            alerts.evaluate();
            alerts.deliverDue();
        } catch (RuntimeException e) {
            LOG.warn("Alert evaluation failed: {}", e.getMessage());
        }
    }
}
