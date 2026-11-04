package gov.uddp.core.onboarding;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives {@link SchedulerService#tick()} on a fixed delay; off with {@code uddp.scheduler.enabled=false}. */
@Component
@ConditionalOnProperty(name = "uddp.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SchedulerRunner.class);

    private final SchedulerService scheduler;
    private final PostgresConnectionFactory connections;
    private final java.util.List<PlatformScheduledJob> jobs;

    public SchedulerRunner(SchedulerService scheduler, PostgresConnectionFactory connections,
                           org.springframework.beans.factory.ObjectProvider<PlatformScheduledJob> jobs) {
        this.scheduler = scheduler;
        this.connections = connections;
        this.jobs = jobs.orderedStream().toList();
    }

    @Scheduled(fixedDelayString = "${uddp.scheduler.tick-millis:30000}",
            initialDelayString = "${uddp.scheduler.initial-delay-millis:15000}")
    public void tick() {
        if (!connections.isConfigured()) {
            return;
        }
        try {
            scheduler.tick();
        } catch (RuntimeException e) {
            LOG.warn("Scheduler tick failed: {}", e.getMessage());
        }
        for (PlatformScheduledJob job : jobs) {
            try {
                job.tick(java.time.Instant.now());
            } catch (RuntimeException e) {
                LOG.warn("Scheduled job {} failed: {}", job.name(), e.getMessage());
            }
        }
    }
}
