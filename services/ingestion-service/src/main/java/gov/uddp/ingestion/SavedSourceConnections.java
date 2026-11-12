package gov.uddp.ingestion;

import gov.uddp.ingestion.connector.ConnectorSecrets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Database-source connections saved from the Onboarding Studio, kept in this service's own schema. The address is in
 * source_connection; the password goes to the encrypted connector credentials and never comes back out of this
 * service.
 */
@Component
public class SavedSourceConnections {

    private final DataSource dataSource;
    private final ConnectorSecrets secrets;

    public SavedSourceConnections(DataSource dataSource, ConnectorSecrets secrets) {
        this.dataSource = dataSource;
        this.secrets = secrets;
    }

    static String secretName(String name) {
        return "source-connection:" + name;
    }

    public Optional<SourceConnections.Connection> find(String name) {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("select host, port, database_name,"
                + " user_name from source_connection where name = ?")) {
            s.setString(1, name);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(connection(name, r));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot read source connection " + name, e);
        }
    }

    /** Every saved connection, by name; passwords included, so only for use inside this service. */
    public Map<String, SourceConnections.Connection> all() {
        Map<String, SourceConnections.Connection> out = new LinkedHashMap<>();
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("select name, host, port, database_name,"
                + " user_name from source_connection order by name"); var r = s.executeQuery()) {
            while (r.next()) {
                out.put(r.getString(1), connection(r.getString(1), r));
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot read source connections", e);
        }
    }

    public void save(String name, SourceConnections.Connection connection) {
        secrets.put(secretName(name), connection.getPassword() == null ? "" : connection.getPassword());
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("insert into source_connection (name, host,"
                + " port, database_name, user_name) values (?, ?, ?, ?, ?) on conflict (name) do update set host ="
                + " excluded.host, port = excluded.port, database_name = excluded.database_name, user_name ="
                + " excluded.user_name, updated_at = now()")) {
            s.setString(1, name);
            s.setString(2, connection.getHost());
            s.setInt(3, connection.getPort());
            s.setString(4, connection.getDatabase());
            s.setString(5, connection.getUser());
            s.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot save source connection " + name, e);
        }
    }

    private SourceConnections.Connection connection(String name, java.sql.ResultSet r) throws java.sql.SQLException {
        var connection = new SourceConnections.Connection();
        connection.setHost(r.getString("host"));
        connection.setPort(r.getInt("port"));
        connection.setDatabase(r.getString("database_name"));
        connection.setUser(r.getString("user_name"));
        connection.setPassword(secrets.get(secretName(name)).orElse(""));
        return connection;
    }
}
