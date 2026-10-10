package io.mealie.backend.persistence.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CategoryRow(UUID id, UUID groupId, String name, String slug, OffsetDateTime createdAt,
        OffsetDateTime updateAt, long recipeCount) {}
