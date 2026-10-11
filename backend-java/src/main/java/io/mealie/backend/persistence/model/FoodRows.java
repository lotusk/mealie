package io.mealie.backend.persistence.model;

import java.util.UUID;

public final class FoodRows {
    private FoodRows() {}
    public record NameRow(UUID foodId, String value) {}
    public record ExtraRow(UUID foodId, String key, String value) {}
    public record SubstitutionRow(
            UUID foodId, UUID substituteFoodId, String note, UUID joinedId, String name, String pluralName) {}
}
