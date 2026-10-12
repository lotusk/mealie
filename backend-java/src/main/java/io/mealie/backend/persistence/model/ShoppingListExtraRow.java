package io.mealie.backend.persistence.model;

import java.util.UUID;

public record ShoppingListExtraRow(UUID shoppingListId, String keyName, String value) {
}
