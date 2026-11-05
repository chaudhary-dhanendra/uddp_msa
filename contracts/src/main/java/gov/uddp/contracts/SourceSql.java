package gov.uddp.contracts;

import java.util.regex.Pattern;

/**
 * How a database-table source becomes Raw (ADR-0016): the SELECT the generic template generic-db-ingest.hpl runs, one
 * column {@code raw_source_line} holding each row as a CSV line in column order, the same opaque line a file delivers.
 * Shared by the edge (which validates a source) and ingestion-service (which runs it), so both build the same SQL.
 * Built only from functions and single-quoted literals, so it survives any command line unchanged.
 */
public final class SourceSql {

    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?");
    /** Characters a query may not contain: statement separators and characters Windows command lines expand. */
    private static final Pattern UNSAFE = Pattern.compile("[;\"<>|&^%`]");

    private SourceSql() {
    }

    /** {@code select * from <table>} or the source's own single SELECT, validated. */
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

    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    /** A source's change column (delta loads): a plain column name. */
    public static String changeColumn(String column) {
        if (column == null || !COLUMN.matcher(column).matches()) {
            throw new IllegalArgumentException("A change column must be a plain column name");
        }
        return column;
    }

    /** {@code select max(column)} of the source as text: the high-water mark a delta load reads up to. */
    public static String maxChange(String table, String query, String column) {
        return "select cast(max(" + changeColumn(column) + ") as text) from (" + select(table, query)
                + ") as change_rows";
    }

    /**
     * The source's rows whose change column lies after {@code after} (null: from the start) and up to
     * {@code upTo}, the high-water mark read before the load, so rows changed during the load wait for the next one.
     */
    public static String deltaSelect(String table, String query, String column, String after, String upTo) {
        String col = changeColumn(column);
        String filter = (after == null ? "" : col + " > " + literal(after) + " and ") + col + " <= " + literal(upTo);
        return "select * from (" + select(table, query) + ") as delta_rows where " + filter;
    }

    private static String literal(String value) {
        if (value == null || UNSAFE.matcher(value).find()) {
            throw new IllegalArgumentException("Unusable change value " + value);
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** The SELECT generic-db-ingest.hpl runs (UDDP_SOURCE_SQL). */
    public static String rawLineSelect(String table, String query) {
        return rawLineSelectOf(select(table, query));
    }

    /** As {@link #rawLineSelect(String, String)} over an already built SELECT (a delta load). */
    public static String rawLineSelectOf(String select) {
        return "select (select string_agg(case when v is null then '' when position(',' in v) > 0"
                + " or position(chr(34) in v) > 0 or position(chr(10) in v) > 0"
                + " then concat(chr(34), replace(v, chr(34), concat(chr(34), chr(34))), chr(34)) else v end,"
                + " ',' order by ord) from json_each_text(row_to_json(source_rows)) with ordinality as e(k, v, ord))"
                + " as raw_source_line from (" + select + ") as source_rows";
    }
}
