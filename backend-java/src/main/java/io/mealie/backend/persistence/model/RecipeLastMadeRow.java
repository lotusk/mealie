package io.mealie.backend.persistence.model;

import java.util.UUID;

public record RecipeLastMadeRow(UUID id, UUID householdId) { }
