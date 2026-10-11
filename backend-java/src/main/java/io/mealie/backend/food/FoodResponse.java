package io.mealie.backend.food;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** IngredientFood response; groupId is intentionally absent from the food itself. */
public record FoodResponse(
        UUID id, String name, String pluralName, String description, Map<String, String> extras, UUID labelId,
        List<Alias> aliases, List<Substitution> substitutions, List<String> householdsWithIngredientFood,
        Label label, long recipeCount, String createdAt, String updatedAt) {
    public record Alias(String name) {}
    public record Summary(UUID id, String name, String pluralName) {}
    public record Substitution(UUID substituteFoodId, String note, Summary substituteFood) {}
    public record Label(String name, String color, UUID groupId, UUID id) {}
}
