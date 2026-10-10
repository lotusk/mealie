package io.mealie.backend.persistence.model;

import java.util.UUID;

/** A tool and its correlated recipe usage count. */
public record ToolRow(UUID id, UUID groupId, String name, String slug, long recipeCount) {
}
