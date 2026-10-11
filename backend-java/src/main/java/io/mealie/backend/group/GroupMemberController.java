package io.mealie.backend.group;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.ValidationErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupMemberController {

    private final GroupMemberService service;

    public GroupMemberController(GroupMemberService service) {
        this.service = service;
    }

    @GetMapping("/api/groups/members")
    @PythonEndpoint(file = "mealie/routes/groups/controller_group_self_service.py", line = 30,
            function = "get_group_members")
    Pagination<GroupMemberResponse> getAll(AuthUser user, HttpServletRequest request) {
        ValidationErrors errors = new ValidationErrors();
        PaginationQuery query = PaginationQuery.parse(request, errors);
        errors.throwIfAny();
        return service.list(user, query);
    }
}
