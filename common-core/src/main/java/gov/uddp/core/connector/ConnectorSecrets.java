package gov.uddp.core.connector;

import java.util.Optional;

/** Where connector credentials live: the encrypted local settings store, never the database or a pack. */
public interface ConnectorSecrets {
    Optional<String> get(String name);

    void put(String name, String value);
}
