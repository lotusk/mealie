package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecipeCreateService {
    private final RecipeCreateRepository recipes;
    private final RecipeCreationLocale locales;
    private final RecipeCreatedPublisher publisher;
    private final TransactionTemplate transaction;

    public RecipeCreateService(RecipeCreateRepository recipes, RecipeCreationLocale locales,
            RecipeCreatedPublisher publisher, PlatformTransactionManager manager) {
        this.recipes = recipes;
        this.locales = locales;
        this.publisher = publisher;
        transaction = new TransactionTemplate(manager);
    }

    public String create(String name, AuthUser user, String locale, String integrationId) {
        // Python's ORM asserts an empty name; whitespace/special characters fail slug generation instead.
        if (name.isEmpty()) throw RecipeCreateFailure.error(500, "Unknown Error", "AssertionError");
        String slug = RecipeSlug.create(name);
        publisher.requireConfigured();
        var context = recipes.context(user);
        if (context == null) throw RecipeCreateFailure.error(500, "Unknown Error", "AttributeError");
        if (context.preferencesId() != null && (context.recipePublic() == null || context.recipeShowNutrition() == null
                || context.recipeShowAssets() == null || context.recipeLandscapeView() == null || context.recipeDisableComments() == null)) {
            throw RecipeCreateFailure.error(500, "Unknown Error", "ValidationError");
        }
        UUID id = UUID.randomUUID();
        Map<String, Object> values = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidateName = attempt == 0 ? name : name + " (" + attempt + ")";
            slug = RecipeSlug.create(candidateName);
            var candidate = recipes.values(id, candidateName, slug, user, OffsetDateTime.now(ZoneOffset.UTC),
                    context, locales.defaults(locale));
            try {
                transaction.executeWithoutResult(ignored -> recipes.insertRecipe(candidate));
                values = candidate;
                break;
            } catch (DataAccessException failure) {
                if (!integrity(failure)) throw RecipeCreateFailure.error(500, "Unknown Error", "OperationalError");
                // Like RepositoryRecipes.create, roll back the entire attempt before trying another name.
                if (attempt == 9) throw RecipeCreateFailure.error(400, "Recipe already exists", null);
            }
        }
        if (values == null) throw RecipeCreateFailure.error(400, "Recipe already exists", null);
        // Python commits the recipe before writing its first timeline entry. Preserve that failure boundary.
        var created = values;
        try {
            transaction.executeWithoutResult(ignored -> recipes.insertTimeline(created));
        } catch (DataAccessException failure) {
            if (integrity(failure)) throw RecipeCreateFailure.error(400, "Recipe already exists", null);
            throw RecipeCreateFailure.error(500, "Unknown Error", "OperationalError");
        }
        if (integrationId == null) throw RecipeCreateFailure.error(500, "Unknown Error", "ValidationError");
        org.slf4j.LoggerFactory.getLogger(RecipeCreateService.class).info(
                "JAVA_RECIPE_CREATED recipe={} group={} slug={}", id, user.groupId(), slug);
        publisher.publish(id, user, locale, integrationId);
        return slug;
    }
    static boolean integrity(DataAccessException failure) {
        if (failure instanceof DataIntegrityViolationException) return true;
        if (failure.getMostSpecificCause() instanceof java.sql.SQLException sql) {
            // SQLite JDBC reports constraint errors with code 19 but no SQLSTATE.
            return sql.getErrorCode() == 19 || (sql.getSQLState() != null && sql.getSQLState().startsWith("23"));
        }
        return false;
    }
}
