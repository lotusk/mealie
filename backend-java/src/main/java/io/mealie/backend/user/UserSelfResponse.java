package io.mealie.backend.user;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Camel-case JSON contract of Python's UserOut, excluding private authentication fields. */
public record UserSelfResponse(
        UUID id, String username, String fullName, String email, String authMethod,
        boolean admin, String group, String household, boolean advanced,
        boolean showAnnouncements, String lastReadAnnouncement,
        boolean canInvite, boolean canManage, boolean canManageHousehold, boolean canOrganize,
        UUID groupId, String groupSlug, UUID householdId, String householdSlug,
        List<LongLiveToken> tokens, String cacheKey) {

    public record LongLiveToken(String name, int id, OffsetDateTime createdAt) {
    }
}
