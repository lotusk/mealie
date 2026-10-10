package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PyRepr;
import io.mealie.backend.compat.PySlugify;
import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.IntegrityViolation;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.events.EventBridge;
import io.mealie.backend.persistence.model.CategoryRow;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.recipe.OrganizerSummary;
import io.mealie.backend.recipe.RecipeRepository;
import io.mealie.backend.web.ApiException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category operations of CategoryController (mealie/routes/organizers/controller_categories.py): permission
 * checks, the Python-compatible error responses (HttpRepo's), and the category_created/updated/deleted events.
 */
@Service
public class CategoryService {

    /** router.url_path_for("get_all"): next/previous links are relative to the categories router. */
    static final String LIST_ROUTE = "/categories";

    /** The controller-local CategorySummary: GET by id and PUT. */
    public record CategorySummary(UUID id, String slug, String name) {
    }

    /** CategoryBase: the response model of /empty. */
    public record CategoryBase(String name, UUID id, UUID groupId, String slug) {
    }

    /** RecipeCategoryResponse.model_construct(): groupId is never passed, so it is always null. */
    public record CategoryResponse(String name, UUID id, UUID groupId, String slug,
            List<Map<String, Object>> recipes) {
    }

    private final CategoryRepository repository;
    private final RecipeRepository recipes;
    private final EventBridge events;
    private final TransactionTemplate transaction;
    private final SqlDialect dialect;

    public CategoryService(CategoryRepository repository, RecipeRepository recipes, EventBridge events,
            TransactionTemplate transaction, SqlDialect dialect) {
        this.repository = repository;
        this.recipes = recipes;
        this.events = events;
        this.transaction = transaction;
        this.dialect = dialect;
    }

    public Pagination<OrganizerSummary> list(AuthUser user, PaginationQuery query, String search) {
        return repository.page(user.groupId(), query, search, LIST_ROUTE, CategoryService::summary);
    }

    public List<CategoryBase> empty(AuthUser user) {
        return repository.findEmpty(user.groupId()).stream()
                .map(row -> new CategoryBase(row.name(), row.id(), row.groupId(), row.slug())).toList();
    }

    public CategorySummary get(AuthUser user, UUID id) {
        return brief(repository.findById(user.groupId(), id).orElseThrow(CategoryService::notFound));
    }

    public CategoryResponse getBySlug(AuthUser user, String slug) {
        CategoryRow category = repository.findBySlug(user.groupId(), slug).orElseThrow(CategoryService::notFound);
        List<UUID> ids = repository.recipeIdsForCategory(user.groupId(), category.id());
        return new CategoryResponse(category.name(), category.id(), null, category.slug(),
                recipes.jsonableSummaries(ids, user.groupId()));
    }

    public OrganizerSummary create(AuthUser user, String rawName, String acceptLanguage) {
        requireOrganizer(user);
        String name = PyStr.strip(rawName);
        if (name.isEmpty()) {
            // Category.validate_name asserts the name isn't empty; HttpRepo reports the AssertionError as a 400.
            throw emptyName();
        }
        String slug = PySlugify.slugify(name);
        CategoryRow category;
        try {
            category = transaction.execute(status -> repository.insert(user.groupId(), name, slug));
        } catch (RuntimeException e) {
            throw handleException(e, insertStatement(), insertParameters(user, name, slug));
        }
        publish(user, "category_created", "create", category, "notifications.generic-created-with-url", true,
                acceptLanguage);
        return summary(category);
    }

    /** HttpRepo.update_one(): an unknown id is a 404; only the changed columns are written. */
    public CategorySummary update(AuthUser user, UUID id, String rawName, String acceptLanguage) {
        requireOrganizer(user);
        CategoryRow existing = repository.findById(user.groupId(), id).orElseThrow(CategoryService::notFound);
        String name = PyStr.strip(rawName);
        if (name.isEmpty()) {
            throw emptyName();
        }
        String slug = PySlugify.slugify(name);
        String changedName = name.equals(existing.name()) ? null : name;
        String changedSlug = slug.equals(existing.slug()) ? null : slug;
        if (changedName != null || changedSlug != null) {
            try {
                transaction.executeWithoutResult(status -> repository.update(id, changedName, changedSlug));
            } catch (RuntimeException e) {
                throw handleException(e, updateStatement(changedName, changedSlug),
                        updateParameters(id, changedName, changedSlug));
            }
        }
        CategoryRow updated = new CategoryRow(id, existing.groupId(), name, slug, existing.createdAt(),
                existing.updateAt(), existing.recipeCount());
        publish(user, "category_updated", "update", updated, "notifications.generic-updated-with-url", true,
                acceptLanguage);
        return brief(updated);
    }

    /** HttpRepo.delete_one(): an unknown id is a NoResultFound, reported as a 404. */
    public void delete(AuthUser user, UUID id, String acceptLanguage) {
        requireOrganizer(user);
        CategoryRow deleted;
        try {
            deleted = transaction.execute(status -> {
                CategoryRow category = repository.findById(user.groupId(), id).orElseThrow(
                        () -> ApiException.errorResponse(HttpStatus.NOT_FOUND, "An unexpected error occurred.",
                                "No row was found when one was required"));
                repository.delete(id);
                return category;
            });
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw handleException(e, deleteStatement(), deleteParameters(id));
        }
        publish(user, "category_deleted", "delete", deleted, "notifications.generic-deleted", false,
                acceptLanguage);
    }

    /** merge_categories(): no event; failures after the checks are unhandled (500). */
    public OrganizerSummary merge(AuthUser user, UUID fromId, UUID toId) {
        requireOrganizer(user);
        if (fromId.equals(toId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "from_id and to_id must be different", Map.of());
        }
        if (repository.findById(user.groupId(), fromId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "from_id category not found", Map.of());
        }
        if (repository.findById(user.groupId(), toId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "to_id category not found", Map.of());
        }
        return transaction.execute(status -> {
            repository.merge(fromId, toId);
            return summary(repository.findById(user.groupId(), toId)
                    .orElseThrow(() -> new IllegalStateException("Merged category disappeared")));
        });
    }

    /** HttpRepo.handle_exception() for a failed write: 409 for a constraint violation, 400 for anything else. */
    private RuntimeException handleException(RuntimeException error, String statement, String parameters) {
        Optional<IntegrityViolation> violation = IntegrityViolation.of(error);
        if (violation.isEmpty()) {
            return ApiException.errorResponse(HttpStatus.BAD_REQUEST, "An unexpected error occurred.",
                    String.valueOf(error.getMessage()));
        }
        String message = violation.get().unique() ? "This item already exists." : "An unexpected error occurred.";
        return ApiException.errorResponse(HttpStatus.CONFLICT, message, violation.get().describe(statement, parameters));
    }

    private static ApiException notFound() {
        return ApiException.errorResponse(HttpStatus.NOT_FOUND, "Not found.", null);
    }

    private static ApiException emptyName() {
        return ApiException.errorResponse(HttpStatus.BAD_REQUEST, "An unexpected error occurred.", "");
    }

    private boolean postgres() {
        return dialect.engine() == DbEngine.POSTGRES;
    }

    private String insertStatement() {
        return postgres()
                ? "INSERT INTO categories (group_id, id, name, slug, created_at, update_at) VALUES "
                        + "(%(group_id)s::UUID, %(id)s::UUID, %(name)s, %(slug)s, %(created_at)s, %(update_at)s)"
                : "INSERT INTO categories (group_id, id, name, slug, created_at, update_at) VALUES (?, ?, ?, ?, ?, ?)";
    }

    /** The parameters as SQLAlchemy prints them; the id and timestamps are those of the failed attempt. */
    private String insertParameters(AuthUser user, String name, String slug) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID id = UUID.randomUUID();
        if (postgres()) {
            return "{'group_id': '" + user.groupId() + "', 'id': '" + id + "', 'name': " + PyRepr.repr(name)
                    + ", 'slug': " + PyRepr.repr(slug) + ", 'created_at': " + pgDatetime(now)
                    + ", 'update_at': " + pgDatetime(now) + "}";
        }
        String timestamp = sqliteTimestamp(now);
        return "(" + PyRepr.repr(dialect.uuid(user.groupId())) + ", " + PyRepr.repr(dialect.uuid(id)) + ", "
                + PyRepr.repr(name) + ", " + PyRepr.repr(slug) + ", '" + timestamp + "', '" + timestamp + "')";
    }

    /** SQLAlchemy's UPDATE lists only the changed columns, then update_at. */
    private String updateStatement(String name, String slug) {
        List<String> assignments = new ArrayList<>();
        if (name != null) assignments.add(postgres() ? "name=%(name)s" : "name=?");
        if (slug != null) assignments.add(postgres() ? "slug=%(slug)s" : "slug=?");
        assignments.add(postgres() ? "update_at=%(update_at)s" : "update_at=?");
        return "UPDATE categories SET " + String.join(", ", assignments) + " WHERE categories.id = "
                + (postgres() ? "%(categories_id)s::UUID" : "?");
    }

    private String updateParameters(UUID id, String name, String slug) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<String> values = new ArrayList<>();
        if (postgres()) {
            if (name != null) values.add("'name': " + PyRepr.repr(name));
            if (slug != null) values.add("'slug': " + PyRepr.repr(slug));
            values.add("'update_at': " + pgDatetime(now));
            values.add("'categories_id': '" + id + "'");
            return "{" + String.join(", ", values) + "}";
        }
        if (name != null) values.add(PyRepr.repr(name));
        if (slug != null) values.add(PyRepr.repr(slug));
        values.add("'" + sqliteTimestamp(now) + "'");
        values.add(PyRepr.repr(dialect.uuid(id)));
        return "(" + String.join(", ", values) + ")";
    }

    private String deleteStatement() {
        return "DELETE FROM categories WHERE categories.id = " + (postgres() ? "%(id)s::UUID" : "?");
    }

    private String deleteParameters(UUID id) {
        return postgres() ? "{'id': '" + id + "'}" : "(" + PyRepr.repr(dialect.uuid(id)) + ",)";
    }

    private static String pgDatetime(LocalDateTime now) {
        return "datetime.datetime(" + now.getYear() + ", " + now.getMonthValue() + ", " + now.getDayOfMonth() + ", "
                + now.getHour() + ", " + now.getMinute() + ", " + now.getSecond() + ", " + now.getNano() / 1000 + ")";
    }

    private static String sqliteTimestamp(LocalDateTime now) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").format(now);
    }

    /** OperationChecks.can_organize(). */
    private static void requireOrganizer(AuthUser user) {
        if (!user.canOrganize()) {
            throw new ApiException(HttpStatus.FORBIDDEN);
        }
    }

    private void publish(AuthUser user, String eventType, String operation, CategoryRow category, String messageKey,
            boolean withUrl, String acceptLanguage) {
        Map<String, Object> documentData = new LinkedHashMap<>();
        documentData.put("documentType", "category");
        documentData.put("operation", operation);
        documentData.put("categoryId", category.id());
        EventBridge.Message message = new EventBridge.Message(messageKey, Map.of("name", category.name()),
                withUrl ? "category" : null, withUrl ? category.slug() : null);
        events.publish(new EventBridge.Event(user.integrationId(), category.groupId(), null, eventType,
                documentData, message), acceptLanguage);
    }

    private static OrganizerSummary summary(CategoryRow row) {
        return new OrganizerSummary(row.id(), row.groupId(), row.name(), row.slug(), row.recipeCount());
    }

    private static CategorySummary brief(CategoryRow row) {
        return new CategorySummary(row.id(), row.slug(), row.name());
    }
}
