package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PyValidate;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.recipe.RecipeToolSummary;
import io.mealie.backend.web.validation.PyRequestBody;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.ValidationErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ToolController {

    private final ToolService service;

    public ToolController(ToolService service) {
        this.service = service;
    }

    @PostMapping("/api/organizers/tools")
    @ResponseStatus(HttpStatus.CREATED)
    @PythonEndpoint(file = "mealie/routes/organizers/controller_tools.py", line = 44, function = "create_one")
    RecipeToolSummary create(PyRequestBody body, AuthUser user) {
        ValidationErrors errors = new ValidationErrors();
        Map<String, Object> fields = errors.bodyObject(body);
        String name = errors.bodyField(fields, "name", "name", PyValidate::str);
        List<String> households = new ArrayList<>();
        if (fields != null) {
            String key = fields.containsKey("householdsWithTool") ? "householdsWithTool" : "households_with_tool";
            if (fields.containsKey(key)) {
                List<?> values = errors.check(List.of("body", key), fields.get(key), value -> {
                    if (value instanceof List<?> list) {
                        return list;
                    }
                    throw new PyValidate.Invalid("list_type", "Input should be a valid list", null);
                });
                if (values != null) {
                    for (int i = 0; i < values.size(); i++) {
                        households.add(errors.check(List.of("body", key, i), values.get(i), PyValidate::str));
                    }
                }
            }
        }
        errors.throwIfAny();
        return service.create(user, name, households);
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
