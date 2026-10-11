package io.mealie.backend.recipe;
import io.mealie.backend.auth.AuthUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class RecipeDuplicateService {
    private final RecipeRepository reads;
    private final RecipeDuplicateRepository writes;
    private final RecipeDuplicateFiles files;
    private final RecipeCreatedPublisher publisher;
    private final TransactionTemplate transaction;
    public RecipeDuplicateService(RecipeRepository reads, RecipeDuplicateRepository writes, RecipeDuplicateFiles files,
            RecipeCreatedPublisher publisher, PlatformTransactionManager manager) {
        this.reads = reads; this.writes = writes; this.files = files; this.publisher = publisher;
        transaction = new TransactionTemplate(manager);
    }
    static class NotificationMetadataException extends RuntimeException { }

    public Map<String, Object> duplicate(String slugOrId, String requestedName, AuthUser user, String locale, String integration) {
        var source = reads.find(slugOrId, user.groupId(), locale)
                .orElseThrow(() -> RecipeCreateFailure.error(404, "No Entry Found", null));
        String name = requestedName == null || requestedName.isEmpty() ? (String) source.get("name") : requestedName;
        if (name == null) name = "";
        publisher.requireConfigured();
        UUID id = UUID.randomUUID();
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = attempt == 0 ? name : name + " (" + attempt + ")";
            String slug = RecipeSlug.create(candidate);
            try {
                transaction.executeWithoutResult(ignored -> writes.insert(source, id, candidate, slug, user, locale));
                var copied = reads.find(id.toString(), user.groupId(), locale)
                        .orElseThrow(RecipeService.InvalidRecipeException::new);
                // Python commits all copied rows before copytree; file failures retain the copy and still notify.
                files.copy(UUID.fromString((String) source.get("id")), id, (String) source.get("slug"), slug);
                if (integration == null) throw new NotificationMetadataException();
                org.slf4j.LoggerFactory.getLogger(RecipeDuplicateService.class).info(
                        "JAVA_RECIPE_DUPLICATED source={} recipe={} group={} slug={}", source.get("id"), id, user.groupId(), slug);
                publisher.publishDuplicated(id, user, locale, integration);
                return copied;
            } catch (DataAccessException failure) {
                if (!RecipeCreateService.integrity(failure)) throw RecipeCreateFailure.error(500, "Unknown Error", "OperationalError");
                if (attempt == 9) throw RecipeCreateFailure.error(400, "Recipe already exists", null);
            }
        }
        throw RecipeCreateFailure.error(400, "Recipe already exists", null);
    }
}
