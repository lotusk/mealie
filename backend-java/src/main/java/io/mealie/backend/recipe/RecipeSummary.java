package io.mealie.backend.recipe;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * Python's RecipeSummary as it serializes: dates and datetimes are pre-rendered strings in pydantic's format, and
 * the yield/servings numbers are floats (0.0 when unset).
 */
public record RecipeSummary(
        UUID id,
        UUID userId,
        UUID householdId,
        UUID groupId,
        String name,
        String slug,
        String image,
        double recipeServings,
        double recipeYieldQuantity,
        String recipeYield,
        String totalTime,
        String prepTime,
        String cookTime,
        String performTime,
        Integer totalTimeSeconds,
        Integer prepTimeSeconds,
        Integer performTimeSeconds,
        String description,
        List<OrganizerSummary> recipeCategory,
        List<OrganizerSummary> tags,
        List<RecipeToolSummary> tools,
        Double rating,
        @JsonProperty("orgURL") String orgUrl,
        String dateAdded,
        String dateUpdated,
        String createdAt,
        String updatedAt,
        String lastMade) {
}
