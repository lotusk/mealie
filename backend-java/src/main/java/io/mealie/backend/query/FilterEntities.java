package io.mealie.backend.query;

import io.mealie.backend.query.FilterEntity.ColumnType;
import java.util.Set;

/**
 * The SQLAlchemy models reachable from migrated endpoints' query filters, transcribed from mealie/db/models. Each
 * lists its FilterableColumn columns, its other columns (filtering on those is an error, not an unknown
 * attribute), and the relationships between these models. The remaining relationship names are listed so a path
 * through them gets an explicit "not supported" error.
 */
public final class FilterEntities {

    private FilterEntities() {
    }

    public static final FilterEntity GROUP = new FilterEntity("Group", "groups", Set.of(
            "ai_provider_settings", "categories", "cookbooks", "data_exports", "group_event_notifiers",
            "group_reports", "ingredient_foods", "ingredient_units", "invite_tokens", "labels", "mealplans",
            "preferences", "recipe_actions", "server_tasks", "shopping_lists", "webhooks"));

    public static final FilterEntity HOUSEHOLD = new FilterEntity("Household", "households", Set.of(
            "cookbooks", "group_event_notifiers", "ingredient_foods_on_hand", "invite_tokens", "preferences",
            "recipe_actions", "webhooks"));

    public static final FilterEntity USER = new FilterEntity("User", "users", Set.of(
            "comments", "favorite_recipes", "mealplans", "owned_recipes", "password_reset_tokens", "rated_recipes",
            "recipe_timeline_events", "shopping_lists", "tokens"));

    public static final FilterEntity RECIPE = new FilterEntity("RecipeModel", "recipes", Set.of(
            "assets", "comments", "extras", "favorited_by", "meal_entries", "notes", "nutrition", "rated_by",
            "recipe_ingredient", "recipe_instructions", "referenced_ingredients", "settings", "share_tokens",
            "shopping_list_item_refs", "shopping_list_refs", "timeline_events"));

    public static final FilterEntity TAG = new FilterEntity("Tag", "tags", Set.of());

    public static final FilterEntity CATEGORY = new FilterEntity("Category", "categories", Set.of());

    public static final FilterEntity TOOL = new FilterEntity("Tool", "tools", Set.of());

    static {
        GROUP.column("name", ColumnType.STRING).column("slug", ColumnType.STRING).baseColumns()
                .relation("households", () -> HOUSEHOLD, "households", true, "groups.id = households.group_id")
                .relation("users", () -> USER, "users", true, "groups.id = users.group_id")
                .relation("recipes", () -> RECIPE, "recipes", true, "groups.id = recipes.group_id")
                .relation("tools", () -> TOOL, "tools", true, "groups.id = tools.group_id")
                .relation("tags", () -> TAG, "tags", true, "groups.id = tags.group_id");

        HOUSEHOLD.column("name", ColumnType.STRING).column("slug", ColumnType.STRING)
                .column("group_id", ColumnType.GUID).baseColumns()
                .relation("group", () -> GROUP, "groups", false, "groups.id = households.group_id")
                .relation("users", () -> USER, "users", true, "households.id = users.household_id")
                .secondary("made_recipes", () -> RECIPE, "recipes", "households_to_recipes",
                        "households.id = households_to_recipes.household_id",
                        "recipes.id = households_to_recipes.recipe_id")
                .secondary("tools_on_hand", () -> TOOL, "tools", "households_to_tools",
                        "households.id = households_to_tools.household_id", "tools.id = households_to_tools.tool_id");

        USER.column("full_name", ColumnType.STRING).column("username", ColumnType.STRING)
                .column("group_id", ColumnType.GUID).column("household_id", ColumnType.GUID).baseColumns()
                .unfilterable("email", "password", "auth_method", "admin", "advanced", "cache_key",
                        "external_avatar_hash", "login_attemps", "locked_at", "tokens_valid_after",
                        "show_announcements", "last_read_announcement", "can_manage_household", "can_manage",
                        "can_invite", "can_organize", "owned_recipes_id")
                .relation("group", () -> GROUP, "groups", false, "groups.id = users.group_id")
                .relation("household", () -> HOUSEHOLD, "households", false, "households.id = users.household_id");

        RECIPE.column("slug", ColumnType.STRING).column("group_id", ColumnType.GUID)
                .column("user_id", ColumnType.GUID).column("rating", ColumnType.FLOAT)
                .column("name", ColumnType.STRING).column("description", ColumnType.STRING)
                .column("image", ColumnType.STRING).column("total_time", ColumnType.STRING)
                .column("prep_time", ColumnType.STRING).column("perform_time", ColumnType.STRING)
                .column("total_time_seconds", ColumnType.INTEGER).column("prep_time_seconds", ColumnType.INTEGER)
                .column("perform_time_seconds", ColumnType.INTEGER).column("recipe_yield", ColumnType.STRING)
                .column("recipe_yield_quantity", ColumnType.FLOAT).column("recipe_servings", ColumnType.FLOAT)
                .column("org_url", ColumnType.STRING).column("date_added", ColumnType.DATE)
                .column("date_updated", ColumnType.DATETIME).column("last_made", ColumnType.DATETIME)
                .column("name_normalized", ColumnType.STRING).column("description_normalized", ColumnType.STRING)
                .baseColumns()
                .unfilterable("cook_time", "recipeCuisine", "is_ocr_recipe")
                .relation("group", () -> GROUP, "groups", false, "groups.id = recipes.group_id")
                .relation("user", () -> USER, "users", false, "users.id = recipes.user_id")
                .proxy("household_id", "user", "household_id")
                .proxy("household", "user", "household")
                .secondary("tags", () -> TAG, "tags", "recipes_to_tags",
                        "recipes.id = recipes_to_tags.recipe_id", "tags.id = recipes_to_tags.tag_id")
                .secondary("recipe_category", () -> CATEGORY, "categories", "recipes_to_categories",
                        "recipes.id = recipes_to_categories.recipe_id",
                        "categories.id = recipes_to_categories.category_id")
                .secondary("tools", () -> TOOL, "tools", "recipes_to_tools",
                        "recipes.id = recipes_to_tools.recipe_id", "tools.id = recipes_to_tools.tool_id")
                .secondary("made_by", () -> HOUSEHOLD, "households", "households_to_recipes",
                        "recipes.id = households_to_recipes.recipe_id",
                        "households.id = households_to_recipes.household_id");

        TAG.column("group_id", ColumnType.GUID).column("name", ColumnType.STRING).column("slug", ColumnType.STRING)
                .baseColumns()
                .unfilterable("recipe_count")
                .relation("group", () -> GROUP, "groups", false, "groups.id = tags.group_id")
                .secondary("recipes", () -> RECIPE, "recipes", "recipes_to_tags",
                        "tags.id = recipes_to_tags.tag_id", "recipes.id = recipes_to_tags.recipe_id");

        CATEGORY.column("group_id", ColumnType.GUID).column("name", ColumnType.STRING)
                .column("slug", ColumnType.STRING).baseColumns()
                .unfilterable("recipe_count")
                .relation("group", () -> GROUP, "groups", false, "groups.id = categories.group_id")
                .secondary("recipes", () -> RECIPE, "recipes", "recipes_to_categories",
                        "categories.id = recipes_to_categories.category_id",
                        "recipes.id = recipes_to_categories.recipe_id");

        TOOL.column("group_id", ColumnType.GUID).column("name", ColumnType.STRING).column("slug", ColumnType.STRING)
                .baseColumns()
                .unfilterable("recipe_count", "on_hand")
                .relation("group", () -> GROUP, "groups", false, "groups.id = tools.group_id")
                .secondary("households_with_tool", () -> HOUSEHOLD, "households", "households_to_tools",
                        "tools.id = households_to_tools.tool_id", "households.id = households_to_tools.household_id")
                .secondary("recipes", () -> RECIPE, "recipes", "recipes_to_tools",
                        "tools.id = recipes_to_tools.tool_id", "recipes.id = recipes_to_tools.recipe_id");
    }
}
