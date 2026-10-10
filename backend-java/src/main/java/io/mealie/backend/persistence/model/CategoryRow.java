package io.mealie.backend.persistence.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A row of {@code categories}, with the number of recipes that carry the category. */
public record CategoryRow(
        UUID id,
        UUID groupId,
        String name,
        String slug,
        OffsetDateTime createdAt,
        OffsetDateTime updateAt,
        long recipeCount) {
}
