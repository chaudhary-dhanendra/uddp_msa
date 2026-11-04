package gov.uddp.core.scheme;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.setup.PlatformDatabaseReadyEvent;
import gov.uddp.core.statepack.StatePackLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Track A4: imports the YAML scheme catalogue and the packs' scheme code crosswalks into versioned Scheme Pack records
 * once the platform database is ready. Both imports are idempotent (existing versions are never changed), so this
 * runs safely at every start; {@code uddp.schemes.import-catalogue-on-startup=false} turns it off.
 */
@Component
public class SchemeCatalogueImporter {
    private static final Logger log = LoggerFactory.getLogger(SchemeCatalogueImporter.class);

    private final SchemePackService schemes;
    private final SchemeCatalogue catalogue;
    private final StatePackLoader packs;
    private final PostgresConnectionFactory connections;
    private final boolean enabled;

    public SchemeCatalogueImporter(SchemePackService schemes, SchemeCatalogue catalogue, StatePackLoader packs,
                                   PostgresConnectionFactory connections,
                                   @Value("${uddp.schemes.import-catalogue-on-startup:true}") boolean enabled) {
        this.schemes = schemes;
        this.catalogue = catalogue;
        this.packs = packs;
        this.connections = connections;
        this.enabled = enabled;
    }

    @EventListener({ApplicationReadyEvent.class, PlatformDatabaseReadyEvent.class})
    public void importOnce() {
        if (!enabled || !connections.isConfigured()) {
            return;
        }
        try {
            int imported = schemes.importCatalogue(catalogue);
            int codes = schemes.importCodeCrosswalks(packs.all());
            if (imported + codes > 0) {
                log.info("Scheme catalogue imported: {} scheme(s), {} code crosswalk(s)", imported, codes);
            }
        } catch (RuntimeException e) {
            log.warn("Scheme catalogue import skipped: {}", e.getMessage());
        }
    }
}
