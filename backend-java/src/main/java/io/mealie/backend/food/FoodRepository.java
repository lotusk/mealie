package io.mealie.backend.food;

import io.mealie.backend.compat.PyRandom;
import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.FoodMapper;
import io.mealie.backend.persistence.mapper.RecipeSummaryMapper;
import io.mealie.backend.persistence.model.FoodRow;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.query.FilterEntities;
import io.mealie.backend.query.PageRequest;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.query.QueryFilterException;
import io.mealie.backend.query.QueryFilterSql;
import io.mealie.backend.query.SearchFilter;
import io.mealie.backend.query.SqlFragment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Python RepositoryFood listing, scoped to the authenticated group. */
@Repository
public class FoodRepository {

    /** IngredientFood._searchable_properties. */
    private static final List<String> SEARCHABLE_COLUMNS = List.of(
            "ingredient_foods.name_normalized", "ingredient_foods.plural_name_normalized");

    private final FoodMapper mapper;
    private final RecipeSummaryMapper recipeSummaryMapper;
    private final SqlDialect dialect;
    private final QueryFilterSql queryFilterSql;

    public FoodRepository(FoodMapper mapper, RecipeSummaryMapper recipeSummaryMapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.recipeSummaryMapper = recipeSummaryMapper;
        this.dialect = dialect;
        this.queryFilterSql = new QueryFilterSql(dialect);
    }

    /**
     * RepositoryGeneric.page_all(): group scope, then search, then the query filter; ordered by search rank, then
     * orderBy (created_at by default when not searching).
     */
    @Transactional
    public Pagination<FoodResponse> page(UUID groupId, PaginationQuery query, String search) {
        boolean postgres = dialect.engine() == DbEngine.POSTGRES;
        SqlFragment where = new SqlFragment("where").append("ingredient_foods.group_id = ").param(dialect.uuid(groupId));
        List<QueryFilterSql.Expr> order = new ArrayList<>();

        if (search != null && !search.isEmpty()) {
            SearchFilter searchFilter = new SearchFilter(search, postgres, true);
            if (searchFilter.isFuzzy()) {
                recipeSummaryMapper.setWordSimilarityThreshold(SearchFilter.FUZZY_SIMILARITY_THRESHOLD);
            }
            QueryFilterSql.Expr condition = searchFilter.where(SEARCHABLE_COLUMNS);
            if (condition != null) {
                where.append(" AND (");
                condition.render(where);
                where.append(")");
            }
            order.add(searchFilter.order(SEARCHABLE_COLUMNS));
        }

        String orderBy = query.orderBy();
        if ((orderBy == null || orderBy.isEmpty()) && (search == null || search.isEmpty())) {
            orderBy = "created_at";
        }

        if (query.queryFilter() != null && !query.queryFilter().isEmpty()) {
            QueryFilterSql.Expr filter = queryFilterSql.filter(query.queryFilter(), FilterEntities.FOOD);
            if (filter != null) {
                where.append(" AND (");
                filter.render(where);
                where.append(")");
            }
        }

        long count = mapper.count(where);
        PageRequest page = new PageRequest(query, count);

        if (orderBy != null && !orderBy.isEmpty()) {
            if (orderBy.equals("random")) {
                order.add(randomOrder(where, order, query.paginationSeed()));
            } else {
                order.addAll(orderBy(query, orderBy));
            }
        }

        SqlFragment orderSql = render("order", order);
        List<FoodRow> rows = mapper.page(where, orderSql, page.limit(), page.offset(), !postgres);
        Map<UUID, List<FoodResponse.Alias>> aliases = new HashMap<>();
        Map<UUID, List<String>> households = new HashMap<>();
        Map<UUID, Map<String, String>> extras = new HashMap<>();
        Map<UUID, List<FoodResponse.Substitution>> substitutions = new HashMap<>();
        // Bound each IN list to stay below SQLite's parameter limit even for perPage=-1.
        for (int start = 0; start < rows.size(); start += 500) {
            List<Object> ids = rows.subList(start, Math.min(start + 500, rows.size())).stream()
                    .map(row -> dialect.uuid(row.id())).toList();
            for (var row : mapper.aliases(ids)) {
                aliases.computeIfAbsent(row.foodId(), key -> new ArrayList<>()).add(new FoodResponse.Alias(row.value()));
            }
            for (var row : mapper.households(ids)) {
                households.computeIfAbsent(row.foodId(), key -> new ArrayList<>()).add(row.value());
            }
            for (var row : mapper.extras(ids)) {
                extras.computeIfAbsent(row.foodId(), key -> new java.util.LinkedHashMap<>()).put(row.key(), row.value());
            }
            for (var row : mapper.substitutions(ids)) {
                String note = row.note() == null ? null : PyStr.strip(row.note());
                if (note != null && note.isEmpty()) note = null;
                if (row.substituteFoodId() == null && note == null) continue;
                if (row.foodId().equals(row.substituteFoodId())) {
                    throw new IllegalStateException("a food cannot be substituted with itself");
                }
                var list = substitutions.computeIfAbsent(row.foodId(), key -> new ArrayList<>());
                if (row.substituteFoodId() != null && list.stream()
                        .anyMatch(sub -> row.substituteFoodId().equals(sub.substituteFoodId()))) continue;
                var summary = row.joinedId() == null ? null
                        : new FoodResponse.Summary(row.joinedId(), row.name(), row.pluralName());
                list.add(new FoodResponse.Substitution(row.substituteFoodId(), note, summary));
            }
        }
        List<FoodResponse> items = rows.stream().map(row -> new FoodResponse(
                row.id(), row.name(), row.pluralName(), row.description() == null ? "" : row.description(),
                extras.getOrDefault(row.id(), Map.of()), row.labelId(), aliases.getOrDefault(row.id(), List.of()),
                substitutions.getOrDefault(row.id(), List.of()), households.getOrDefault(row.id(), List.of()),
                row.joinedLabelId() == null ? null : new FoodResponse.Label(
                        row.labelName(), row.labelColor(), row.labelGroupId(), row.joinedLabelId()),
                row.recipeCount(), timestamp(row.createdAt()), timestamp(row.updatedAt()))).toList();
        return page.result(items, count, query, "/foods");
    }

    private static String timestamp(java.time.OffsetDateTime value) {
        if (value == null) return null;
        return value.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"))
                + (value.getNano() == 0 ? "" : String.format(java.util.Locale.ROOT, ".%06d", value.getNano() / 1000)) + "Z";
    }

    /** RepositoryGeneric.add_order_by_to_query() for "attr", "attr:dir" and comma-separated lists of them. */
    private List<QueryFilterSql.Expr> orderBy(PaginationQuery query, String orderBy) {
        List<QueryFilterSql.Expr> order = new ArrayList<>();
        for (String raw : orderBy.split(",", -1)) {
            String value = PyStr.strip(raw);
            String attribute = value;
            String direction = query.orderDirection();
            if (value.contains(":")) {
                String[] parts = value.split(":", -1);
                if (parts.length != 2 || !PaginationQuery.ORDER_DIRECTIONS.contains(parts[1])) {
                    throw invalidOrderBy(orderBy, value);
                }
                attribute = parts[0];
                direction = parts[1];
            }
            String column;
            try {
                column = queryFilterSql.orderAttr(attribute, FilterEntities.FOOD, direction.equals("desc"));
            } catch (QueryFilterException e) {
                if (e.detail().startsWith("Cannot filter on ")) {
                    throw new QueryFilterException(
                            "Invalid order_by statement \"" + orderBy + "\": " + e.detail());
                }
                throw invalidOrderBy(orderBy, value);
            }
            String sql = column + (direction.equals("desc") ? " DESC" : " ASC")
                    + (query.orderByNullPosition() == null ? ""
                            : query.orderByNullPosition().equals("first") ? " NULLS FIRST" : " NULLS LAST");
            order.add(out -> out.append(sql));
        }
        return order;
    }

    private static QueryFilterException invalidOrderBy(String orderBy, String value) {
        return new QueryFilterException(
                "Invalid order_by statement \"" + orderBy + "\": \"" + value + "\" is invalid");
    }

    /**
     * orderBy=random: Python shuffles the matching ids with random.seed(paginationSeed) and orders by each id's
     * position, so the order is stable across pages and identical on both backends.
     */
    private QueryFilterSql.Expr randomOrder(SqlFragment where, List<QueryFilterSql.Expr> priorOrder, String seed) {
        List<UUID> ids = mapper.ids(where, render("order", priorOrder)).stream().map(IdRow::id).toList();
        if (ids.isEmpty()) {
            return null;
        }
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            positions.add(i);
        }
        new PyRandom(seed).shuffle(positions);
        return out -> {
            out.append("CASE ingredient_foods.id");
            for (int i = 0; i < ids.size(); i++) {
                out.append(" WHEN ").param(dialect.uuid(ids.get(i))).append(" THEN " + positions.get(i));
            }
            out.append(" END");
        };
    }

    private static SqlFragment render(String name, List<QueryFilterSql.Expr> terms) {
        SqlFragment fragment = new SqlFragment(name);
        boolean first = true;
        for (QueryFilterSql.Expr term : terms) {
            if (term == null) {
                continue;
            }
            fragment.append(first ? "" : ", ");
            term.render(fragment);
            first = false;
        }
        return fragment;
    }

}
