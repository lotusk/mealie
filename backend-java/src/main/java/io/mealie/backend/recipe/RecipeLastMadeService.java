package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecipeLastMadeService {
    private final RecipeLastMadeRepository updates;
    private final RecipeService reads;
    private final RecipeCreatedPublisher publisher;
    private final TransactionTemplate transaction;
    public RecipeLastMadeService(RecipeLastMadeRepository updates, RecipeService reads,
            RecipeCreatedPublisher publisher, PlatformTransactionManager manager) {
        this.updates = updates;
        this.reads = reads;
        this.publisher = publisher;
        transaction = new TransactionTemplate(manager);
    }
    Map<String, Object> update(String slug, OffsetDateTime timestamp, AuthUser user, String locale, String integrationId) {
        publisher.requireUpdateConfigured();
        io.mealie.backend.persistence.model.RecipeLastMadeRow target;
        try { target = transaction.execute(ignored -> updates.update(slug, user, timestamp)); }
        catch (DataAccessException error) {
            boolean dataError = error.getMostSpecificCause() instanceof java.sql.SQLException sql
                    && sql.getSQLState() != null && sql.getSQLState().startsWith("22");
            if (dataError) throw RecipeCreateFailure.error(500, "Unknown Error", "DataError");
            if (RecipeCreateService.integrity(error)) throw RecipeCreateFailure.error(400, "Recipe already exists", null);
            throw RecipeCreateFailure.error(500, "Unknown Error", "OperationalError");
        }
        var recipe = reads.getOne(slug, user, locale).orElseThrow(() -> RecipeCreateFailure.error(404, "No Entry Found", null));
        if (integrationId == null) throw RecipeCreateFailure.error(500, "Unknown Error", "ValidationError");
        publisher.publishUpdated(target.id(), target.householdId(), user, locale, integrationId);
        org.slf4j.LoggerFactory.getLogger(RecipeLastMadeService.class).info(
                "JAVA_RECIPE_LAST_MADE_UPDATED recipe={} group={} household={} timestamp={}", target.id(), user.groupId(), user.householdId(), timestamp);
        return recipe;
    }
}
