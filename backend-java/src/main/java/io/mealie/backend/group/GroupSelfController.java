package io.mealie.backend.group;

import io.mealie.backend.auth.AuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupSelfController {

    private final GroupSelfService service;

    public GroupSelfController(GroupSelfService service) {
        this.service = service;
    }

    @GetMapping("/api/groups/self")
    GroupSelfResponse get(AuthUser user) {
        return service.get(user.groupId());
    }
}
