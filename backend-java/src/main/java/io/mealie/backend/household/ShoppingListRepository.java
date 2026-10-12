package io.mealie.backend.household;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PyDateTime;
import io.mealie.backend.compat.PyRandom;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.ShoppingListMapper;
import io.mealie.backend.persistence.model.ShoppingListExtraRow;
import io.mealie.backend.persistence.model.ShoppingListLabelSettingRow;
import io.mealie.backend.persistence.model.ShoppingListRecipeRefRow;
import io.mealie.backend.persistence.model.ShoppingListRow;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.query.FilterEntities;
import io.mealie.backend.query.PageRequest;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.query.QueryFilterSql;
import io.mealie.backend.query.SqlFragment;
import io.mealie.backend.recipe.RecipeRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ShoppingListRepository {
    private final ShoppingListMapper mapper;
    private final RecipeRepository recipes;
    private final SqlDialect dialect;
    private final QueryFilterSql filters;

    public ShoppingListRepository(ShoppingListMapper mapper, RecipeRepository recipes, SqlDialect dialect) {
        this.mapper = mapper;
        this.recipes = recipes;
        this.dialect = dialect;
        this.filters = new QueryFilterSql(dialect);
    }

    public Pagination<Map<String, Object>> page(AuthUser user, PaginationQuery query) {
        SqlFragment where = new SqlFragment("where").append("shopping_lists.group_id = ")
                .param(dialect.uuid(user.groupId()));
        if (user.householdId() != null) {
            where.append(" AND users.household_id = ").param(dialect.uuid(user.householdId()));
        }
        if (query.queryFilter() != null && !query.queryFilter().isEmpty()) {
            where.append(" AND ");
            filters.filter(query.queryFilter(), FilterEntities.SHOPPING_LIST).render(where);
        }

        long count = mapper.count(where);
        String orderBy = query.orderBy();
        if (orderBy == null || orderBy.isEmpty()) {
            orderBy = "created_at";
        }
        PaginationQuery effective = new PaginationQuery(orderBy, query.orderByNullPosition(), query.orderDirection(),
                query.queryFilter(), query.paginationSeed(), query.page(), query.perPage());
        SqlFragment order = new SqlFragment("order");
        if ("random".equals(orderBy)) {
            List<UUID> matchingIds = mapper.ids(where, order).stream().map(IdRow::id).toList();
            List<Integer> positions = new ArrayList<>();
            for (int i = 0; i < matchingIds.size(); i++) positions.add(i);
            new PyRandom(query.paginationSeed()).shuffle(positions);
            if (!matchingIds.isEmpty()) {
                order.append("CASE shopping_lists.id");
                for (int i = 0; i < matchingIds.size(); i++) {
                    order.append(" WHEN ").param(dialect.uuid(matchingIds.get(i)))
                            .append(" THEN " + positions.get(i));
                }
                order.append(" END");
            }
        } else {
            boolean first = true;
            for (String raw : orderBy.split(",", -1)) {
                String value = raw.trim();
                String[] parts = value.split(":", -1);
                if (parts.length > 2 || parts.length == 2 && !PaginationQuery.ORDER_DIRECTIONS.contains(parts[1])) {
                    throw new io.mealie.backend.query.QueryFilterException(
                            "Invalid order_by statement \"" + orderBy + "\": \"" + value + "\" is invalid");
                }
                String direction = parts.length == 2 ? parts[1] : query.orderDirection();
                if (!first) order.append(", ");
                order.append(filters.orderAttr(parts[0], FilterEntities.SHOPPING_LIST, direction.equals("desc")));
                order.append(direction.equals("desc") ? " DESC" : " ASC");
                if (query.orderByNullPosition() != null) {
                    order.append(query.orderByNullPosition().equals("first") ? " NULLS FIRST" : " NULLS LAST");
                }
                first = false;
            }
        }

        PageRequest page = new PageRequest(effective, count);
        List<ShoppingListRow> rows = mapper.page(where, order, page.limit(), page.offset(),
                dialect.engine() != DbEngine.POSTGRES);
        List<Object> ids = rows.stream().map(row -> dialect.uuid(row.id())).toList();
        List<Map<String, Object>> items = assemble(rows, ids, user.groupId());
        return page.result(items, count, effective, "/api/households/shopping/lists");
    }

    private List<Map<String, Object>> assemble(List<ShoppingListRow> rows, List<Object> ids, UUID groupId) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, Map<String, String>> extras = new LinkedHashMap<>();
        for (ShoppingListExtraRow row : mapper.extras(ids)) {
            extras.computeIfAbsent(row.shoppingListId(), ignored -> new LinkedHashMap<>())
                    .put(row.keyName(), row.value());
        }
        Map<UUID, List<ShoppingListRecipeRefRow>> references = new LinkedHashMap<>();
        List<UUID> recipeIds = new ArrayList<>();
        for (ShoppingListRecipeRefRow ref : mapper.recipeReferences(ids)) {
            references.computeIfAbsent(ref.shoppingListId(), ignored -> new ArrayList<>()).add(ref);
            recipeIds.add(ref.recipeId());
        }
        Map<UUID, Map<String, Object>> recipeById = new LinkedHashMap<>();
        for (Map<String, Object> recipe : recipes.jsonableSummaries(recipeIds, groupId)) {
            Object id = recipe.get("id");
            if (id instanceof UUID uuid) recipeById.put(uuid, recipe);
        }
        Map<UUID, List<ShoppingListLabelSettingRow>> labels = new LinkedHashMap<>();
        for (ShoppingListLabelSettingRow label : mapper.labelSettings(ids)) {
            labels.computeIfAbsent(label.shoppingListId(), ignored -> new ArrayList<>()).add(label);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ShoppingListRow row : rows) {
        List<Map<String, Object>> recipeRefs = references.getOrDefault(row.id(), List.of()).stream().map(ref -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", ref.id());
                item.put("shoppingListId", ref.shoppingListId());
                item.put("recipeId", ref.recipeId());
                item.put("recipeQuantity", ref.recipeQuantity());
                item.put("recipe", recipeById.get(ref.recipeId()));
                return item;
            }).toList();
            List<Map<String, Object>> labelSettings = labels.getOrDefault(row.id(), List.of()).stream().map(label -> {
                Map<String, Object> labelInfo = new LinkedHashMap<>();
                labelInfo.put("id", label.labelId());
                labelInfo.put("groupId", label.groupId());
                labelInfo.put("name", label.name());
                labelInfo.put("color", label.color());
                Map<String, Object> setting = new LinkedHashMap<>();
                setting.put("id", label.id());
                setting.put("shoppingListId", label.shoppingListId());
                setting.put("labelId", label.labelId());
                setting.put("position", label.position());
                setting.put("label", labelInfo);
                return setting;
            }).toList();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.name());
            item.put("extras", extras.getOrDefault(row.id(), Map.of()));
            item.put("createdAt", PyDateTime.pydantic(row.createdAt()));
            item.put("updatedAt", PyDateTime.pydantic(row.updatedAt()));
            item.put("groupId", row.groupId());
            item.put("userId", row.userId());
            item.put("id", row.id());
            item.put("householdId", row.householdId());
            item.put("recipeReferences", recipeRefs);
            item.put("labelSettings", labelSettings);
            result.add(item);
        }
        return result;
    }
}
