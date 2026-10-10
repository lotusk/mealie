package io.mealie.backend.group;

import java.util.List;
import java.util.UUID;

/** JSON contract of Python's GroupSummary. */
public record GroupSelfResponse(
        String name, UUID id, String slug, Preferences preferences, AiProviderSettings aiProviderSettings) {

    public record Preferences(boolean privateGroup, boolean showAnnouncements, UUID groupId, UUID id) {
    }

    public record AiProviderSettings(
            UUID defaultProviderId, UUID audioProviderId, UUID imageProviderId,
            List<AiProviderSummary> providers,
            boolean aiEnabled, boolean audioProviderEnabled, boolean imageProviderEnabled) {
    }

    public record AiProviderSummary(UUID id, String name) {
    }
}
