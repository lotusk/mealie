package io.mealie.backend.recipe;

import java.util.List;
import java.util.UUID;

/** Python's RecipeTool: an organizer plus the slugs of the households that have the tool. */
public record RecipeToolSummary(
        UUID id, UUID groupId, String name, String slug, long recipeCount, List<String> householdsWithTool) {
}
