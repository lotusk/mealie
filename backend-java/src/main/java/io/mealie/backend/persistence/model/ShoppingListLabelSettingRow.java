package io.mealie.backend.persistence.model;

import java.util.UUID;

public record ShoppingListLabelSettingRow(UUID id, UUID shoppingListId, UUID labelId, Integer position,
        UUID groupId, String name, String color) {
}
