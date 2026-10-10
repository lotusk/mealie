package io.mealie.backend.persistence.model;

import java.util.UUID;

public record UserHouseholdRow(UUID userId, UUID householdId) {
}
