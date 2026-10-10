package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PyValidate;
import io.mealie.backend.organizers.CategoryService.CategoryBase;
import io.mealie.backend.organizers.CategoryService.CategoryResponse;
import io.mealie.backend.organizers.CategoryService.CategorySummary;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.recipe.OrganizerSummary;
import io.mealie.backend.web.validation.PyRequestBody;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.ValidationErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/organizers/categories}, migrated from mealie/routes/organizers/controller_categories.py.
 *
 * <p>Parameters are validated by hand, in FastAPI's order, so errors match Python's 422s: the body is parsed first
 * (before authentication), then the user is resolved (401), then path, query and body fields are validated together.
 */
@RestController
@RequestMapping("/api/organizers/categories")
public class CategoryController {

    private static final String SOURCE = "mealie/routes/organizers/controller_categories.py";

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    @PythonEndpoint(file = SOURCE, line = 39, function = "get_all")
    Pagination<OrganizerSummary> getAll(AuthUser user, HttpServletRequest request) {
        ValidationErrors errors = new ValidationErrors();
        PaginationQuery query = PaginationQuery.parse(request, errors);
        errors.throwIfAny();
        return service.list(user, query, PaginationQuery.last(request, "search"));
    }

    @GetMapping("/empty")
    @PythonEndpoint(file = SOURCE, line = 72, function = "get_all_empty")
    List<CategoryBase> getEmpty(AuthUser user) {
        return service.empty(user);
    }

    @PostMapping("/merge")
    @PythonEndpoint(file = SOURCE, line = 77, function = "merge_categories")
    OrganizerSummary merge(PyRequestBody body, AuthUser user) {
        ValidationErrors errors = new ValidationErrors();
        Map<String, Object> fields = errors.bodyObject(body);
        UUID fromId = errors.bodyField(fields, "fromId", "from_id", PyValidate::uuid4);
        UUID toId = errors.bodyField(fields, "toId", "to_id", PyValidate::uuid4);
        errors.throwIfAny();
        return service.merge(user, fromId, toId);
    }

    @GetMapping("/{item_id}")
    @PythonEndpoint(file = SOURCE, line = 92, function = "get_one")
    CategorySummary getOne(AuthUser user, @PathVariable("item_id") String itemId) {
        ValidationErrors errors = new ValidationErrors();
        UUID id = errors.path("item_id", itemId, PyValidate::uuid4);
        errors.throwIfAny();
        return service.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PythonEndpoint(file = SOURCE, line = 51, function = "create_one")
    OrganizerSummary create(PyRequestBody body, AuthUser user, HttpServletRequest request) {
        ValidationErrors errors = new ValidationErrors();
        String name = errors.bodyField(errors.bodyObject(body), "name", "name", PyValidate::str);
        errors.throwIfAny();
        return service.create(user, name, request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }

    @PutMapping("/{item_id}")
    @PythonEndpoint(file = SOURCE, line = 99, function = "update_one")
    CategorySummary update(PyRequestBody body, AuthUser user, @PathVariable("item_id") String itemId,
            HttpServletRequest request) {
        ValidationErrors errors = new ValidationErrors();
        UUID id = errors.path("item_id", itemId, PyValidate::uuid4);
        String name = errors.bodyField(errors.bodyObject(body), "name", "name", PyValidate::str);
        errors.throwIfAny();
        return service.update(user, id, name, request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }

    /** Python's handler returns None, which FastAPI sends as a JSON {@code null}. */
    @DeleteMapping("/{item_id}")
    @PythonEndpoint(file = SOURCE, line = 121, function = "delete_one")
    ResponseEntity<String> delete(AuthUser user, @PathVariable("item_id") String itemId,
            HttpServletRequest request) {
        ValidationErrors errors = new ValidationErrors();
        UUID id = errors.path("item_id", itemId, PyValidate::uuid4);
        errors.throwIfAny();
        service.delete(user, id, request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null");
    }

    @GetMapping("/slug/{category_slug}")
    @PythonEndpoint(file = SOURCE, line = 141, function = "get_one_by_slug")
    CategoryResponse getBySlug(AuthUser user, @PathVariable("category_slug") String slug) {
        return service.getBySlug(user, slug);
    }
}
