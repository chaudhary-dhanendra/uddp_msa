package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.CurrentUser;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Small JDBC helper for the read side: parameterised, read-only queries over short-lived connections. */
@Component
public class Db {
    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;

    public Db(PostgresConnectionFactory connections, ObjectMapper json) {
        this.connections = connections;
        this.json = json;
    }

    @FunctionalInterface
    public interface Row<T> {
        T map(ResultSet r) throws Exception;
    }

    public <T> List<T> list(String sql, Row<T> row, Object... parameters) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            c.setReadOnly(true);
            bind(s, parameters);
            try (var r = s.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (r.next()) {
                    out.add(row.map(r));
                }
                return out;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Read query failed", e);
        }
    }

    public <T> Optional<T> one(String sql, Row<T> row, Object... parameters) {
        var rows = list(sql, row, parameters);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public long count(String sql, Object... parameters) {
        return one(sql, r -> r.getLong(1), parameters).orElse(0L);
    }

    public JsonNode json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Stored JSON is not readable", e);
        }
    }

    public static UUID uuid(ResultSet r, String column) throws SQLException {
        return r.getObject(column, UUID.class);
    }

    public static OffsetDateTime time(ResultSet r, String column) throws SQLException {
        return r.getObject(column, OffsetDateTime.class);
    }

    public static Integer integer(ResultSet r, String column) throws SQLException {
        int value = r.getInt(column);
        return r.wasNull() ? null : value;
    }

    public static Double decimal(ResultSet r, String column) throws SQLException {
        double value = r.getDouble(column);
        return r.wasNull() ? null : value;
    }

    /**
     * A filter on a state code column for the current user's state scope (ADR-0019); bind it with
     * {@link #scopeParameters()}. Every state passes when the scope is ALL.
     */
    public static String inScope(String column) {
        return " and (cast(? as text[]) is null or " + column + " = any(cast(? as text[])))";
    }

    /** The two parameters of one {@link #inScope} filter. */
    public static Object[] scopeParameters() {
        String[] codes = CurrentUser.scope().sqlCodes();
        return new Object[] {codes, codes};
    }

    /** The parameters followed by the parameters of one {@link #inScope} filter. */
    public static Object[] withScope(Object... parameters) {
        Object[] scope = scopeParameters();
        Object[] all = java.util.Arrays.copyOf(parameters, parameters.length + scope.length);
        System.arraycopy(scope, 0, all, parameters.length, scope.length);
        return all;
    }

    /** Throws {@link ReadNotFoundException} (as for an unknown ID) when the state is outside the user's scope. */
    public static void requireInScope(String stateCode, String notFoundMessage) {
        if (!CurrentUser.scope().allows(stateCode)) {
            throw new ReadNotFoundException(notFoundMessage);
        }
    }

    /** Milliseconds between two instants, or null while either is unknown. */
    public static Long millis(OffsetDateTime from, OffsetDateTime to) {
        return from == null || to == null ? null : java.time.Duration.between(from, to).toMillis();
    }

    private static void bind(PreparedStatement s, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            Object value = parameters[i];
            if (value == null) {
                s.setNull(i + 1, java.sql.Types.NULL);
            } else {
                s.setObject(i + 1, value);
            }
        }
    }
}
