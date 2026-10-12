package io.mealie.backend.user;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PyValidate;
import io.mealie.backend.web.validation.PyRequestBody;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.ValidationErrors;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserRatingController {
    private final UserRatingService service;

    public UserRatingController(UserRatingService service) {
        this.service = service;
    }

    @PostMapping("/api/users/{id}/ratings/{slug}")
    @PythonEndpoint(file = "mealie/routes/users/ratings.py", line = 56, function = "set_rating")
    ResponseEntity<String> setRating(PyRequestBody body, AuthUser user,
            @PathVariable("id") String id, @PathVariable("slug") String slug) {
        ValidationErrors errors = new ValidationErrors();
        UUID userId = errors.path("id", id, PyValidate::uuid4);
        UserRatingUpdate data = UserRatingUpdate.parse(body, errors);
        errors.throwIfAny();
        service.set(user, userId, slug, data);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null");
    }
}
