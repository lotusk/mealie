package io.mealie.backend.persistence.model;

/** Household defaults are read through the authenticated owner's group and household. */
public record RecipeCreationContextRow(String householdId, String preferencesId, Boolean recipePublic, Boolean recipeShowNutrition,
        Boolean recipeShowAssets, Boolean recipeLandscapeView, Boolean recipeDisableComments) { }
