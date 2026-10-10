package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PyDateTime;
import io.mealie.backend.compat.PyRepr;
import io.mealie.backend.compat.PySlugify;
import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.IntegrityViolation;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.events.EventBridge;
import io.mealie.backend.persistence.model.TagRow;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.recipe.OrganizerSummary;
import io.mealie.backend.recipe.RecipeSummary;
import io.mealie.backend.recipe.RecipeSummaryAssembler;
import io.mealie.backend.web.ApiException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tag operations of TagController (mealie/routes/organizers/controller_tags.py): permission checks, the
 * Python-compatible error responses, and the tag_created/updated/deleted events.
 */
@Service
public class TagService {

    /** router.url_path_for("get_all"): next/previous links are relative to the tags router. */
    static final String LIST_ROUTE = "/tags";

    /** A tag with the recipes that carry it: RecipeTagResponse. */
    public record TagResponse(String name, UUID id, UUID groupId, String slug, List<RecipeSummary> recipes) {
    }

    private final TagRepository repository;
    private final RecipeSummaryAssembler recipeSummaries;
    private final EventBridge events;
    private final TransactionTemplate transaction;
    private final SqlDialect dialect;

    public TagService(TagRepository repository, RecipeSummaryAssembler recipeSummaries, EventBridge events,
            TransactionTemplate transaction, SqlDialect dialect) {
        this.repository = repository;
        this.recipeSummaries = recipeSummaries;
        this.events = events;
        this.transaction = transaction;
        this.dialect = dialect;
    }

    public Pagination<OrganizerSummary> list(AuthUser user, PaginationQuery query, String search) {
        return repository.page(user.groupId(), query, search, LIST_ROUTE, TagService::summary);
    }

    /**
     * get_empty() returns ORM objects without a response model, so FastAPI serializes their attributes as they are:
     * snake_case keys and isoformat() datetimes.
     */
    public List<Map<String, Object>> empty(AuthUser user) {
        return repository.findEmpty(user.groupId()).stream().map(row -> {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("recipe_count", 0);
            tag.put("group_id", row.groupId());
            tag.put("slug", row.slug());
            tag.put("update_at", PyDateTime.isoformat(row.updateAt()));
            tag.put("id", row.id());
            tag.put("name", row.name());
            tag.put("created_at", PyDateTime.isoformat(row.createdAt()));
            return tag;
        }).toList();
    }

    /** get_one() returns a TagOut through the RecipeTagResponse model, so the recipes list is always empty. */
    public TagResponse get(AuthUser user, UUID id) {
        TagRow tag = repository.findById(user.groupId(), id)
                .orElseThrow(() -> ApiException.errorResponse(HttpStatus.NOT_FOUND, "Not found.", null));
        return response(tag, List.of());
    }

    /** Python returns None for an unknown slug, which fails response validation: a plain 500. */
    public TagResponse getBySlug(AuthUser user, String slug) {
        TagRow tag = repository.findBySlug(user.groupId(), slug)
                .orElseThrow(() -> new IllegalStateException("No tag with slug " + slug));
        List<RecipeSummary> recipes = recipeSummaries.assemble(repository.recipeRows(tag.id()),
                tagId -> tagId.equals(tag.id()) ? tag.recipeCount() : 0);
        return response(tag, recipes);
    }

    public OrganizerSummary create(AuthUser user, String rawName, String acceptLanguage) {
        requireOrganizer(user);
        String name = PyStr.strip(rawName);
        if (name.isEmpty()) {
            // Tag.validate_name asserts the name isn't empty; HttpRepo reports the AssertionError as a 400.
            throw ApiException.errorResponse(HttpStatus.BAD_REQUEST, "An unexpected error occurred.", "");
        }
        String slug = PySlugify.slugify(name);
        TagRow tag;
        try {
            tag = transaction.execute(status -> repository.insert(user.groupId(), name, slug));
        } catch (RuntimeException e) {
            throw createError(e, user, name, slug);
        }
        publish(user, "tag_created", "create", tag, "notifications.generic-created-with-url", true,
                acceptLanguage);
        return summary(tag);
    }

    /** HttpRepo.handle_exception(): 409 for a constraint violation, 400 for anything else. */
    private RuntimeException createError(RuntimeException error, AuthUser user, String name, String slug) {
        Optional<IntegrityViolation> violation = IntegrityViolation.of(error);
        if (violation.isEmpty()) {
            return ApiException.errorResponse(HttpStatus.BAD_REQUEST, "An unexpected error occurred.",
                    String.valueOf(error.getMessage()));
        }
        String message = violation.get().unique() ? "This item already exists." : "An unexpected error occurred.";
        return ApiException.errorResponse(HttpStatus.CONFLICT, message,
                violation.get().describe(insertStatement(), insertParameters(user, name, slug)));
    }

    private String insertStatement() {
        return dialect.engine() == DbEngine.POSTGRES
                ? "INSERT INTO tags (id, group_id, name, slug, created_at, update_at) VALUES (%(id)s::UUID, "
                        + "%(group_id)s::UUID, %(name)s, %(slug)s, %(created_at)s, %(update_at)s)"
                : "INSERT INTO tags (id, group_id, name, slug, created_at, update_at) VALUES (?, ?, ?, ?, ?, ?)";
    }

    /** The parameters as SQLAlchemy prints them; the id and timestamps are those of the failed attempt. */
    private String insertParameters(AuthUser user, String name, String slug) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (dialect.engine() == DbEngine.POSTGRES) {
            String datetime = "datetime.datetime(" + now.getYear() + ", " + now.getMonthValue() + ", "
                    + now.getDayOfMonth() + ", " + now.getHour() + ", " + now.getMinute() + ", " + now.getSecond()
                    + ", " + now.getNano() / 1000 + ")";
            return "{'id': '" + UUID.randomUUID() + "', 'group_id': '" + user.groupId() + "', 'name': "
                    + PyRepr.repr(name) + ", 'slug': " + PyRepr.repr(slug) + ", 'created_at': " + datetime
                    + ", 'update_at': " + datetime + "}";
        }
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").format(now);
        return "(" + PyRepr.repr(dialect.uuid(UUID.randomUUID())) + ", " + PyRepr.repr(dialect.uuid(user.groupId()))
                + ", " + PyRepr.repr(name) + ", " + PyRepr.repr(slug) + ", '" + timestamp + "', '" + timestamp + "')";
    }

    /**
     * update_one() calls the repository directly, without HttpRepo's error handling, so an unknown id, an empty name
     * or a slug clash are unhandled exceptions: a plain 500.
     */
    public TagResponse update(AuthUser user, UUID id, String rawName, String acceptLanguage) {
        requireOrganizer(user);
        TagRow updated = transaction.execute(status -> {
            TagRow tag = repository.findById(user.groupId(), id)
                    .orElseThrow(() -> new IllegalStateException("No row was found when one was required"));
            String name = PyStr.strip(rawName);
            if (name.isEmpty()) {
                throw new IllegalStateException("Tag name must not be empty");
            }
            String slug = PySlugify.slugify(name);
            if (!name.equals(tag.name()) || !slug.equals(tag.slug()) || !user.groupId().equals(tag.groupId())) {
                repository.update(id, user.groupId(), name, slug);
            }
            return new TagRow(tag.id(), user.groupId(), name, slug, tag.createdAt(), tag.updateAt(),
                    tag.recipeCount());
        });
        publish(user, "tag_updated", "update", updated, "notifications.generic-updated-with-url", true,
                acceptLanguage);
        return response(updated, List.of());
    }

    /** delete_recipe_tag(): any failure, including an unknown id, is a bare 400. */
    public void delete(AuthUser user, UUID id, String acceptLanguage) {
        requireOrganizer(user);
        TagRow deleted;
        try {
            deleted = transaction.execute(status -> {
                TagRow tag = repository.findById(user.groupId(), id)
                        .orElseThrow(() -> new IllegalStateException("No row was found when one was required"));
                repository.delete(id);
                return tag;
            });
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST);
        }
        publish(user, "tag_deleted", "delete", deleted, "notifications.generic-deleted", false, acceptLanguage);
    }

    /** merge_tags(): no event; failures after the checks are unhandled (500). */
    public OrganizerSummary merge(AuthUser user, UUID fromId, UUID toId) {
        requireOrganizer(user);
        if (fromId.equals(toId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "from_id and to_id must be different", Map.of());
        }
        if (repository.findById(user.groupId(), fromId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "from_id tag not found", Map.of());
        }
        if (repository.findById(user.groupId(), toId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "to_id tag not found", Map.of());
        }
        return transaction.execute(status -> {
            repository.merge(fromId, toId);
            return summary(repository.findById(user.groupId(), toId)
                    .orElseThrow(() -> new IllegalStateException("Merged tag disappeared")));
        });
    }

    /** OperationChecks.can_organize(). */
    private static void requireOrganizer(AuthUser user) {
        if (!user.canOrganize()) {
            throw new ApiException(HttpStatus.FORBIDDEN);
        }
    }

    private void publish(AuthUser user, String eventType, String operation, TagRow tag, String messageKey,
            boolean withUrl, String acceptLanguage) {
        Map<String, Object> documentData = new LinkedHashMap<>();
        documentData.put("documentType", "tag");
        documentData.put("operation", operation);
        documentData.put("tagId", tag.id());
        EventBridge.Message message = new EventBridge.Message(messageKey, Map.of("name", tag.name()),
                withUrl ? "tag" : null, withUrl ? tag.slug() : null);
        events.publish(new EventBridge.Event(user.integrationId(), tag.groupId(), null, eventType, documentData,
                message), acceptLanguage);
    }

    private static OrganizerSummary summary(TagRow row) {
        return new OrganizerSummary(row.id(), row.groupId(), row.name(), row.slug(), row.recipeCount());
    }

    private static TagResponse response(TagRow row, List<RecipeSummary> recipes) {
        return new TagResponse(row.name(), row.id(), row.groupId(), row.slug(), recipes);
    }
}
