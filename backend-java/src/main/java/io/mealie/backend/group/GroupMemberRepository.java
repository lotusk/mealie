package io.mealie.backend.group;

import io.mealie.backend.compat.PyRandom;
import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.GroupMemberMapper;
import io.mealie.backend.persistence.model.GroupMemberRow;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.query.FilterEntities;
import io.mealie.backend.query.PageRequest;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.query.QueryFilterException;
import io.mealie.backend.query.QueryFilterSql;
import io.mealie.backend.query.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class GroupMemberRepository {

    private final GroupMemberMapper mapper;
    private final SqlDialect dialect;
    private final QueryFilterSql filters;

    public GroupMemberRepository(GroupMemberMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
        this.filters = new QueryFilterSql(dialect);
    }

    public Pagination<GroupMemberResponse> page(UUID groupId, PaginationQuery query) {
        SqlFragment where = new SqlFragment("where").append("users.group_id = ").param(dialect.uuid(groupId));
        if (query.queryFilter() != null && !query.queryFilter().isEmpty()) {
            QueryFilterSql.Expr filter = filters.filter(query.queryFilter(), FilterEntities.USER);
            if (filter != null) {
                where.append(" AND (");
                filter.render(where);
                where.append(")");
            }
        }
        long count = mapper.count(where);
        PageRequest page = new PageRequest(query, count);
        String orderBy = query.orderBy() == null || query.orderBy().isEmpty() ? "created_at" : query.orderBy();
        SqlFragment order = orderBy.equals("random") ? randomOrder(where, query.paginationSeed()) : order(query, orderBy);
        List<GroupMemberResponse> items = mapper.page(where, order, page.limit(), page.offset(),
                        dialect.engine() == DbEngine.SQLITE).stream()
                .map(this::response).toList();
        return page.result(items, count, query, "/groups/members");
    }

    private GroupMemberResponse response(GroupMemberRow row) {
        return new GroupMemberResponse(row.id(), row.groupId(), row.householdId(), row.username(), row.fullName());
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
                column = filters.orderAttr(attribute, FilterEntities.USER, direction.equals("desc"));
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
        result.append("CASE users.id");
        for (int i = 0; i < ids.size(); i++) {
            result.append(" WHEN ").param(dialect.uuid(ids.get(i))).append(" THEN " + positions.get(i));
        }
        return result.append(" END");
    }
}
