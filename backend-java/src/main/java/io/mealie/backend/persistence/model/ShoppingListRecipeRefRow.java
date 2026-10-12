package io.mealie.backend.persistence.model;

import java.util.UUID;

public record ShoppingListRecipeRefRow(UUID id, UUID shoppingListId, UUID recipeId, Double recipeQuantity) {
}
