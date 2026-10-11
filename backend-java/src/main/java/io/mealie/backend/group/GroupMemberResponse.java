package io.mealie.backend.group;

import java.util.Objects;
import java.util.UUID;

/** Python's UserSummary; deliberately excludes private user fields. */
public record GroupMemberResponse(UUID id, UUID groupId, UUID householdId, String username, String fullName) {

    public GroupMemberResponse {
        // UserSummary requires all five values, even though some database columns are nullable.
        Objects.requireNonNull(id);
        Objects.requireNonNull(groupId);
        Objects.requireNonNull(householdId);
        Objects.requireNonNull(username);
        Objects.requireNonNull(fullName);
    }
}
