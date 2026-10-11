package io.mealie.backend.user;

import java.util.List;
import java.util.UUID;

public record UserRatingsResponse(List<Rating> ratings) {

    public record Rating(UUID recipeId, Double rating, boolean isFavorite) {}
}
