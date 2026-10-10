package io.mealie.backend.persistence.model;

import java.util.UUID;

/** GroupSummary and its optional preferences and AI settings, all scoped by the authenticated group id. */
public record GroupSelfRow(
        UUID id, String name, String slug,
        UUID preferencesId, Boolean privateGroup, Boolean showAnnouncements,
        UUID aiSettingsId, UUID defaultProviderId, UUID audioProviderId, UUID imageProviderId) {
}
