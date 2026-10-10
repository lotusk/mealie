package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.RecipeCreateMapper;
import io.mealie.backend.persistence.model.RecipeCreationContextRow;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RecipeCreateRepository {
    private final RecipeCreateMapper mapper;
    private final SqlDialect dialect;

    public RecipeCreateRepository(RecipeCreateMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }

    RecipeCreationContextRow context(AuthUser user) {
        return mapper.context(dialect.uuid(user.id()), dialect.uuid(user.groupId()), dialect.uuid(user.householdId()));
    }

    Map<String, Object> values(UUID id, String name, String slug, AuthUser user, OffsetDateTime timestamp,
            RecipeCreationContextRow context, RecipeCreationLocale.Defaults defaults) {
        var values = new LinkedHashMap<String, Object>();
        values.put("recipeId", dialect.uuid(id));
        values.put("groupId", dialect.uuid(user.groupId()));
        values.put("userId", dialect.uuid(user.id()));
        values.put("name", name);
        values.put("slug", slug);
        values.put("nameNormalized", RecipeSlug.normalize(name));
        values.put("ingredientNote", defaults.ingredientNote());
        values.put("ingredientNormalized", RecipeSlug.normalize(defaults.ingredientNote()));
        values.put("instructionText", defaults.instructionText());
        values.put("referenceId", dialect.uuid(UUID.randomUUID()));
        values.put("instructionId", dialect.uuid(UUID.randomUUID()));
        values.put("eventId", dialect.uuid(UUID.randomUUID()));
        values.put("timestamp", dialect.timestamp(timestamp));
        values.put("dateAdded", dialect.date(timestamp.toLocalDate()));
        values.put("yes", dialect.bool(true));
        values.put("no", dialect.bool(false));
        boolean fallback = context.preferencesId() == null;
        values.put("public", dialect.bool(!fallback && Boolean.TRUE.equals(context.recipePublic())));
        values.put("showNutrition", dialect.bool(!fallback && Boolean.TRUE.equals(context.recipeShowNutrition())));
        values.put("showAssets", dialect.bool(!fallback && Boolean.TRUE.equals(context.recipeShowAssets())));
        values.put("landscapeView", dialect.bool(!fallback && Boolean.TRUE.equals(context.recipeLandscapeView())));
        values.put("disableComments", dialect.bool(fallback || Boolean.TRUE.equals(context.recipeDisableComments())));
        return values;
    }

    void insertRecipe(Map<String, Object> values) {
        mapper.insertRecipe(values);
        mapper.insertIngredient(values);
        mapper.insertInstruction(values);
        mapper.insertNutrition(values);
        mapper.insertSettings(values);
    }

    void insertTimeline(Map<String, Object> values) {
        values.put("timelineTimestamp", dialect.timestamp(OffsetDateTime.now(java.time.ZoneOffset.UTC)));
        mapper.insertTimeline(values);
    }
}
