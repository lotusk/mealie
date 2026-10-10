package io.mealie.backend.user;

import io.mealie.backend.auth.AuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserSelfController {

    private final UserSelfService service;

    public UserSelfController(UserSelfService service) {
        this.service = service;
    }

    @GetMapping("/api/users/self")
    UserSelfResponse get(AuthUser user) {
        return service.get(user.id());
    }
}
