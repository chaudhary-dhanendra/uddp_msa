package gov.uddp.messaging;

import java.sql.Connection;
import java.sql.SQLException;

/** Opens a connection to the owning service's own database; the caller closes it. */
@FunctionalInterface
public interface ConnectionSource {
    Connection open() throws SQLException;
}
