package io.mealie.backend.recipe;

import io.mealie.backend.db.SqlDialect;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only projection of the schema owned by Python; no HTTP client or Python fallback. */
@Repository
public class RecipeRepository {
    private static final Set<String> UUID_COLUMNS = Set.of("id", "group_id", "household_id", "user_id", "recipe_id",
            "reference_id", "unit_id", "food_id", "referenced_recipe_id", "label_id", "substitute_food_id");
    private static final Set<String> BOOL_COLUMNS = Set.of("admin", "public", "show_nutrition", "show_assets", "landscape_view",
            "disable_comments", "locked", "fraction", "use_abbreviation");
    private static final Set<String> TIMESTAMP_COLUMNS = Set.of("created_at", "update_at", "date_updated", "last_made");
    private final NamedParameterJdbcTemplate jdbc;
    private final SqlDialect dialect;

    public RecipeRepository(NamedParameterJdbcTemplate jdbc, SqlDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public Optional<Map<String, Object>> find(String slugOrId, UUID groupId, String locale) {
        Optional<UUID> id = RecipeIdentifier.uuid(slugOrId);
        return readRecipe(id.<Object>map(dialect::uuid).orElse(slugOrId), id.isPresent() ? "id" : "slug",
                groupId, locale, new HashSet<>());
    }

    private Optional<Map<String, Object>> readRecipe(Object identifier, String column, UUID groupId, String locale, Set<UUID> parents) {
        // Python's RecipeService uses group_recipes, not the household-scoped repo. Admins are also group-scoped here.
        String sql = "SELECT r.*, u.household_id FROM recipes r LEFT JOIN users u ON u.id = r.user_id"
                + " WHERE r." + column + " = :identifier AND r.group_id = :groupId";
        var params = new MapSqlParameterSource("identifier", identifier).addValue("groupId", dialect.uuid(groupId));
        List<Map<String, Object>> rows = jdbc.query(sql, params, (rs, n) -> project(rs,
                "id user_id household_id group_id name slug image recipe_servings recipe_yield_quantity recipe_yield total_time prep_time cook_time perform_time total_time_seconds prep_time_seconds perform_time_seconds description rating org_url date_added date_updated created_at update_at last_made"));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> recipe = rows.getFirst();
        UUID id = UUID.fromString((String) recipe.get("id"));
        if (!parents.add(id)) throw new RecipeService.InvalidRecipeException();
        try {
            recipe.compute("recipeServings", (k, v) -> v == null ? 0 : v);
            recipe.compute("recipeYieldQuantity", (k, v) -> v == null ? 0 : v);
            recipe.put("recipeCategory", organizers("categories", "recipes_to_categories", "category_id", id));
            recipe.put("tags", organizers("tags", "recipes_to_tags", "tag_id", id));
            List<Map<String, Object>> tools = organizers("tools", "recipes_to_tools", "tool_id", id);
            for (var tool : tools) tool.put("householdsWithTool", householdSlugs("households_to_tools", "tool_id", uuid(tool.get("id"))));
            recipe.put("tools", tools);
            List<Map<String, Object>> ingredients = jdbc.query("SELECT * FROM recipes_ingredients WHERE recipe_id = :id ORDER BY position", param(id),
                    (rs, n) -> {
                        var result = project(rs, "quantity note title original_text reference_id unit_id food_id referenced_recipe_id");
                        result.put("rowId", rs.getInt("id"));
                        return result;
                    });
            for (var ingredient : ingredients) {
                int rowId = (Integer) ingredient.remove("rowId");
                var unit = unit(uuid(ingredient.remove("unitId")));
                var food = food(uuid(ingredient.remove("foodId")));
                UUID reference = uuid(ingredient.remove("referencedRecipeId"));
                Double quantity = IngredientDisplay.round((Number) ingredient.get("quantity"));
                ingredient.put("quantity", quantity);
                ingredient.put("unit", unit);
                ingredient.put("food", food);
                ingredient.put("referencedRecipe", reference == null ? null : readRecipe(dialect.uuid(reference), "id", groupId, locale, parents).orElse(null));
                if (ingredient.get("referenceId") == null) ingredient.put("referenceId", UUID.randomUUID().toString());
                ingredient.put("substitutions", substitutions("recipes_ingredients_substitutions", "ingredient_id", rowId));
                ingredient.put("display", IngredientDisplay.format(quantity, unit, food, (String) ingredient.get("note"), locale));
            }
            recipe.put("recipeIngredient", ingredients);
            List<Map<String, Object>> steps = related("recipe_instructions", id, "id title summary text", " ORDER BY position");
            for (var step : steps) {
                UUID stepId = uuid(step.get("id"));
                step.put("ingredientReferences", rows("recipe_ingredient_ref_link", "instruction_id", stepId, "reference_id", ""));
                step.put("noteReferences", rows("recipe_note_ref_link", "instruction_id", stepId, "reference_id", ""));
            }
            recipe.put("recipeInstructions", steps);
            recipe.put("nutrition", first(related("recipe_nutrition", id, "calories carbohydrate_content cholesterol_content fat_content fiber_content protein_content saturated_fat_content sodium_content sugar_content trans_fat_content unsaturated_fat_content", "")));
            recipe.put("settings", first(related("recipe_settings", id, "public show_nutrition show_assets landscape_view disable_comments locked", "")));
            recipe.put("assets", related("recipe_assets", id, "name icon file_name", ""));
            List<Map<String, Object>> notes = related("notes", id, "title text reference_id", "");
            for (var note : notes) if (note.get("referenceId") == null) note.put("referenceId", UUID.randomUUID().toString());
            recipe.put("notes", notes);
            recipe.put("extras", extras("api_extras", "recipee_id", id));
            recipe.put("comments", jdbc.query("SELECT c.*, u.username, u.admin, u.full_name FROM recipe_comments c JOIN users u ON u.id = c.user_id WHERE c.recipe_id = :id", param(id),
                    (rs, n) -> {
                        var comment = project(rs, "id recipe_id text created_at update_at user_id");
                        var user = project(rs, "username admin full_name");
                        user.put("id", comment.get("userId"));
                        comment.put("user", user);
                        return comment;
                    }));
            return Optional.of(recipe);
        } finally {
            parents.remove(id);
        }
    }

    private List<Map<String, Object>> organizers(String table, String link, String key, UUID id) {
        return jdbc.query("SELECT o.* FROM " + table + " o JOIN " + link + " l ON o.id = l." + key + " WHERE l.recipe_id = :id", param(id),
                (rs, n) -> {
                    var result = project(rs, "id group_id name slug");
                    result.put("recipeCount", 0); // SQLAlchemy query_expression defaults to zero on the single-recipe path.
                    return result;
                });
    }

    private Map<String, Object> unit(UUID id) {
        if (id == null) return null;
        var unit = first(rows("ingredient_units", "id", id, "id name plural_name description fraction abbreviation plural_abbreviation use_abbreviation standard_quantity standard_unit created_at update_at", ""));
        if (unit == null) return null;
        unit.compute("description", (k, v) -> v == null ? "" : v);
        unit.put("extras", Map.of());
        unit.put("aliases", rows("ingredient_units_aliases", "unit_id", id, "name", ""));
        if (unit.get("standardUnit") == null || unit.get("standardUnit").toString().isEmpty()
                || !(unit.get("standardQuantity") instanceof Number number) || number.doubleValue() <= 0) {
            unit.put("standardQuantity", null);
            unit.put("standardUnit", null);
        }
        return unit;
    }

    private Map<String, Object> food(UUID id) {
        if (id == null) return null;
        var food = first(rows("ingredient_foods", "id", id, "id name plural_name description label_id created_at update_at", ""));
        if (food == null) return null;
        UUID label = uuid(food.get("labelId"));
        food.compute("description", (k, v) -> v == null ? "" : v);
        food.put("extras", extras("ingredient_food_extras", "ingredient_food_id", id));
        food.put("aliases", rows("ingredient_foods_aliases", "food_id", id, "name", ""));
        food.put("substitutions", substitutions("ingredient_foods_substitutions", "food_id", id));
        food.put("householdsWithIngredientFood", householdSlugs("households_to_ingredient_foods", "food_id", id));
        food.put("label", label == null ? null : first(rows("multi_purpose_labels", "id", label, "id group_id name color", "")));
        food.put("recipeCount", 0);
        return food;
    }

    private List<Map<String, Object>> substitutions(String table, String column, Object id) {
        List<Map<String, Object>> substitutions = rows(table, column, id, "substitute_food_id note", " ORDER BY position");
        Set<UUID> seen = new HashSet<>();
        substitutions.removeIf(sub -> {
            UUID food = uuid(sub.get("substituteFoodId"));
            String note = sub.get("note") instanceof String text ? text.strip() : null;
            sub.put("note", note == null || note.isEmpty() ? null : note);
            return food == null ? sub.get("note") == null : !seen.add(food);
        });
        for (var sub : substitutions) {
            UUID food = uuid(sub.get("substituteFoodId"));
            sub.put("substituteFood", food == null ? null : first(rows("ingredient_foods", "id", food, "id name plural_name", "")));
        }
        return substitutions;
    }

    private List<String> householdSlugs(String table, String column, UUID id) {
        return jdbc.query("SELECT h.slug FROM households h JOIN " + table + " l ON h.id = l.household_id WHERE l." + column + " = :id",
                param(id), (rs, n) -> rs.getString("slug"));
    }

    private Map<String, Object> extras(String table, String column, UUID id) {
        var extras = new LinkedHashMap<String, Object>();
        jdbc.query("SELECT key_name, value FROM " + table + " WHERE " + column + " = :id", param(id),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> extras.put(rs.getString("key_name"), rs.getString("value")));
        return extras;
    }

    // Table/column/projection names below are private constants supplied by this repository, never request data.
    private List<Map<String, Object>> related(String table, UUID id, String columns, String order) {
        return rows(table, "recipe_id", id, columns, order);
    }

    private List<Map<String, Object>> rows(String table, String key, Object id, String columns, String order) {
        return jdbc.query("SELECT * FROM " + table + " WHERE " + key + " = :id" + order, param(id), (rs, n) -> project(rs, columns));
    }

    private MapSqlParameterSource param(Object id) {
        return new MapSqlParameterSource("id", id instanceof UUID value ? dialect.uuid(value) : id);
    }

    private Map<String, Object> project(ResultSet rs, String columns) throws SQLException {
        var result = new LinkedHashMap<String, Object>();
        for (String column : columns.split(" ")) {
            Object value;
            if (UUID_COLUMNS.contains(column)) {
                UUID id = dialect.getUuid(rs, column);
                value = id == null ? null : id.toString();
            } else if (BOOL_COLUMNS.contains(column)) {
                value = dialect.getBool(rs, column);
            } else if (TIMESTAMP_COLUMNS.contains(column)) {
                value = timestamp(dialect.getTimestamp(rs, column));
            } else if (column.equals("date_added")) {
                var date = dialect.getDate(rs, column);
                value = date == null ? null : date.toString();
            } else {
                value = rs.getObject(column);
            }
            result.put(alias(column), value);
        }
        return result;
    }

    private static String alias(String column) {
        if (column.equals("org_url")) return "orgURL";
        if (column.equals("update_at")) return "updatedAt";
        String[] words = column.split("_");
        var result = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) result.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        return result.toString();
    }

    private static String timestamp(OffsetDateTime value) {
        if (value == null) return null;
        return value.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"))
                + (value.getNano() == 0 ? "" : String.format(java.util.Locale.ROOT, ".%06d", value.getNano() / 1000)) + "Z";
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
