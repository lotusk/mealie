package io.mealie.backend.persistence.model;

import java.util.UUID;

public record UserRatingRow(UUID recipeId, Double rating, Boolean isFavorite) {}
