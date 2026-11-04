package gov.uddp.core.onboarding;

import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.setup.DatabaseConnectionSettings;
import gov.uddp.core.setup.LocalSettingsStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Database-table sources (ADR-0016): a table or a single read-only SELECT of a named PostgreSQL connection whose
 * credentials live only in the encrypted local settings store. Generic: it knows no table or column of any state.
 */
@Component
public class DatabaseSources {
    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?");
    private static final Pattern CONNECTION = Pattern.compile("[a-z][a-z0-9-]{0,40}");
    /** Characters a query may not contain: statement separators and characters Windows command lines expand. */
    private static final Pattern UNSAFE = Pattern.compile("[;\"<>|&^%`]");

    /** Rows read from a source: header and values in column order. */
    public record Rows(List<String> header, List<Map<String, String>> rows) {
    }

    private final LocalSettingsStore store;

    public DatabaseSources(LocalSettingsStore store) {
        this.store = store;
    }

    public DatabaseConnectionSettings connection(String name) {
        requireName(name);
        DatabaseConnectionSettings settings = store.connections().get(name);
        if (settings == null) {
            throw new IllegalArgumentException("Unknown source connection: " + name
                    + " (add it in the Onboarding Studio; credentials are stored only on this machine)");
        }
        return settings;
    }

    public static void requireName(String name) {
        if (name == null || !CONNECTION.matcher(name).matches()) {
            throw new IllegalArgumentException("Connection name must be lower case letters, digits and hyphens");
        }
    }

    /** The SELECT a source reads: the table, or the query as a sub-select. Validated, never raw user SQL alone. */
    public static String select(String table, String query) {
        return select(table, query, true);
    }

    /** As {@link #select(String, String)}; {@code asKeyword} false omits AS before the sub-select alias (Oracle). */
    public static String select(String table, String query, boolean asKeyword) {
        if (query != null && !query.isBlank()) {
            String trimmed = query.strip();
            if (!trimmed.regionMatches(true, 0, "select ", 0, 7) || UNSAFE.matcher(trimmed).find()) {
                throw new IllegalArgumentException("A source query must be one SELECT statement without"
                        + " ; \" < > | & ^ % or ` characters");
            }
            return "select * from (" + trimmed + ")" + (asKeyword ? " as " : " ") + "source_query";
        }
        if (table == null || !TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("Table must be a table name, optionally schema-qualified");
        }
        return "select * from " + table;
    }

    /**
     * The SELECT the generic Hop template runs: one column {@code raw_source_line} holding each row as a CSV line in
     * column order (values with a comma, quote or line break are quoted), the same opaque line a file delivers.
     * Built only from functions and single-quoted literals, so it survives any command line unchanged.
     */
    public static String rawLineSelect(String table, String query) {
        return "select (select string_agg(case when v is null then '' when position(',' in v) > 0"
                + " or position(chr(34) in v) > 0 or position(chr(10) in v) > 0"
                + " then concat(chr(34), replace(v, chr(34), concat(chr(34), chr(34))), chr(34)) else v end,"
                + " ',' order by ord) from json_each_text(row_to_json(source_rows)) with ordinality as e(k, v, ord))"
                + " as raw_source_line from (" + select(table, query) + ") as source_rows";
    }

    public Rows read(String connectionName, String table, String query, int limit) {
        DatabaseConnectionSettings settings = connection(connectionName);
        String sql = select(table, query) + " limit " + Math.max(1, limit);
        try (Connection c = open(settings)) {
            c.setReadOnly(true);
            try (var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
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
                return new Rows(header, rows);
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Cannot read the source: "
                    + SecretRedactor.redact(e.getMessage(), List.of(settings.password())), e);
        }
    }

    /** Opens the connection and runs the source's SELECT once, for the SOURCE_TESTED stage and the Studio's Test. */
    public void test(String connectionName, String table, String query) {
        read(connectionName, table, query, 1);
    }

    /** UDDP_SOURCE_* settings for the generic Hop template's {@code uddp-source} connection. Never log them. */
    public Map<String, String> hopVariables(String connectionName) {
        DatabaseConnectionSettings settings = connection(connectionName);
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("UDDP_SOURCE_HOST", settings.host());
        variables.put("UDDP_SOURCE_PORT", Integer.toString(settings.port()));
        variables.put("UDDP_SOURCE_DB", settings.database());
        variables.put("UDDP_SOURCE_USER", settings.user());
        variables.put("UDDP_SOURCE_PASSWORD", settings.password());
        return variables;
    }

    private static Connection open(DatabaseConnectionSettings settings) throws SQLException {
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
    }
}
