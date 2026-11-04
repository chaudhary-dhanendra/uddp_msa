package gov.uddp.core.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a short-lived connection to the platform database. Lets modules outside {@code core.canonical} (for example
 * the shaping administration) use the database without depending on the canonical layer.
 */
@FunctionalInterface
public interface ConnectionSource {
    Connection open() throws SQLException;
}
