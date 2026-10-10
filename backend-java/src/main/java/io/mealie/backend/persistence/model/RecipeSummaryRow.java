package io.mealie.backend.persistence.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of a recipe joined with its categories, tools and tags, the way SQLAlchemy joined-eager-loads a
 * RecipeSummary. A recipe with several organizers spans several rows; the organizer columns are null when the recipe
 * has none of that kind. Fields are set by MyBatis.
 */
public class RecipeSummaryRow {
    public UUID recipeId;
    public String slug;
    public UUID groupId;
    public UUID userId;
    public Double rating;
    public String name;
    public String description;
    public String image;
    public String totalTime;
    public String prepTime;
    public String performTime;
    public String cookTime;
    public Integer totalTimeSeconds;
    public Integer prepTimeSeconds;
    public Integer performTimeSeconds;
    public String recipeYield;
    public Double recipeYieldQuantity;
    public Double recipeServings;
    public String orgUrl;
    public LocalDate dateAdded;
    public OffsetDateTime dateUpdated;
    public OffsetDateTime lastMade;
    public OffsetDateTime createdAt;
    public OffsetDateTime updateAt;

    public UUID categoryId;
    public UUID categoryGroupId;
    public String categoryName;
    public String categorySlug;

    public UUID toolId;
    public UUID toolGroupId;
    public String toolName;
    public String toolSlug;

    public UUID tagId;
    public UUID tagGroupId;
    public String tagName;
    public String tagSlug;
}
