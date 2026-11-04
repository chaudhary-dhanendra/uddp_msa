package gov.uddp.core.testsupport;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small read helpers for assertions against a test database. */
public final class Sql {
    private final PostgresConnectionFactory connections;

    public Sql(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    /** The first column of the first row as a number (a count, or any integer value). */
    public long count(String sql, Object... parameters) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalStateException("No row: " + sql);
                }
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public UUID uuid(String sql, Object... parameters) {
        var values = column(sql, UUID.class, parameters);
        if (values.size() != 1) {
            throw new IllegalStateException("Expected one row, got " + values.size() + ": " + sql);
        }
        return values.getFirst();
    }

    public String text(String sql, Object... parameters) {
        var values = column(sql, String.class, parameters);
        if (values.size() != 1) {
            throw new IllegalStateException("Expected one row, got " + values.size() + ": " + sql);
        }
        return values.getFirst();
    }

    public <T> List<T> column(String sql, Class<T> type, Object... parameters) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            var out = new ArrayList<T>();
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    out.add(r.getObject(1, type));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute(String sql, Object... parameters) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The Golden ID that currently holds this canonical Resident. */
    public UUID goldenOf(UUID canonical) {
        return uuid("select golden_resident_id from golden_resident_membership"
                + " where canonical_resident_id=? and effective_to is null", canonical);
    }

    /** Canonical IDs of the latest canonical versions with this name, in ID order. */
    public List<UUID> canonicalsNamed(String name) {
        return column("select canonical_resident_id from canonical_resident_current"
                + " where canonical_payload->>'name'=? order by canonical_resident_id", UUID.class, name);
    }
}
