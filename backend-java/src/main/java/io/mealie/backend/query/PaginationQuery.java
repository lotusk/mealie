package io.mealie.backend.query;

import io.mealie.backend.compat.PyValidate;
import io.mealie.backend.web.validation.ValidationErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Python's PaginationQuery (mealie/schema/response/pagination.py) as FastAPI reads it from the query string: camelCase
 * names, the last value wins when a name repeats, and pydantic's validation for each field.
 */
public record PaginationQuery(
        String orderBy,
        String orderByNullPosition,
        String orderDirection,
        String queryFilter,
        String paginationSeed,
        long page,
        long perPage) {

    public static final List<String> ORDER_DIRECTIONS = List.of("asc", "desc");
    public static final List<String> NULL_POSITIONS = List.of("first", "last");

    /**
     * Validates the parameters into errors (in pydantic's field order) and returns null if any is invalid; the
     * caller throws them with the rest of the request's errors.
     */
    public static PaginationQuery parse(HttpServletRequest request, ValidationErrors errors) {
        String orderBy = last(request, "orderBy");
        String nullPosition = errors.query("orderByNullPosition", last(request, "orderByNullPosition"),
                v -> PyValidate.enumValue(v, NULL_POSITIONS), null);
        String direction = errors.query("orderDirection", last(request, "orderDirection"),
                v -> PyValidate.enumValue(v, ORDER_DIRECTIONS), "desc");
        String queryFilter = last(request, "queryFilter");
        String seed = last(request, "paginationSeed");
        Long page = errors.query("page", last(request, "page"), PyValidate::integer, 1L);
        Long perPage = errors.query("perPage", last(request, "perPage"), PyValidate::integer, 50L);
        if (errors.hasErrors()) {
            return null;
        }
        if ("random".equals(orderBy) && (seed == null || seed.isEmpty())) {
            // RequestQuery.validate_randseed raises inside the dependency, outside FastAPI's request validation,
            // so Python answers with a plain 500.
            throw new IllegalStateException("paginationSeed is required when orderBy is random");
        }
        return new PaginationQuery(orderBy, nullPosition, direction, queryFilter, seed, page, perPage);
    }

    public static String last(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null || values.length == 0 ? null : values[values.length - 1];
    }
}
