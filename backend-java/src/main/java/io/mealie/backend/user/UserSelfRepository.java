package io.mealie.backend.user;

import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.UserSelfMapper;
import io.mealie.backend.persistence.model.UserSelfRow;
import io.mealie.backend.persistence.model.UserSelfTokenRow;
import io.mealie.backend.user.UserSelfResponse.LongLiveToken;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class UserSelfRepository {

    private final UserSelfMapper mapper;
    private final SqlDialect dialect;

    public UserSelfRepository(UserSelfMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }

    public Optional<UserSelfResponse> findById(UUID id) {
        UserSelfRow row = mapper.findById(dialect.uuid(id));
        if (row == null) {
            return Optional.empty();
        }
        List<LongLiveToken> tokens = mapper.findTokens(dialect.uuid(id)).stream()
                .map(this::toToken).toList();
        return Optional.of(new UserSelfResponse(
                row.id(), row.username(), row.fullName(), row.email().strip().toLowerCase(Locale.ROOT),
                authMethodValue(row.authMethod()),
                Boolean.TRUE.equals(row.admin()), row.groupName(), row.householdName(),
                Boolean.TRUE.equals(row.advanced()), Boolean.TRUE.equals(row.showAnnouncements()),
                row.lastReadAnnouncement(), Boolean.TRUE.equals(row.canInvite()),
                Boolean.TRUE.equals(row.canManage()), Boolean.TRUE.equals(row.canManageHousehold()),
                Boolean.TRUE.equals(row.canOrganize()), row.groupId(), row.groupSlug(),
                row.householdId(), row.householdSlug(), tokens, row.cacheKey()));
    }

    private LongLiveToken toToken(UserSelfTokenRow row) {
        return new LongLiveToken(row.name(), row.id(), row.createdAt());
    }

    private static String authMethodValue(String name) {
        return "MEALIE".equals(name) ? "Mealie" : name;
    }
}
