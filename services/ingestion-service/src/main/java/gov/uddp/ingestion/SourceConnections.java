package gov.uddp.ingestion;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connections of database-table sources, by the name a State Pack source gives (connectionRef). They are configured
 * only in this service ({@code uddp.ingestion.connections.<name>.*}, passwords from the environment), so credentials
 * never travel in commands or events. Only PostgreSQL sources are supported, as the generic template's uddp-source
 * connection is.
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

    private Map<String, Connection> connections = new LinkedHashMap<>();

    public Map<String, Connection> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, Connection> connections) {
        this.connections = connections;
    }

    public Connection get(String name) {
        Connection connection = name == null ? null : connections.get(name);
        if (connection == null || connection.host == null || connection.database == null || connection.user == null) {
            throw new IllegalArgumentException("No source connection named " + name + " is configured in"
                    + " ingestion-service (uddp.ingestion.connections." + name + ".*)");
        }
        return connection;
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

    /** The configured connection names and where they point (never the password), for the Studio's list. */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        connections.forEach((name, c) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", name);
            view.put("host", c.host);
            view.put("port", c.port);
            view.put("database", c.database);
            view.put("user", c.user);
            view.put("passwordSet", c.password != null && !c.password.isEmpty());
            out.add(view);
        });
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
