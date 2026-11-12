package gov.uddp.ingestion.connector;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Source connectors and landing folders, owned by ingestion-service; the edge's screens call these. Credentials go in
 * once (PUT) and never come back out: responses show only the names of stored credentials.
 */
@RestController
@RequestMapping("/api/ingestion")
public class ConnectorController {

    private final Connectors connectors;
    private final LandingFolders landing;
    private final gov.uddp.ingestion.SourceConnections sources;

    public ConnectorController(Connectors connectors, LandingFolders landing,
                               gov.uddp.ingestion.SourceConnections sources) {
        this.connectors = connectors;
        this.landing = landing;
        this.sources = sources;
    }

    /** The database connections configured here (names and addresses; passwords never leave this service). */
    @GetMapping("/connections")
    public List<Map<String, Object>> connections() {
        return sources.describe();
    }

    /**
     * Tests and saves a database connection from the Studio: body host, port, database, user, password (empty keeps
     * the saved one). The answer is the list entry, never the password.
     */
    @PutMapping("/connections/{name}")
    public Map<String, Object> saveConnection(@PathVariable String name, @RequestBody Map<String, Object> body) {
        Object port = body.get("port");
        Integer number;
        try {
            number = port instanceof Number n ? Integer.valueOf(n.intValue())
                    : port == null || port.toString().isBlank() ? null : Integer.valueOf(port.toString().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number");
        }
        return sources.save(name, text(body, "host"), number, text(body, "database"), text(body, "user"),
                text(body, "password"));
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : value.toString();
    }

    /** A preview of a database source read with this service's credentials: body connectionRef, table or query, limit. */
    @PostMapping("/sources/rows")
    public Map<String, Object> sourceRows(@RequestBody Map<String, Object> source) {
        String select = gov.uddp.contracts.SourceSql.select((String) source.get("table"), (String) source.get("query"));
        Object limit = source.get("limit");
        return sources.rows((String) source.get("connectionRef"), select,
                limit instanceof Number n ? n.intValue() : 20);
    }

    @GetMapping("/connectors/types")
    public List<String> types() {
        return connectors.types();
    }

    @GetMapping("/connectors/{state}/{source}")
    public ResponseEntity<Connectors.Saved> find(@PathVariable String state, @PathVariable String source) {
        return ResponseEntity.of(connectors.find(state, source));
    }

    /** Body {@code {"type": ..., "settings": {...}, "credentials": {...}}}. */
    @PutMapping("/connectors/{state}/{source}")
    @SuppressWarnings("unchecked")
    public Connectors.Saved save(@PathVariable String state, @PathVariable String source,
                                 @RequestBody Map<String, Object> body) {
        return connectors.save(state, source, (String) body.get("type"),
                (Map<String, String>) body.getOrDefault("settings", Map.of()),
                (Map<String, String>) body.getOrDefault("credentials", Map.of()));
    }

    @PostMapping("/connectors/{state}/{source}/test")
    public Map<String, Object> test(@PathVariable String state, @PathVariable String source) {
        connectors.test(state, source);
        return Map.of("ok", true);
    }

    @GetMapping("/connectors/{state}/{source}/preview")
    public Table preview(@PathVariable String state, @PathVariable String source,
                         @RequestParam(defaultValue = "20") int rows) {
        return connectors.preview(state, source, rows);
    }

    /** The files waiting in a landing folder (names only). */
    @GetMapping("/landing/waiting")
    public ResponseEntity<List<String>> waiting(@RequestParam String location) {
        if (!landing.isLandingFolder(location)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(landing.waiting(location).stream().map(file -> file.getFileName().toString())
                .toList());
    }

    /**
     * SOURCE_TESTED for what this service reads: the source's connector, its database table (with this service's
     * credentials), or a waiting file in its landing folder.
     */
    @PostMapping("/sources/test")
    public Map<String, Object> testSource(@RequestBody Map<String, String> source) {
        String state = source.get("stateCode");
        String system = source.get("sourceSystem");
        if (connectors.find(state, system).isPresent()) {
            connectors.test(state, system);
        } else if (source.get("connectionRef") != null) {
            sources.header(source.get("connectionRef"),
                    gov.uddp.contracts.SourceSql.select(source.get("table"), source.get("query")));
        } else if (landing.isLandingFolder(source.get("location"))) {
            if (landing.waiting(source.get("location")).isEmpty()) {
                throw new IllegalArgumentException("No file is waiting in the landing folder " + source.get("location"));
            }
        } else {
            throw new IllegalArgumentException("The source has no connector, database or landing folder here: "
                    + source.get("location"));
        }
        return Map.of("ok", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
