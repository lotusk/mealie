package io.mealie.backend.group;

import io.mealie.backend.compat.PyRandom;
import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.GroupHouseholdMapper;
import io.mealie.backend.persistence.model.GroupHouseholdRow;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.query.FilterEntities;
import io.mealie.backend.query.PageRequest;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.query.QueryFilterException;
import io.mealie.backend.query.QueryFilterSql;
import io.mealie.backend.query.SqlFragment;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class GroupHouseholdRepository {

    private final GroupHouseholdMapper mapper;
    private final SqlDialect dialect;
    private final QueryFilterSql filters;

    public GroupHouseholdRepository(GroupHouseholdMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
        this.filters = new QueryFilterSql(dialect);
    }

    public Pagination<GroupHouseholdResponse> page(UUID groupId, PaginationQuery query) {
        SqlFragment where = new SqlFragment("where").append("households.group_id = ").param(dialect.uuid(groupId));
        if (query.queryFilter() != null && !query.queryFilter().isEmpty()) {
            QueryFilterSql.Expr filter = filters.filter(query.queryFilter(), FilterEntities.HOUSEHOLD);
            if (filter != null) {
                where.append(" AND ");
                filter.render(where);
            }
        }
        long count = mapper.count(where);
        PageRequest page = new PageRequest(query, count);
        String orderBy = query.orderBy() == null || query.orderBy().isEmpty() ? "created_at" : query.orderBy();
        SqlFragment order = orderBy.equals("random") ? randomOrder(where, query.paginationSeed()) : order(query, orderBy);
        List<GroupHouseholdResponse> items = mapper.page(where, order, page.limit(), page.offset(),
                        dialect.engine() == DbEngine.SQLITE).stream()
                .map(this::response).toList();
        return page.result(items, count, query, "/groups/households");
    }

    public Optional<GroupHouseholdResponse> findBySlugOrId(UUID groupId, String slugOrId) {
        Optional<UUID> id = parseUuid(slugOrId);
        // A UUID-looking slug is always treated as an ID, without a second slug lookup, like Python.
        GroupHouseholdRow row = mapper.findOne(dialect.uuid(groupId),
                id.map(dialect::uuid).orElse(null), slugOrId);
        return Optional.ofNullable(row).map(this::response);
    }

    /** Match Python uuid.UUID(string), including hex, braces and URN representations. */
    private static Optional<UUID> parseUuid(String value) {
        String hex = value.replace("urn:", "").replace("uuid:", "").replace("-", "");
        while (hex.startsWith("{")) hex = hex.substring(1);
        while (hex.endsWith("}")) hex = hex.substring(0, hex.length() - 1);
        if (hex.length() != 32) return Optional.empty();
        try {
            return Optional.of(new UUID(HexFormat.fromHexDigitsToLong(hex, 0, 16),
                    HexFormat.fromHexDigitsToLong(hex, 16, 32)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private GroupHouseholdResponse response(GroupHouseholdRow row) {
        // A missing preference relationship is null; don't synthesize defaults for it.
        GroupHouseholdResponse.Preferences preferences = row.preferencesId() == null ? null
                : new GroupHouseholdResponse.Preferences(
                        row.privateHousehold(), row.showAnnouncements(), row.lockRecipeEditsFromOtherHouseholds(),
                        row.firstDayOfWeek(), row.recipePublic(), row.recipeShowNutrition(), row.recipeShowAssets(),
                        row.recipeLandscapeView(), row.recipeDisableComments(), row.preferencesId());
        return new GroupHouseholdResponse(row.groupId(), PyStr.strip(row.name()), row.id(), row.slug(), preferences);
    }

    private SqlFragment order(PaginationQuery query, String orderBy) {
        SqlFragment result = new SqlFragment("order");
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
                column = filters.orderAttr(attribute, FilterEntities.HOUSEHOLD, direction.equals("desc"));
            } catch (QueryFilterException e) {
                if (e.detail().startsWith("Cannot filter on ")) {
                    throw new QueryFilterException("Invalid order_by statement \"" + orderBy + "\": " + e.detail());
                }
                throw invalidOrderBy(orderBy, value);
            }
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append(column).append(direction.equals("desc") ? " DESC" : " ASC");
            if (query.orderByNullPosition() != null) {
                result.append(query.orderByNullPosition().equals("first") ? " NULLS FIRST" : " NULLS LAST");
            }
        }
        return result;
    }

    private static QueryFilterException invalidOrderBy(String orderBy, String value) {
        return new QueryFilterException("Invalid order_by statement \"" + orderBy + "\": \"" + value + "\" is invalid");
    }

    /** Match Python's seeded shuffle before applying LIMIT/OFFSET. */
    private SqlFragment randomOrder(SqlFragment where, String seed) {
        List<UUID> ids = mapper.ids(where).stream().map(IdRow::id).toList();
        SqlFragment result = new SqlFragment("order");
        if (ids.isEmpty()) {
            return result;
        }
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            positions.add(i);
        }
        new PyRandom(seed).shuffle(positions);
        result.append("CASE households.id");
        for (int i = 0; i < ids.size(); i++) {
            result.append(" WHEN ").param(dialect.uuid(ids.get(i))).append(" THEN " + positions.get(i));
        }
        return result.append(" END");
    }
}
