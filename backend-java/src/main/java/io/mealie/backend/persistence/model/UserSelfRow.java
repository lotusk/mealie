package io.mealie.backend.persistence.model;

import java.util.UUID;

/** The fields Python's UserOut reads from the user and its group/household relationships. */
public record UserSelfRow(
        UUID id, String username, String fullName, String email, String authMethod,
        Boolean admin, Boolean advanced, Boolean showAnnouncements, String lastReadAnnouncement,
        Boolean canInvite, Boolean canManage, Boolean canManageHousehold, Boolean canOrganize,
        UUID groupId, String groupName, String groupSlug,
        UUID householdId, String householdName, String householdSlug, String cacheKey) {
}
