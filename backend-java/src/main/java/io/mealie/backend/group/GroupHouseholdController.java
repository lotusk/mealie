package io.mealie.backend.group;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.ValidationErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupHouseholdController {

    private final GroupHouseholdService service;

    public GroupHouseholdController(GroupHouseholdService service) {
        this.service = service;
    }

    @GetMapping("/api/groups/households")
    @PythonEndpoint(file = "mealie/routes/groups/controller_group_households.py", line = 14,
            function = "get_all_households")
    Pagination<GroupHouseholdResponse> getAll(AuthUser user, HttpServletRequest request) {
        ValidationErrors errors = new ValidationErrors();
        PaginationQuery query = PaginationQuery.parse(request, errors);
        errors.throwIfAny();
        return service.list(user, query);
    }

    @GetMapping("/api/groups/households/{household_slug}")
    @PythonEndpoint(file = "mealie/routes/groups/controller_group_households.py", line = 21,
            function = "get_one_household")
    GroupHouseholdResponse getOne(AuthUser user, @PathVariable("household_slug") String slugOrId) {
        return service.get(user, slugOrId);
    }
}
