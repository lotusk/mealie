package io.mealie.backend.organizers;

import io.mealie.backend.compat.PyRandom;
import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.RecipeSummaryMapper;
import io.mealie.backend.persistence.mapper.ToolMapper;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.persistence.model.ToolHouseholdRow;
import io.mealie.backend.persistence.model.ToolRow;
import io.mealie.backend.query.FilterEntities;
import io.mealie.backend.query.PageRequest;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.query.QueryFilterException;
import io.mealie.backend.query.QueryFilterSql;
import io.mealie.backend.query.SearchFilter;
import io.mealie.backend.query.SqlFragment;
import io.mealie.backend.recipe.RecipeToolSummary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tools, scoped to one group, listed like RepositoryTools (mealie/repos/repository_factory.py) on top of
 * RepositoryGeneric (mealie/repos/repository_generic.py).
 */
@Repository
public class ToolRepository {

    /** RecipeTool._searchable_properties. */
    private static final List<String> SEARCHABLE_COLUMNS = List.of("tools.name");

    private final ToolMapper mapper;
    private final RecipeSummaryMapper recipeSummaryMapper;
    private final SqlDialect dialect;
    private final QueryFilterSql queryFilterSql;

    public ToolRepository(ToolMapper mapper, RecipeSummaryMapper recipeSummaryMapper, SqlDialect dialect) {
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
    public Pagination<RecipeToolSummary> page(UUID groupId, PaginationQuery query, String search) {
        boolean postgres = dialect.engine() == DbEngine.POSTGRES;
        SqlFragment where = new SqlFragment("where").append("tools.group_id = ").param(dialect.uuid(groupId));
        List<QueryFilterSql.Expr> order = new ArrayList<>();

        if (search != null && !search.isEmpty()) {
            SearchFilter searchFilter = new SearchFilter(search, postgres, false);
            if (searchFilter.isFuzzy()) {
                recipeSummaryMapper.setWordSimilarityThreshold(SearchFilter.FUZZY_SIMILARITY_THRESHOLD);
            }
            QueryFilterSql.Expr condition = searchFilter.where(SEARCHABLE_COLUMNS);
            if (condition != null) {
                where.append(" AND ");
                condition.render(where);
            }
            order.add(searchFilter.order(SEARCHABLE_COLUMNS));
        }

        String orderBy = query.orderBy();
        if ((orderBy == null || orderBy.isEmpty()) && (search == null || search.isEmpty())) {
            orderBy = "created_at";
        }

        if (query.queryFilter() != null && !query.queryFilter().isEmpty()) {
            QueryFilterSql.Expr filter = queryFilterSql.filter(query.queryFilter(), FilterEntities.TOOL);
            if (filter != null) {
                where.append(" AND ");
                filter.render(where);
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
        List<ToolRow> rows = mapper.page(where, orderSql, page.limit(), page.offset(), !postgres);
        Map<UUID, List<String>> households = new HashMap<>();
        // Batch relationship reads: perPage=-1 must not issue one query per tool.
        if (!rows.isEmpty()) {
            List<Object> ids = rows.stream().map(row -> dialect.uuid(row.id())).toList();
            for (ToolHouseholdRow household : mapper.householdSlugs(ids)) {
                households.computeIfAbsent(household.toolId(), key -> new ArrayList<>()).add(household.slug());
            }
        }
        List<RecipeToolSummary> items = rows.stream()
                .map(row -> new RecipeToolSummary(row.id(), row.groupId(), row.name(), row.slug(), row.recipeCount(),
                        households.getOrDefault(row.id(), List.of())))
                .toList();
        return page.result(items, count, query, "/tools");
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
                column = queryFilterSql.orderAttr(attribute, FilterEntities.TOOL, direction.equals("desc"));
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
            out.append("CASE tools.id");
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
