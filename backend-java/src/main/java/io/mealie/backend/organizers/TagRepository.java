package io.mealie.backend.organizers;

import io.mealie.backend.compat.PyRandom;
import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.RecipeSummaryMapper;
import io.mealie.backend.persistence.mapper.TagMapper;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.persistence.model.RecipeSummaryRow;
import io.mealie.backend.persistence.model.TagRow;
import io.mealie.backend.query.FilterEntities;
import io.mealie.backend.query.PageRequest;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.query.QueryFilterException;
import io.mealie.backend.query.QueryFilterSql;
import io.mealie.backend.query.SearchFilter;
import io.mealie.backend.query.SqlFragment;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tags, scoped to one group, read and written like RepositoryTags (mealie/repos/repository_factory.py) on top of
 * RepositoryGeneric (mealie/repos/repository_generic.py).
 */
@Repository
public class TagRepository {

    /** RecipeTag._searchable_properties. */
    private static final List<String> SEARCHABLE_COLUMNS = List.of("tags.name");

    private final TagMapper mapper;
    private final RecipeSummaryMapper recipeSummaryMapper;
    private final SqlDialect dialect;
    private final QueryFilterSql queryFilterSql;

    public TagRepository(TagMapper mapper, RecipeSummaryMapper recipeSummaryMapper, SqlDialect dialect) {
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
    public <T> Pagination<T> page(UUID groupId, PaginationQuery query, String search, String route,
            Function<TagRow, T> mapItem) {
        boolean postgres = dialect.engine() == DbEngine.POSTGRES;
        SqlFragment where = new SqlFragment("where").append("tags.group_id = ").param(dialect.uuid(groupId));
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
            QueryFilterSql.Expr filter = queryFilterSql.filter(query.queryFilter(), FilterEntities.TAG);
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
        List<T> items = mapper.page(where, orderSql, page.limit(), page.offset(), !postgres).stream()
                .map(mapItem)
                .toList();
        return page.result(items, count, query, route);
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
                column = queryFilterSql.orderAttr(attribute, FilterEntities.TAG, direction.equals("desc"));
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
            out.append("CASE tags.id");
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

    public Optional<TagRow> findById(UUID groupId, UUID id) {
        return Optional.ofNullable(mapper.findById(dialect.uuid(groupId), dialect.uuid(id)));
    }

    public Optional<TagRow> findBySlug(UUID groupId, String slug) {
        return Optional.ofNullable(mapper.findBySlug(dialect.uuid(groupId), slug));
    }

    public List<TagRow> findEmpty(UUID groupId) {
        return mapper.findEmpty(dialect.uuid(groupId));
    }

    public List<RecipeSummaryRow> recipeRows(UUID tagId) {
        return mapper.recipeRows(dialect.uuid(tagId));
    }

    /** Inserts a tag; the caller runs it in a transaction and handles constraint violations. */
    public TagRow insert(UUID groupId, String name, String slug) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        mapper.insert(dialect.uuid(id), dialect.uuid(groupId), name, slug, dialect.timestamp(now),
                dialect.timestamp(now));
        return new TagRow(id, groupId, name, slug, now, now, 0);
    }

    public void update(UUID id, UUID groupId, String name, String slug) {
        mapper.update(dialect.uuid(id), dialect.uuid(groupId), name, slug, dialect.timestamp(now()));
    }

    /** Deletes a tag and, as the ORM does, its links to existing recipes. */
    public void delete(UUID id) {
        mapper.deleteRecipeLinks(dialect.uuid(id));
        mapper.delete(dialect.uuid(id));
    }

    /** RepositoryTags.merge(): moves from's recipes to {@code to} (skipping ones it has) and deletes from. */
    public void merge(UUID fromId, UUID toId) {
        mapper.moveRecipeLinks(dialect.uuid(fromId), dialect.uuid(toId));
        mapper.deleteAllRecipeLinks(dialect.uuid(fromId));
        delete(fromId);
    }

    /** get_utc_now(), at the microsecond precision Python stores. */
    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
