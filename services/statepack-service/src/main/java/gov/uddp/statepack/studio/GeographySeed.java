package gov.uddp.statepack.studio;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.geography.FileLgdSourceClient;
import gov.uddp.core.geography.LgdImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fills this service's read model of the LGD geography master (V4) when it is empty, from the configured snapshot
 * (by default the platform's demo snapshot, platform/reference-data/lgd), so Studio previews resolve districts the
 * same way canonical processing does. An existing master is never touched; a real deployment points
 * UDDP_LGD_SEED at the LGD snapshot it loads everywhere.
 */
@Component
public class GeographySeed {

    private static final Logger log = LoggerFactory.getLogger(GeographySeed.class);

    private final PostgresConnectionFactory connections;
    private final LgdImportService imports;
    private final String seed;

    public GeographySeed(PostgresConnectionFactory connections, com.fasterxml.jackson.databind.ObjectMapper json,
                         @Value("${uddp.studio.lgd-seed:}") String seed) {
        this.connections = connections;
        this.imports = new LgdImportService(connections, json);
        this.seed = seed;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedWhenEmpty() {
        if (seed == null || seed.isBlank() || !Files.isRegularFile(Path.of(seed))) {
            return;
        }
        try (var c = connections.open(); var s = c.prepareStatement("select count(*) from lgd_entity");
                var r = s.executeQuery()) {
            r.next();
            if (r.getLong(1) > 0) {
                return;
            }
        } catch (Exception e) {
            log.warn("Cannot read the geography master: {}", e.getMessage());
            return;
        }
        var result = imports.importSnapshot(FileLgdSourceClient.read(Path.of(seed)), "statepack-service-seed");
        log.info("Geography master seeded from {}: {} ({} rows)", Path.of(seed).getFileName(), result.status(),
                result.rows());
    }
}
