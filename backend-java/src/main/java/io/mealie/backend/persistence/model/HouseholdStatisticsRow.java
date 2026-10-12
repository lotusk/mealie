package io.mealie.backend.persistence.model;

/** Counts returned by the Python HouseholdStatistics schema. */
public record HouseholdStatisticsRow(
        long totalRecipes,
        long totalUsers,
        long totalCategories,
        long totalTags,
        long totalTools) {
}
