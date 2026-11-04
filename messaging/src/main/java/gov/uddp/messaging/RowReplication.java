package gov.uddp.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replicates rows between services whose tables have the same definition (MSA Phase 3): the owner reads rows as JSON
 * ({@code row_to_json}) on its own transaction, and a read-model holder upserts them by primary key, changing a row
 * only when it differs (so append-only tables, whose rows never change, are never updated). Used for the Golden
 * replica (registry-service to the edge) and for stewardship decisions (edge to registry-service). A foreign key to
 * a row another event has not delivered yet makes the event wait ({@link Inbox.NotYetApplicable}).
 */
public final class RowReplication {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private RowReplication() {
    }

    /** One table and the condition (with its parameters) selecting the rows to replicate. */
    public record Selection(String table, String where, List<Object> parameters) {
    }

    /** {@code {table: [row, ...]}} in selection order; a table selected twice is read twice. */
    public static ObjectNode read(Connection c, List<Selection> selections) throws SQLException {
        ObjectNode out = Outbox.json().createObjectNode();
        for (Selection selection : selections) {
            ArrayNode rows = out.has(selection.table()) ? (ArrayNode) out.get(selection.table())
                    : out.putArray(selection.table());
            try (var s = c.prepareStatement("select row_to_json(t)::text from " + selection.table() + " t where "
                    + selection.where())) {
                for (int i = 0; i < selection.parameters().size(); i++) {
                    s.setObject(i + 1, selection.parameters().get(i));
                }
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        rows.add(readTree(r.getString(1)));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Upserts the rows table by table in {@code order} (parents first). {@code deferredColumns} are written in a
     * second pass, for self-references such as a Golden Resident merged into another one of the same batch.
     */
    public static void apply(Connection c, JsonNode rows, List<String> order,
                             Map<String, List<String>> deferredColumns) throws SQLException {
        try {
            for (String table : order) {
                JsonNode tableRows = rows.path(table);
                if (!tableRows.isArray() || tableRows.isEmpty()) {
                    continue;
                }
                List<String> keys = primaryKey(c, table);
                // identity columns (GENERATED ALWAYS) keep the owner's value on insert and are never updated
                List<String> columns = columns(c, table).stream()
                        .filter(column -> !identityColumns(c, table).contains(column)).toList();
                List<String> deferred = deferredColumns.getOrDefault(table, List.of());
                for (JsonNode row : tableRows) {
                    ObjectNode first = row.deepCopy();
                    deferred.forEach(column -> first.putNull(column));
                    upsert(c, table, keys, columns, deferred, first);
                }
                if (!deferred.isEmpty()) {
                    for (JsonNode row : tableRows) {
                        upsert(c, table, keys, columns, List.of(), row);
                    }
                }
            }
        } catch (SQLException e) {
            if (FOREIGN_KEY_VIOLATION.equals(e.getSQLState())) {
                throw new Inbox.NotYetApplicable("a referenced row has not been replicated yet: " + e.getMessage());
            }
            throw e;
        }
    }

    private static void upsert(Connection c, String table, List<String> keys, List<String> columns,
                               List<String> keepExisting, JsonNode row) throws SQLException {
        List<String> updatable = new ArrayList<>();
        for (String column : columns) {
            if (!keys.contains(column)) {
                updatable.add(column);
            }
        }
        String set = String.join(", ", updatable.stream().map(column -> keepExisting.contains(column)
                ? quote(column) + " = coalesce(" + table + "." + quote(column) + ", excluded." + quote(column) + ")"
                : quote(column) + " = excluded." + quote(column)).toList());
        String distinct = "(" + String.join(", ", updatable.stream().map(col -> table + "." + quote(col)).toList())
                + ") is distinct from (" + String.join(", ", updatable.stream().map(col -> "excluded." + quote(col))
                .toList()) + ")";
        String sql = "insert into " + table + " overriding system value select * from json_populate_record(null::"
                + table + ", cast(? as json)) on conflict (" + String.join(", ", keys.stream()
                .map(RowReplication::quote).toList()) + ")"
                + (updatable.isEmpty() ? " do nothing" : " do update set " + set + " where " + distinct);
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, row.toString());
            s.executeUpdate();
        }
    }

    private static List<String> primaryKey(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var s = c.prepareStatement("select a.attname from pg_index i join pg_attribute a on a.attrelid ="
                + " i.indrelid and a.attnum = any(i.indkey) where i.indrelid = cast(? as regclass) and i.indisprimary"
                + " order by array_position(i.indkey, a.attnum)")) {
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(table + " has no primary key to replicate by");
        }
        return out;
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var s = c.prepareStatement("select attname from pg_attribute where attrelid = cast(? as regclass)"
                + " and attnum > 0 and not attisdropped order by attnum")) {
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
        }
        return out;
    }

    private static List<String> identityColumns(Connection c, String table) {
        List<String> out = new ArrayList<>();
        try (var s = c.prepareStatement("select attname from pg_attribute where attrelid = cast(? as regclass)"
                + " and attnum > 0 and attidentity <> ''")) {
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the identity columns of " + table, e);
        }
        return out;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static JsonNode readTree(String text) {
        try {
            return Outbox.json().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read a replicated row", e);
        }
    }
}
