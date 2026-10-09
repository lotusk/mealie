package io.mealie.backend.query;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RepositoryGeneric.add_pagination_to_query() and PaginationBase.set_pagination_guides(): turns the requested page
 * and the total count into LIMIT/OFFSET and the response's page numbers and next/previous links.
 */
public final class PageRequest {

    private final long page;
    private final long perPage;
    private final long totalPages;
    /** Null means no LIMIT (perPage=-1). */
    private final Long limit;
    private final long offset;

    public PageRequest(PaginationQuery query, long count) {
        long perPage = query.perPage();
        Long limit = perPage;
        if (perPage == -1) {
            perPage = count;
            limit = null;
        }
        long totalPages = perPage == 0 ? 0 : (long) Math.ceil((double) count / perPage);
        long page = query.page();
        if (page == -1) {
            page = totalPages;
        }
        if (page < 1) {
            page = 1;
        }
        this.page = page;
        this.perPage = perPage;
        this.totalPages = totalPages;
        this.limit = limit;
        this.offset = (page - 1) * perPage;
    }

    public Long limit() {
        return limit;
    }

    public long offset() {
        return offset;
    }

    /** The response body, with links to the previous and next page built from the original query parameters. */
    public <T> Pagination<T> result(List<T> items, long count, PaginationQuery query, String route) {
        String next = page >= totalPages ? null : link(route, query, page + 1);
        String previous = page <= 1 ? null : link(route, query, page - 1);
        return new Pagination<>(page, perPage, count, totalPages, items, next, previous);
    }

    /** urlencode(camelize(q.model_dump()), doseq=True): None renders as "None". */
    private static String link(String route, PaginationQuery query, long page) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderBy", query.orderBy());
        params.put("orderByNullPosition", query.orderByNullPosition());
        params.put("orderDirection", query.orderDirection());
        params.put("queryFilter", query.queryFilter());
        params.put("paginationSeed", query.paginationSeed());
        params.put("page", page);
        params.put("perPage", query.perPage());
        StringBuilder out = new StringBuilder(route).append('?');
        boolean first = true;
        for (Map.Entry<String, Object> param : params.entrySet()) {
            out.append(first ? "" : "&").append(param.getKey()).append('=')
                    .append(quotePlus(param.getValue() == null ? "None" : String.valueOf(param.getValue())));
            first = false;
        }
        return out.toString();
    }

    /** urllib.parse.quote_plus(): keeps letters, digits and "_.-~", encodes spaces as '+'. */
    static String quotePlus(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '.' || c == '-' || c == '~') {
                out.append((char) c);
            } else if (c == ' ') {
                out.append('+');
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }
}
