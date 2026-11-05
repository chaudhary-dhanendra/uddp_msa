package gov.uddp.core.held;

import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.statepack.StatePackLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Held records in this service's own database when canonical processing runs in-process (not in canonical-service). */
@Configuration
public class LocalHeldStore {

    @Bean
    @ConditionalOnProperty(name = "uddp.canonical.remote", havingValue = "false", matchIfMissing = true)
    HeldStore heldStore(HeldRecordRepository repository, CanonicalResidentProcessingService processing,
                             StatePackLoader packs, PostgresConnectionFactory connections) {
        return new HeldRecordOperations(repository, processing, packs, connections);
    }

    /** A group reprocess a stopped process left running continues where it was (after the schema is migrated). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resumeReprocessJobs(org.springframework.boot.context.event.ApplicationReadyEvent ready) {
        ready.getApplicationContext().getBeanProvider(HeldStore.class).ifAvailable(store -> {
            if (store instanceof HeldRecordOperations operations) {
                operations.resumeReprocessJobs();
            }
        });
    }
}
