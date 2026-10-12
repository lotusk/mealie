package io.mealie.backend.persistence.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ShoppingListRow(UUID id, UUID groupId, UUID userId, UUID householdId, String name,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
