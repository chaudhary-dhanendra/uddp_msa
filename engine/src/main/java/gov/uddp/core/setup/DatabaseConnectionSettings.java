package gov.uddp.core.setup;

import java.util.regex.Pattern;

/**
 * A PostgreSQL connection entered on the Setup screen (or a named source connection). {@link #toString()} never
 * includes the password.
 */
public record DatabaseConnectionSettings(String host, int port, String database, String user, String password) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]-]{1,253}");

    /** Rejects values that cannot be a PostgreSQL connection or an unquoted database name. */
    public DatabaseConnectionSettings validated() {
        if (host == null || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Host must be a host name or IP address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (database == null || !IDENTIFIER.matcher(database).matches()) {
            throw new IllegalArgumentException("Database must start with a letter or underscore and contain only"
                    + " letters, digits and underscores (at most 63 characters)");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("User is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password is required");
        }
        return this;
    }

    public String jdbcUrl() {
        return jdbcUrl(database);
    }

    public String jdbcUrl(String databaseName) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + databaseName;
    }

    public DatabaseConnectionSettings withPassword(String newPassword) {
        return new DatabaseConnectionSettings(host, port, database, user, newPassword);
    }

    @Override
    public String toString() {
        return "DatabaseConnectionSettings[host=" + host + ", port=" + port + ", database=" + database
                + ", user=" + user + ", password=******]";
    }
}
