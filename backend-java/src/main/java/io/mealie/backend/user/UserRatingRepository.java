package io.mealie.backend.user;

import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.UserRatingMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class UserRatingRepository {
    private final UserRatingMapper mapper;
    private final SqlDialect dialect;

    public UserRatingRepository(UserRatingMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }

    public Optional<UUID> findRecipe(UUID groupId, String slugOrId) {
        Optional<UUID> id = parseUuid(slugOrId);
        var row = mapper.findRecipe(dialect.uuid(groupId), id.map(dialect::uuid).orElse(null), slugOrId);
        return row == null ? Optional.empty() : Optional.of(row.id());
    }

    public void save(UUID userId, UUID recipeId, UUID groupId, UserRatingUpdate data) {
        Object user = dialect.uuid(userId);
        Object recipe = dialect.uuid(recipeId);
        Object now = dialect.timestamp(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        var existing = mapper.findRating(user, recipe);
        if (existing == null) {
            mapper.insert(dialect.uuid(UUID.randomUUID()), user, recipe, data.rating(),
                    dialect.bool(Boolean.TRUE.equals(data.isFavorite())), now);
        } else {
            Double rating = data.rating() == null ? existing.rating() : data.rating();
            Boolean favorite = data.isFavorite() == null ? Boolean.TRUE.equals(existing.isFavorite()) : data.isFavorite();
            // SQLAlchemy doesn't issue an UPDATE (or advance update_at) when field values are unchanged.
            if (!Objects.equals(rating, existing.rating()) || !Objects.equals(favorite, existing.isFavorite())) {
                mapper.update(dialect.uuid(existing.id()), user, recipe, rating, dialect.bool(favorite), now);
            }
        }
        // Match UserToRecipe's ORM hook: only positive scores count; favorite-only rows don't count.
        mapper.recalculate(recipe, dialect.uuid(groupId), now);
    }

    private static Optional<UUID> parseUuid(String value) {
        String hex = value.replace("urn:", "").replace("uuid:", "").replace("-", "");
        while (hex.startsWith("{")) hex = hex.substring(1);
        while (hex.endsWith("}")) hex = hex.substring(0, hex.length() - 1);
        if (hex.length() != 32) return Optional.empty();
        try {
            return Optional.of(new UUID(HexFormat.fromHexDigitsToLong(hex, 0, 16),
                    HexFormat.fromHexDigitsToLong(hex, 16, 32)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
