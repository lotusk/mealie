package io.mealie.backend.persistence.model;

import java.util.UUID;

public record GroupHouseholdRow(
        UUID id, UUID groupId, String name, String slug, UUID preferencesId,
        Boolean privateHousehold, Boolean showAnnouncements, Boolean lockRecipeEditsFromOtherHouseholds,
        Integer firstDayOfWeek, Boolean recipePublic, Boolean recipeShowNutrition, Boolean recipeShowAssets,
        Boolean recipeLandscapeView, Boolean recipeDisableComments) {
}
