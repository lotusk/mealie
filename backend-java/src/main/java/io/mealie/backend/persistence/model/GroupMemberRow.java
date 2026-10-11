package io.mealie.backend.persistence.model;

import java.util.UUID;

public record GroupMemberRow(UUID id, UUID groupId, UUID householdId, String username, String fullName) {
}
