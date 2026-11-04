package gov.uddp.core.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.connector.SourceConnector.Config;
import gov.uddp.core.connector.SourceConnector.Delivery;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.studio.CsvProfiler;
import gov.uddp.core.studio.CsvProfiler.Table;
import gov.uddp.core.studio.StudioModels.Profile;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Saves a source's connector, tests it, previews and profiles it, and stages its deliveries (Track C5). A staged
 * delivery is an ordinary CSV file that the generic file-ingest pipeline lands, so change detection (C1), schedules
 * (C2) and everything downstream treat every connector type alike.
 */
@Service
public class ConnectorService {
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,40}");
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() { };

    /** What is saved for a source; secrets are not part of it. */
    public record Saved(String type, Map<String, String> config) {
    }

    /** A delivery staged as a CSV file, the source definition that reads it, and the follow-up after ingestion. */
    public record Staged(SourceDefinition source, Runnable completed) {
    }

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;
    private final ConnectorSecrets secrets;
    private final CsvProfiler profiler;
    private final Map<String, SourceConnector> byType;
    private final Path stagingDirectory;

    @Autowired
    public ConnectorService(PostgresConnectionFactory connections, ObjectMapper json, ConnectorSecrets secrets,
                            CsvProfiler profiler, List<SourceConnector> connectors,
                            @Value("${uddp.connector.staging-directory:../studio-workspace/deliveries}") String staging) {
        this.connections = connections;
        this.json = json;
        this.secrets = secrets;
        this.profiler = profiler;
        this.byType = connectors.stream().collect(Collectors.toMap(SourceConnector::type, Function.identity()));
        this.stagingDirectory = Path.of(staging);
    }

    private gov.uddp.core.events.IngestionClient remote;

    /** MSA: with ingestion-service owning connectors, every call goes there and no credential stays at the edge. */
    @Autowired(required = false)
    public void setRemote(gov.uddp.core.events.IngestionClient remote) {
        this.remote = remote;
    }

    private String path(String stateCode, String sourceSystem) {
        return "api/ingestion/connectors/" + gov.uddp.core.events.IngestionClient.encode(stateCode) + "/"
                + gov.uddp.core.events.IngestionClient.encode(sourceSystem);
    }

    private Saved saved(com.fasterxml.jackson.databind.JsonNode node) {
        return new Saved(node.path("type").asText(), json.convertValue(node.path("config"), STRINGS));
    }

    private Table table(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> header = json.convertValue(node.path("header"), new TypeReference<List<String>>() { });
        List<Map<String, String>> rows = json.convertValue(node.path("rows"),
                new TypeReference<List<Map<String, String>>>() { });
        return new Table(header, rows, node.path("totalRows").asLong(rows.size()));
    }

    public List<String> types() {
        if (remote != null) {
            return json.convertValue(remote.get("api/ingestion/connectors/types").orElseThrow(),
                    new TypeReference<List<String>>() { });
        }
        return byType.keySet().stream().sorted().toList();
    }

    public Optional<Saved> find(String stateCode, String sourceSystem) {
        if (remote != null) {
            return remote.get(path(stateCode, sourceSystem)).map(this::saved);
        }
        try (var c = connections.open();
                var s = c.prepareStatement("select connector_type,config::text from source_connector"
                        + " where state_code=? and source_system=?")) {
            s.setString(1, stateCode);
            s.setString(2, sourceSystem);
            var r = s.executeQuery();
            return r.next() ? Optional.of(new Saved(r.getString(1), json.readValue(r.getString(2), STRINGS)))
                    : Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the source connector", e);
        }
    }

    /**
     * Saves the connector. {@code credentials} (field name to value, for example password) go to the encrypted store
     * and only their store name is kept in the settings; a field left out keeps its earlier credential.
     */
    public Saved save(String stateCode, String sourceSystem, String type, Map<String, String> settings,
                      Map<String, String> credentials) {
        if (remote != null) {
            return saved(remote.put(path(stateCode, sourceSystem), Map.of("type", type, "settings", settings,
                    "credentials", credentials)));
        }
        connector(type);
        Map<String, String> values = new LinkedHashMap<>();
        settings.forEach((key, value) -> {
            if (!FIELD.matcher(key).matches() || key.endsWith("Ref")) {
                throw new IllegalArgumentException("Invalid connector setting: " + key);
            }
            values.put(key, value);
        });
        find(stateCode, sourceSystem).filter(old -> old.type().equals(type))
                .ifPresent(old -> old.config().forEach((key, value) -> {
                    if (key.endsWith("Ref")) {
                        values.putIfAbsent(key, value);
                    }
                }));
        credentials.forEach((field, value) -> {
            if (!FIELD.matcher(field).matches() || value == null || value.isEmpty()) {
                throw new IllegalArgumentException("Invalid credential field: " + field);
            }
            String name = "connector." + stateCode + "." + sourceSystem + "." + field;
            secrets.put(name, value);
            values.put(field + "Ref", name);
        });
        try (var c = connections.open();
                var s = c.prepareStatement("insert into source_connector(state_code,source_system,connector_type,config)"
                        + " values(?,?,?,cast(? as jsonb)) on conflict (state_code,source_system) do update set"
                        + " connector_type=excluded.connector_type,config=excluded.config,updated_at=now()")) {
            s.setString(1, stateCode);
            s.setString(2, sourceSystem);
            s.setString(3, type);
            s.setString(4, json.writeValueAsString(values));
            s.executeUpdate();
            return new Saved(type, values);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the source connector", e);
        }
    }

    public void test(String stateCode, String sourceSystem) {
        if (remote != null) {
            remote.post(path(stateCode, sourceSystem) + "/test", Map.of());
            return;
        }
        var saved = require(stateCode, sourceSystem);
        connector(saved.type()).test(config(saved));
    }

    public Table preview(String stateCode, String sourceSystem, int rows) {
        if (remote != null) {
            return table(remote.get(path(stateCode, sourceSystem) + "/preview?rows=" + Math.max(1, Math.min(rows, 200)))
                    .orElseThrow(() -> new IllegalArgumentException("The source has no connector yet")));
        }
        var saved = require(stateCode, sourceSystem);
        return connector(saved.type()).preview(config(saved), Math.max(1, Math.min(rows, 200)));
    }

    public Profile profile(String stateCode, String sourceSystem) {
        if (remote != null) {
            return profiler.profile(table(remote.get(path(stateCode, sourceSystem) + "/preview?rows=1000")
                    .orElseThrow(() -> new IllegalArgumentException("The source has no connector yet"))));
        }
        var saved = require(stateCode, sourceSystem);
        return profiler.profile(connector(saved.type()).preview(config(saved), 1000));
    }

    /**
     * Fetches the source's delivery and writes it as a CSV file for the generic file-ingest pipeline; empty when the
     * source has no connector (it reads its configured file or database as before).
     */
    public Optional<Staged> stage(String stateCode, SourceDefinition source) {
        if (remote != null) {
            return Optional.empty(); // ingestion-service stages its own deliveries
        }
        var saved = find(stateCode, source.sourceId());
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        Delivery delivery = connector(saved.get().type()).fetch(config(saved.get()));
        try {
            Path directory = stagingDirectory.resolve(stateCode).resolve(source.sourceId());
            Files.createDirectories(directory);
            Path file = directory.resolve(Instant.now().toEpochMilli() + ".csv").toAbsolutePath();
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(out, CSVFormat.RFC4180.builder().setRecordSeparator("\n").build())) {
                printer.printRecord(delivery.table().header());
                for (var row : delivery.table().rows()) {
                    printer.printRecord(delivery.table().header().stream().map(row::get).toList());
                }
            }
            var staged = new SourceDefinition(source.sourceId(), "file", "generic-file-ingest.hpl",
                    source.connectionRef(), file.toString(), source.recordIdField(), source.sourceSchemaVersion(),
                    source.recordKeyFields());
            return Optional.of(new Staged(staged, delivery.completed()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot stage the delivery", e);
        }
    }

    private Saved require(String stateCode, String sourceSystem) {
        return find(stateCode, sourceSystem).orElseThrow(
                () -> new IllegalArgumentException("The source has no connector yet"));
    }

    private SourceConnector connector(String type) {
        SourceConnector connector = byType.get(type);
        if (connector == null) {
            throw new IllegalArgumentException("Unknown connector type '" + type + "'; use one of " + types());
        }
        return connector;
    }

    private Config config(Saved saved) {
        return new Config(saved.config(), secrets);
    }
}
