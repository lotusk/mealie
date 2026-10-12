package io.mealie.backend.persistence.model;

import java.util.UUID;

public record UserRatingWriteRow(UUID id, Double rating, Boolean isFavorite) {}
