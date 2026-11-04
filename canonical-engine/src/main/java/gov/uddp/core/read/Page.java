package gov.uddp.core.read;

import java.util.List;

/**
 * One page of a read API list. {@code page} is 0-based; {@code sort} echoes the applied order as {@code field,dir}.
 */
public record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages, String sort) {
    public static <T> Page<T> of(List<T> items, PageQuery query, long totalItems) {
        int pages = totalItems == 0 ? 0 : (int) ((totalItems + query.size() - 1) / query.size());
        return new Page<>(List.copyOf(items), query.page(), query.size(), totalItems, pages, query.sortParameter());
    }
}
