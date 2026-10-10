package io.mealie.backend.persistence.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A row of {@code tags}, with the number of recipes that carry the tag. */
public record TagRow(
        UUID id,
        UUID groupId,
        String name,
        String slug,
        OffsetDateTime createdAt,
        OffsetDateTime updateAt,
        long recipeCount) {
}
