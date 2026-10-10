package io.mealie.backend.group;

import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.group.GroupSelfResponse.AiProviderSettings;
import io.mealie.backend.group.GroupSelfResponse.AiProviderSummary;
import io.mealie.backend.group.GroupSelfResponse.Preferences;
import io.mealie.backend.persistence.mapper.GroupSelfMapper;
import io.mealie.backend.persistence.model.GroupSelfRow;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class GroupSelfRepository {

    private final GroupSelfMapper mapper;
    private final SqlDialect dialect;

    public GroupSelfRepository(GroupSelfMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }

    public Optional<GroupSelfResponse> findById(UUID id) {
        GroupSelfRow row = mapper.findById(dialect.uuid(id));
        if (row == null) {
            return Optional.empty();
        }
        Preferences preferences = row.preferencesId() == null ? null : new Preferences(
                Boolean.TRUE.equals(row.privateGroup()), Boolean.TRUE.equals(row.showAnnouncements()),
                row.id(), row.preferencesId());
        AiProviderSettings aiSettings = row.aiSettingsId() == null ? null : aiSettings(row);
        return Optional.of(new GroupSelfResponse(row.name(), row.id(), row.slug(), preferences, aiSettings));
    }

    private AiProviderSettings aiSettings(GroupSelfRow row) {
        List<AiProviderSummary> providers = mapper.findProviders(dialect.uuid(row.aiSettingsId())).stream()
                .map(provider -> new AiProviderSummary(provider.id(), provider.name())).toList();
        Set<UUID> providerIds = providers.stream().map(AiProviderSummary::id).collect(Collectors.toSet());
        // Python's AIProviderSettingsOut clears stale configured ids that are not in this settings' providers.
        UUID defaultId = validProvider(row.defaultProviderId(), providerIds);
        UUID audioId = validProvider(row.audioProviderId(), providerIds);
        UUID imageId = validProvider(row.imageProviderId(), providerIds);
        boolean enabled = defaultId != null;
        return new AiProviderSettings(defaultId, audioId, imageId, providers,
                enabled, enabled && audioId != null, enabled && imageId != null);
    }

    private static UUID validProvider(UUID id, Set<UUID> providerIds) {
        return providerIds.contains(id) ? id : null;
    }
}
