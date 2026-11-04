package gov.uddp.core.api;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.setup.PlatformDatabaseSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness plus the setup state: {@code mode} is SETUP until a platform database is configured, then READY. */
@RestController
public class HealthController {
    private final PostgresConnectionFactory connections;
    private final PlatformDatabaseSettings settings;

    public HealthController(PostgresConnectionFactory connections, PlatformDatabaseSettings settings) {
        this.connections = connections;
        this.settings = settings;
    }

    @GetMapping("/health")
    @PreAuthorize("permitAll()")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "uddp-common-core");
        body.put("mode", connections.isConfigured() ? "READY" : "SETUP");
        body.put("databaseSource", settings.source().name());
        return body;
    }
}
