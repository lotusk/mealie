package io.mealie.backend.group;

import java.util.UUID;

/** Python's HouseholdSummary, without members, webhooks or deprecated preferences. */
public record GroupHouseholdResponse(UUID groupId, String name, UUID id, String slug, Preferences preferences) {

    public record Preferences(
            boolean privateHousehold, boolean showAnnouncements, boolean lockRecipeEditsFromOtherHouseholds,
            int firstDayOfWeek, boolean recipePublic, boolean recipeShowNutrition, boolean recipeShowAssets,
            boolean recipeLandscapeView, boolean recipeDisableComments, UUID id) {
    }
}
