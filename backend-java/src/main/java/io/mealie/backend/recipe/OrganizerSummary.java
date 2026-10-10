package io.mealie.backend.recipe;

import java.util.UUID;

/**
 * Python's RecipeTag / RecipeCategory (mealie/schema/recipe/recipe.py), and TagOut, which has the same fields: a tag
 * or category with the number of recipes it is on.
 */
public record OrganizerSummary(UUID id, UUID groupId, String name, String slug, long recipeCount) {
}
