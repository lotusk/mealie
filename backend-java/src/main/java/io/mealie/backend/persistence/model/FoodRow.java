package io.mealie.backend.persistence.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FoodRow(
        UUID id, String name, String pluralName, String description, UUID labelId,
        UUID joinedLabelId, String labelName, String labelColor, UUID labelGroupId,
        long recipeCount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
