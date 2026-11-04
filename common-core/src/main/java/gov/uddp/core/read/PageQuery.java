package gov.uddp.core.read;

import java.util.Locale;
import java.util.Map;

/**
 * Validated paging and sorting for a read API list. Sort keys come from a per-endpoint allow-list that maps the API
 * field name to a SQL expression, so a request can never inject SQL; anything else is a 400.
 */
public record PageQuery(int page, int size, String sortField, boolean ascending, String orderBy) {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    /**
     * @param sort         {@code field} or {@code field,asc|desc}; null uses {@code defaultSort}
     * @param sortable     API field name to SQL expression
     * @param defaultSort  for example {@code startedAt,desc}
     * @param tieBreaker   SQL expression appended to every order so pages are stable
     */
    public static PageQuery of(Integer page, Integer size, String sort, Map<String, String> sortable,
                               String defaultSort, String tieBreaker) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw new IllegalArgumentException("page must be 0 or greater");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        String requested = sort == null || sort.isBlank() ? defaultSort : sort.strip();
        String[] parts = requested.split(",");
        String field = parts[0].strip();
        String expression = sortable.get(field);
        if (expression == null || parts.length > 2) {
            throw new IllegalArgumentException("Unsupported sort '" + requested + "'; sortable fields: "
                    + String.join(", ", new java.util.TreeSet<>(sortable.keySet())));
        }
        boolean ascending = true;
        if (parts.length == 2) {
            String direction = parts[1].strip().toLowerCase(Locale.ROOT);
            if (!direction.equals("asc") && !direction.equals("desc")) {
                throw new IllegalArgumentException("Sort direction must be asc or desc");
            }
            ascending = direction.equals("asc");
        }
        String order = expression + (ascending ? " asc nulls last" : " desc nulls last") + ", " + tieBreaker;
        return new PageQuery(p, s, field, ascending, order);
    }

    public int offset() {
        return page * size;
    }

    public String sortParameter() {
        return sortField + "," + (ascending ? "asc" : "desc");
    }

    /** {@code order by ... limit ... offset ...} for this page. */
    public String sql() {
        return " order by " + orderBy + " limit " + size + " offset " + offset();
    }
}
