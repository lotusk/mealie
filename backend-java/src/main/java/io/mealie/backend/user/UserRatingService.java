package io.mealie.backend.user;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRatingService {
    private final UserRatingRepository repository;

    public UserRatingService(UserRatingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void set(AuthUser user, UUID userId, String slugOrId, UserRatingUpdate data) {
        if (!user.id().equals(userId)) {
            throw ApiException.errorResponse(HttpStatus.FORBIDDEN, user.admin()
                    ? "Use the Admin API to update other users" : "User cannot edit other users", null);
        }
        UUID recipeId = repository.findRecipe(user.groupId(), slugOrId)
                .orElseThrow(() -> ApiException.errorResponse(HttpStatus.NOT_FOUND, "Not found.", null));
        repository.save(userId, recipeId, user.groupId(), data);
    }
}
