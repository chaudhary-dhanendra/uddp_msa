package gov.uddp.ingestion.connector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A generic source type (Track C5). A connector reads one department source into rows; nothing here knows a
 * department, scheme or source. Credentials are never part of the configuration: a secret field is the name of a
 * credential in the encrypted settings store and is resolved only when the connector runs.
 */
public interface SourceConnector {
    /** The type name stored with the source: excel, jdbc, sftp or rest. */
    String type();

    /** Reaches the source and reads enough to prove it works. Throws IllegalArgumentException with a safe message. */
    void test(Config config);

    /** The first {@code limit} rows, for the Studio preview and profiling. */
    Table preview(Config config, int limit);

    /** The current delivery in full. {@link Delivery#completed()} runs once the delivery was ingested. */
    Delivery fetch(Config config);

    /** A fetched delivery and what to do once it was ingested (for example archive the picked-up file). */
    record Delivery(Table table, Runnable completed) {
        public static Delivery of(Table table) {
            return new Delivery(table, () -> { });
        }
    }

    /** The saved settings of a source: plain values plus resolution of the credentials they name. */
    record Config(Map<String, String> values, ConnectorSecrets secrets) {
        public Config {
            values = new LinkedHashMap<>(values);
        }

        public String required(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Connector setting '" + key + "' is required");
            }
            return value.strip();
        }

        public String optional(String key, String fallback) {
            String value = values.get(key);
            return value == null || value.isBlank() ? fallback : value.strip();
        }

        public int integer(String key, int fallback) {
            String value = optional(key, null);
            try {
                return value == null ? fallback : Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Connector setting '" + key + "' must be a number");
            }
        }

        /** The credential named by {@code <field>Ref}, from the encrypted store. */
        public Optional<String> secret(String field) {
            String ref = values.get(field + "Ref");
            return ref == null || ref.isBlank() ? Optional.empty() : secrets.get(ref);
        }

        public String requiredSecret(String field) {
            return secret(field).orElseThrow(() -> new IllegalArgumentException(
                    "The credential for '" + field + "' is not saved; enter it again in the Studio"));
        }
    }
}
