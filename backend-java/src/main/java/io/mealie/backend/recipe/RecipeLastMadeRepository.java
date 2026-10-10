package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.RecipeLastMadeMapper;
import io.mealie.backend.persistence.model.RecipeLastMadeRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RecipeLastMadeRepository {
    private final RecipeLastMadeMapper mapper;
    private final SqlDialect dialect;
    public RecipeLastMadeRepository(RecipeLastMadeMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }
    RecipeLastMadeRow update(String slug, AuthUser user, OffsetDateTime timestamp) {
        var values = new LinkedHashMap<String, Object>();
        values.put("groupId", dialect.uuid(user.groupId()));
        values.put("recipeId", RecipeIdentifier.uuid(slug).map(id -> dialect.recipeWriteLookupUuid(id, slug)).orElse(null));
        values.put("slug", slug);
        var recipe = mapper.find(values);
        if (recipe == null) throw RecipeCreateFailure.error(404, "No Entry Found", null);
        values.put("recipeId", dialect.uuid(recipe.id()));
        values.put("householdId", dialect.uuid(user.householdId()));
        values.put("linkId", dialect.uuid(UUID.randomUUID()));
        values.put("lastMade", dialect.timestamp(timestamp));
        values.put("now", dialect.timestamp(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)));
        mapper.setHousehold(values);
        // SQLAlchemy's association listener only advances this value; lowering one household never lowers it.
        mapper.advanceRecipe(values);
        return recipe;
    }
}
