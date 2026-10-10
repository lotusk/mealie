package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.recipe.RecipeToolSummary;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.ValidationErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ToolController {

    private final ToolService service;

    public ToolController(ToolService service) {
        this.service = service;
    }

    @GetMapping("/api/organizers/tools")
    @PythonEndpoint(file = "mealie/routes/organizers/controller_tools.py", line = 33, function = "get_all")
    Pagination<RecipeToolSummary> getAll(AuthUser user, HttpServletRequest request) {
        ValidationErrors errors = new ValidationErrors();
        PaginationQuery query = PaginationQuery.parse(request, errors);
        errors.throwIfAny();
        return service.list(user, query, PaginationQuery.last(request, "search"));
    }
}
