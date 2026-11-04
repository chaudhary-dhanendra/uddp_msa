package gov.uddp.core.connector;

import gov.uddp.core.onboarding.DatabaseSources;
import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.studio.CsvProfiler.Table;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * One generic JDBC template for PostgreSQL, MySQL, SQL Server and Oracle (Track C5). Settings: {@code dbType},
 * {@code host}, {@code port}, {@code database} (the Oracle service name), {@code user}, the credential
 * {@code passwordRef}, and either {@code table} or {@code query} (one read-only SELECT). The drivers are optional
 * dependencies: a deployment includes only those it needs.
 */
@Component
public class JdbcConnector implements SourceConnector {
    /** What differs between the databases: URL, driver class and the sub-select alias syntax. */
    public enum Dialect {
        POSTGRESQL("org.postgresql.Driver", 5432, true),
        MYSQL("com.mysql.cj.jdbc.Driver", 3306, true),
        SQLSERVER("com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433, true),
        ORACLE("oracle.jdbc.OracleDriver", 1521, false);

        final String driverClass;
        final int defaultPort;
        final boolean asBeforeAlias;

        Dialect(String driverClass, int defaultPort, boolean asBeforeAlias) {
            this.driverClass = driverClass;
            this.defaultPort = defaultPort;
            this.asBeforeAlias = asBeforeAlias;
        }

        public String url(String host, int port, String database) {
            return switch (this) {
                case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
                case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database;
                case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database;
                case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
            };
        }

        /** The SELECT the connector runs for a table or a query; validated like every Studio database source. */
        public String select(String table, String query) {
            return DatabaseSources.select(table, query, asBeforeAlias);
        }
    }

    @Override
    public String type() {
        return "jdbc";
    }

    public static Dialect dialect(Config config) {
        String name = config.required("dbType").toUpperCase(Locale.ROOT);
        try {
            return Dialect.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("dbType must be one of postgresql, mysql, sqlserver, oracle");
        }
    }

    @Override
    public void test(Config config) {
        preview(config, 1);
    }

    @Override
    public Table preview(Config config, int limit) {
        return read(config, limit);
    }

    @Override
    public Delivery fetch(Config config) {
        return Delivery.of(read(config, 0));
    }

    /** {@code limit} 0 reads everything. */
    private Table read(Config config, int limit) {
        Dialect dialect = dialect(config);
        String sql = dialect.select(config.optional("table", null), config.optional("query", null));
        String password = config.requiredSecret("password");
        try {
            Class.forName(dialect.driverClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The " + dialect.name().toLowerCase(Locale.ROOT)
                    + " JDBC driver is not installed in this deployment");
        }
        String url = dialect.url(config.required("host"), config.integer("port", dialect.defaultPort),
                config.required("database"));
        Properties properties = new Properties();
        properties.setProperty("user", config.required("user"));
        properties.setProperty("password", password);
        DriverManager.setLoginTimeout(10);
        try (Connection c = DriverManager.getConnection(url, properties)) {
            c.setReadOnly(true);
            try (var s = c.createStatement()) {
                if (limit > 0) {
                    s.setMaxRows(limit);
                }
                try (var r = s.executeQuery(sql)) {
                    int columns = r.getMetaData().getColumnCount();
                    List<String> header = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        header.add(r.getMetaData().getColumnLabel(i));
                    }
                    List<Map<String, String>> rows = new ArrayList<>();
                    while (r.next()) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns; i++) {
                            String value = r.getString(i);
                            row.put(header.get(i - 1), value == null ? "" : value);
                        }
                        rows.add(row);
                    }
                    return new Table(header, rows);
                }
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Cannot read the source: "
                    + SecretRedactor.redact(e.getMessage(), List.of(password)), e);
        }
    }
}
