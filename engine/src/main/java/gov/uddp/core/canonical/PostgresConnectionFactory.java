package gov.uddp.core.canonical;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Opens PostgreSQL connections configured by UDDP_POSTGRES_* settings, from a small pool per set of settings
 * (opening a physical connection per call cost tens of milliseconds, which dominated per-event work at volume).
 * UDDP_POSTGRES_URL is used when set; otherwise the URL is built from HOST, PORT and DB.
 * There are no defaults: a missing setting fails with an error naming it.
 * The application's instance ({@code setup.SetupConfiguration}) looks the settings up on every call, so settings
 * applied from the Setup screen take effect without a restart; UDDP_POSTGRES_* environment variables win when set.
 * Tests use {@link #forSettings} to point at a disposable database instead of the environment.
 */
public class PostgresConnectionFactory implements gov.uddp.core.database.ConnectionSource {
    static final String URL = "UDDP_POSTGRES_URL";
    static final String HOST = "UDDP_POSTGRES_HOST";
    static final String PORT = "UDDP_POSTGRES_PORT";
    static final String DB = "UDDP_POSTGRES_DB";
    static final String USER = "UDDP_POSTGRES_USER";
    static final String PASSWORD = "UDDP_POSTGRES_PASSWORD";

    private static final Pattern JDBC_URL = Pattern.compile("jdbc:postgresql://([^:/?]+)(?::([0-9]+))?/([^?]+).*");

    private final Function<String, String> environment;

    public PostgresConnectionFactory() {
        this(System::getenv);
    }

    PostgresConnectionFactory(Function<String, String> environment) {
        this.environment = environment;
    }

    /** A factory whose settings are looked up by name (UDDP_POSTGRES_*) each time they are needed. */
    public static PostgresConnectionFactory forLookup(Function<String, String> lookup) {
        return new PostgresConnectionFactory(lookup);
    }

    /** A factory for explicit settings, used by tests and tools that own a disposable database. */
    public static PostgresConnectionFactory forSettings(String url, String user, String password) {
        return new PostgresConnectionFactory(Map.of(URL, url, USER, user, PASSWORD, password)::get);
    }

    private static final ThreadLocal<Connection> BOUND = new ThreadLocal<>();

    /**
     * A new connection, or, inside {@link #inTransaction}, the calling thread's transaction connection: closing, committing, making read-only
     * and switching auto-commit on it do nothing, so repositories that open and close a connection per call take part in
     * the one transaction.
     */
    public Connection open() throws SQLException {
        Connection bound = BOUND.get();
        if (bound != null) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "close", "commit", "setAutoCommit", "setReadOnly", "setTransactionIsolation" -> {
                                return null;
                            }
                            case "isClosed" -> {
                                return false;
                            }
                            default -> {
                                try {
                                    return method.invoke(bound, args);
                                } catch (java.lang.reflect.InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            }
                        }
                    });
        }
        return pooled();
    }

    /** Pools shared by every factory with the same settings; new settings (Setup screen) get a new pool. */
    private static final Map<String, com.zaxxer.hikari.HikariDataSource> POOLS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Connections per pool; small, as several services may share one database server. */
    static final int POOL_SIZE = Integer.getInteger("uddp.database.pool-size", 8);

    private Connection pooled() throws SQLException {
        String url = url();
        String user = required(USER);
        String password = required(PASSWORD);
        String key = url + "|" + user + "|" + Integer.toHexString(password.hashCode());
        var pool = POOLS.computeIfAbsent(key, k -> {
            var config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(POOL_SIZE);
            config.setMinimumIdle(0);
            config.setIdleTimeout(60_000);
            config.setPoolName("uddp-" + Integer.toHexString(k.hashCode()));
            return new com.zaxxer.hikari.HikariDataSource(config);
        });
        return pool.getConnection();
    }

    /**
     * Runs {@code work} in one database transaction: everything it does through {@link #open()} on this thread commits
     * together, or rolls back together when it throws.
     */
    public <T> T inTransaction(java.util.function.Supplier<T> work) {
        if (BOUND.get() != null) {
            return work.get();
        }
        try (Connection connection = pooled()) {
            connection.setAutoCommit(false);
            BOUND.set(connection);
            try {
                T result = work.get();
                connection.commit();
                return result;
            } catch (RuntimeException | Error e) {
                connection.rollback();
                throw e;
            } finally {
                BOUND.remove();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot run the database transaction", e);
        }
    }

    /** A non-pooling DataSource over the same settings, for Flyway. */
    public DataSource dataSource() {
        return new DriverManagerDataSource(url(), required(USER), required(PASSWORD));
    }

    /** True when every setting needed to open a connection is present. */
    public boolean isConfigured() {
        return missingVariables().isEmpty();
    }

    /**
     * UDDP_POSTGRES_HOST, PORT, DB, USER and PASSWORD for a child process (Hop, Python matching). When only
     * UDDP_POSTGRES_URL is set, host, port and database are taken from it. Never log the result: it holds the password.
     */
    public Map<String, String> processVariables() {
        Map<String, String> variables = new LinkedHashMap<>();
        String url = environment.apply(URL);
        if (present(url)) {
            var matcher = JDBC_URL.matcher(url);
            if (!matcher.matches()) {
                throw new IllegalStateException("UDDP_POSTGRES_URL is not a jdbc:postgresql://host:port/database URL");
            }
            variables.put(HOST, matcher.group(1));
            variables.put(PORT, matcher.group(2) == null ? "5432" : matcher.group(2));
            variables.put(DB, matcher.group(3));
            // MSA: a service's URL names its own schema (currentSchema=...); a child process (Python) gets the same
            // search path through libpq's PGOPTIONS, so its unqualified SQL reads that schema too.
            var schema = java.util.regex.Pattern.compile("[?&]currentSchema=([A-Za-z0-9_,]+)").matcher(url);
            if (schema.find()) {
                variables.put("PGOPTIONS", "-c search_path=" + schema.group(1));
            }
        } else {
            variables.put(HOST, required(HOST));
            variables.put(PORT, required(PORT));
            variables.put(DB, required(DB));
        }
        variables.put(USER, required(USER));
        variables.put(PASSWORD, required(PASSWORD));
        return variables;
    }

    /** Values that must never appear in stored or returned text (see {@code SecretRedactor}). */
    public List<String> secrets() {
        String password = environment.apply(PASSWORD);
        return present(password) ? List.of(password) : List.of();
    }

    String url() {
        String url = environment.apply(URL);
        if (present(url)) {
            return url;
        }
        return "jdbc:postgresql://" + required(HOST) + ":" + required(PORT) + "/" + required(DB);
    }

    List<String> missingVariables() {
        var required = present(environment.apply(URL)) ? List.of(USER, PASSWORD) : List.of(HOST, PORT, DB, USER, PASSWORD);
        return required.stream().filter(name -> !present(environment.apply(name))).toList();
    }

    private String required(String name) {
        String value = environment.apply(name);
        if (!present(value)) {
            throw new IllegalStateException("Missing required setting " + name
                    + "; configure the platform database on the Setup screen or set UDDP_POSTGRES_* variables");
        }
        return value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
