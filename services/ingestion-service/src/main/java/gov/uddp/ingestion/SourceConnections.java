package gov.uddp.ingestion;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connections of database-table sources, by the name a State Pack source gives (connectionRef). They live only in
 * this service, so credentials never travel in commands or events: configured in its settings
 * ({@code uddp.ingestion.connections.<name>.*}, passwords from the environment), or saved from the Onboarding Studio
 * ({@link SavedSourceConnections}, password encrypted). A configured connection wins over a saved one of the same
 * name and cannot be changed from the Studio. Only PostgreSQL sources are supported, as the generic template's
 * uddp-source connection is.
 */
@Component
@ConfigurationProperties(prefix = "uddp.ingestion")
public class SourceConnections {

    /** One source database. */
    public static class Connection {
        private String host;
        private int port = 5432;
        private String database;
        private String user;
        private String password;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }
    }

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,40}");

    private Map<String, Connection> connections = new LinkedHashMap<>();

    private SavedSourceConnections saved;

    /** Connections saved from the Studio (absent in tests that only configure connections). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useSavedConnections(SavedSourceConnections saved) {
        this.saved = saved;
    }

    public Map<String, Connection> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, Connection> connections) {
        this.connections = connections;
    }

    public Connection get(String name) {
        Connection connection = name == null ? null : connections.get(name);
        if (!complete(connection) && name != null && saved != null) {
            connection = saved.find(name).orElse(null);
        }
        if (!complete(connection)) {
            throw new IllegalArgumentException("No source connection named " + name + " is configured in"
                    + " ingestion-service (uddp.ingestion.connections." + name + ".*) or saved from the Studio");
        }
        return connection;
    }

    private static boolean complete(Connection c) {
        return c != null && c.host != null && c.database != null && c.user != null;
    }

    /**
     * Tests the connection, then saves it (address in source_connection, password encrypted) and returns how the list
     * shows it. An empty password keeps the one saved before. A connection configured in this service's settings is
     * not changed here.
     */
    public Map<String, Object> save(String name, String host, Integer port, String database, String user,
                                    String password) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Connection name must be lower case letters, digits and hyphens");
        }
        if (saved == null) {
            throw new IllegalStateException("Saving source connections is not available in this service");
        }
        if (complete(connections.get(name))) {
            throw new IllegalArgumentException("Connection " + name + " is configured in ingestion-service's settings"
                    + " (uddp.ingestion.connections." + name + ".*); change it there or use another name");
        }
        if (blank(host) || blank(database) || blank(user)) {
            throw new IllegalArgumentException("Host, database and user are required");
        }
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        Connection c = new Connection();
        c.setHost(host.strip());
        c.setPort(port);
        c.setDatabase(database.strip());
        c.setUser(user.strip());
        c.setPassword(blank(password) ? saved.find(name).map(Connection::getPassword).orElse("") : password);
        java.sql.DriverManager.setLoginTimeout(10);
        try (var connection = DriverManager.getConnection(c.jdbcUrl(), c.user, c.password)) {
            connection.isValid(5);
        } catch (java.sql.SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (c.password != null && !c.password.isEmpty()) {
                message = message.replace(c.password, "********");
            }
            throw new IllegalArgumentException("Cannot connect to " + c.host + ":" + c.port + "/" + c.database + ": "
                    + message);
        }
        saved.save(name, c);
        return view(name, c);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, Object> view(String name, Connection c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("host", c.host);
        view.put("port", c.port);
        view.put("database", c.database);
        view.put("user", c.user);
        view.put("passwordSet", c.password != null && !c.password.isEmpty());
        return view;
    }

    /** The UDDP_SOURCE_* settings of the generic template's uddp-source connection (secret: never on a command line). */
    public Map<String, String> hopSettings(String name) {
        Connection c = get(name);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("UDDP_SOURCE_HOST", c.host);
        settings.put("UDDP_SOURCE_PORT", Integer.toString(c.port));
        settings.put("UDDP_SOURCE_DB", c.database);
        settings.put("UDDP_SOURCE_USER", c.user);
        settings.put("UDDP_SOURCE_PASSWORD", c.password == null ? "" : c.password);
        return settings;
    }

    /** The configured and saved connection names and where they point (never the password), for the Studio's list. */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        connections.forEach((name, c) -> out.add(view(name, c)));
        if (saved != null) {
            saved.all().forEach((name, c) -> {
                if (!complete(connections.get(name))) {
                    out.add(view(name, c));
                }
            });
        }
        return out;
    }

    /** Up to {@code limit} rows of the source's SELECT, read-only, for a Studio preview (values as text). */
    public Map<String, Object> rows(String name, String select, int limit) {
        Connection c = get(name);
        try (var connection = DriverManager.getConnection(c.jdbcUrl(), c.user, c.password)) {
            connection.setReadOnly(true);
            try (var s = connection.createStatement();
                    var r = s.executeQuery(select + " limit " + Math.max(1, Math.min(limit, 100_000)))) {
                int count = r.getMetaData().getColumnCount();
                List<String> header = new ArrayList<>();
                for (int i = 1; i <= count; i++) {
                    header.add(r.getMetaData().getColumnLabel(i));
                }
                List<Map<String, String>> rows = new ArrayList<>();
                while (r.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= count; i++) {
                        String value = r.getString(i);
                        row.put(header.get(i - 1), value == null ? "" : value);
                    }
                    rows.add(row);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("header", header);
                out.put("rows", rows);
                return out;
            }
        } catch (java.sql.SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (c.password != null && !c.password.isEmpty()) {
                message = message.replace(c.password, "********");
            }
            throw new IllegalArgumentException("Cannot read source " + name + ": " + message, e);
        }
    }

    /** The first column of the first row of a SELECT on the source, as text (null when it has none). */
    public String scalar(String name, String select) {
        Connection c = get(name);
        try (var connection = DriverManager.getConnection(c.jdbcUrl(), c.user, c.password)) {
            connection.setReadOnly(true);
            try (var s = connection.createStatement(); var r = s.executeQuery(select)) {
                return r.next() ? r.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (c.password != null && !c.password.isEmpty()) {
                message = message.replace(c.password, "********");
            }
            throw new IllegalArgumentException("Cannot read source " + name + ": " + message, e);
        }
    }

    /** The source's column names in order (the CSV header of its raw lines), read without fetching rows. */
    public String header(String name, String select) {
        Connection c = get(name);
        try (var connection = DriverManager.getConnection(c.jdbcUrl(), c.user, c.password);
                var s = connection.createStatement();
                var r = s.executeQuery(select + " limit 0")) {
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                columns.add(r.getMetaData().getColumnLabel(i));
            }
            return String.join(",", columns);
        } catch (java.sql.SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (c.password != null && !c.password.isEmpty()) {
                message = message.replace(c.password, "********");
            }
            throw new IllegalArgumentException("Cannot read source " + name + ": " + message, e);
        }
    }
}
