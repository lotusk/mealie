package io.mealie.backend.user;

import io.mealie.backend.auth.AuthService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserSelfService {

    private final UserSelfRepository users;

    public UserSelfService(UserSelfRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserSelfResponse get(UUID id) {
        return users.findById(id).orElseThrow(AuthService::credentialsException);
    }
}
