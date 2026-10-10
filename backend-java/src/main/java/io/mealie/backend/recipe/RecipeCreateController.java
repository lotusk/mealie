package io.mealie.backend.recipe;

import com.auth0.jwt.JWT;
import io.mealie.backend.auth.AuthTokens;
import io.mealie.backend.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
public class RecipeCreateController {
    private final RecipeCreateService recipes;
    private final RecipeCreateValidation validation;
    private final ObjectMapper json;

    public RecipeCreateController(RecipeCreateService recipes, RecipeCreateValidation validation, ObjectMapper json) {
        this.recipes = recipes;
        this.validation = validation;
        this.json = json;
    }

    @PostMapping("/api/recipes")
    public ResponseEntity<String> create(RecipeCreateBody body, AuthUser user, HttpServletRequest request) {
        String name = validation.name(body);
        String token = AuthTokens.extract(request).orElseThrow();
        var claim = JWT.decode(token).getClaim("integration_id");
        String integrationId = claim.isMissing() ? "generic" : claim.asString();
        String slug = recipes.create(name, user, request.getHeader("Accept-Language") == null
                ? "en-US" : request.getHeader("Accept-Language"), integrationId);
        // A JSON string rather than Spring's plain String response.
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(slug));
    }

    @ExceptionHandler(RecipeCreateFailure.class)
    ResponseEntity<Object> failure(RecipeCreateFailure failure) {
        return ResponseEntity.status(failure.status).contentType(MediaType.APPLICATION_JSON).body(failure.body);
    }
}
