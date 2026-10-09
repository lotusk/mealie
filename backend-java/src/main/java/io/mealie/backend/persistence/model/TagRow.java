package io.mealie.backend.persistence.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TagRow(UUID id, UUID groupId, String name, String slug, long recipeCount,
        OffsetDateTime createdAt, OffsetDateTime updateAt) {
}
