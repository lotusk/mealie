package io.mealie.backend.food;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.ValidationErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FoodController {
    private final FoodService service;

    public FoodController(FoodService service) {
        this.service = service;
    }

    @GetMapping("/api/foods")
    @PythonEndpoint(file = "mealie/routes/unit_and_foods/foods.py", line = 38, function = "get_all")
    Pagination<FoodResponse> getAll(AuthUser user, HttpServletRequest request) {
        ValidationErrors errors = new ValidationErrors();
        PaginationQuery query = PaginationQuery.parse(request, errors);
        errors.throwIfAny();
        return service.list(user, query, PaginationQuery.last(request, "search"));
    }
}
