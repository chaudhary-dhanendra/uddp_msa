package gov.uddp.ingestion.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.ingestion.connector.SourceConnector.Config;
import gov.uddp.ingestion.connector.SourceConnector.Delivery;
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
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * A source's connector in ingestion-service (Track C5, moved from the edge): saved per state and source (settings in
 * source_connector, credentials in the encrypted connector_secret), tested, previewed, and used to stage a delivery as
 * an ordinary CSV file that the generic file template lands. Nothing here knows a department or a state.
 */
@Service
public class Connectors {

    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,40}");
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() { };

    /** What is saved for a source; secrets are not part of it, only the names of stored credentials. */
    public record Saved(String type, Map<String, String> config) {
    }

    /** A delivery staged as a CSV file, and the follow-up once it was ingested. */
    public record Staged(Path file, Runnable completed) {
    }

    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();
    private final ConnectorSecrets secrets;
    private final Map<String, SourceConnector> byType;
    private final Path staging;

    public Connectors(DataSource dataSource, ConnectorSecrets secrets, List<SourceConnector> connectors,
                      @Value("${uddp.ingestion.landing-directory:${java.io.tmpdir}/uddp-landing}") String landing) {
        this.dataSource = dataSource;
        this.secrets = secrets;
        this.byType = connectors.stream().collect(Collectors.toMap(SourceConnector::type, Function.identity()));
        this.staging = Path.of(landing).toAbsolutePath().normalize().resolve("deliveries");
    }

    public List<String> types() {
        return byType.keySet().stream().sorted().toList();
    }

    public Optional<Saved> find(String stateCode, String sourceSystem) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("select connector_type, config::text"
                + " from source_connector where state_code = ? and source_system = ?")) {
            s.setString(1, stateCode);
            s.setString(2, sourceSystem);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(new Saved(r.getString(1), json.readValue(r.getString(2), STRINGS)))
                        : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the source connector", e);
        }
    }

    /**
     * Saves the connector. {@code credentials} (field name to value) are encrypted here and only their names are
     * kept in the settings; a field left out keeps its earlier credential.
     */
    public Saved save(String stateCode, String sourceSystem, String type, Map<String, String> settings,
                      Map<String, String> credentials) {
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
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("insert into source_connector (state_code,"
                + " source_system, connector_type, config) values (?, ?, ?, cast(? as jsonb)) on conflict"
                + " (state_code, source_system) do update set connector_type = excluded.connector_type,"
                + " config = excluded.config, updated_at = now()")) {
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
        var saved = require(stateCode, sourceSystem);
        connector(saved.type()).test(config(saved));
    }

    public Table preview(String stateCode, String sourceSystem, int rows) {
        var saved = require(stateCode, sourceSystem);
        return connector(saved.type()).preview(config(saved), Math.max(1, Math.min(rows, 1000)));
    }

    /** Fetches the source's delivery into a CSV file; empty when the source has no connector. */
    public Optional<Staged> stage(String stateCode, String sourceSystem) {
        var saved = find(stateCode, sourceSystem);
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        Delivery delivery = connector(saved.get().type()).fetch(config(saved.get()));
        try {
            Path directory = Files.createDirectories(staging.resolve(stateCode).resolve(sourceSystem));
            Path file = directory.resolve(Instant.now().toEpochMilli() + ".csv");
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(out, CSVFormat.RFC4180.builder().setRecordSeparator("\n")
                            .build())) {
                printer.printRecord(delivery.table().header());
                for (var row : delivery.table().rows()) {
                    printer.printRecord(delivery.table().header().stream().map(row::get).toList());
                }
            }
            return Optional.of(new Staged(file, delivery.completed()));
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
