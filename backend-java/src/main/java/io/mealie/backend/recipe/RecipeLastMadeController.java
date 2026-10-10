package io.mealie.backend.recipe;

import com.auth0.jwt.JWT;
import io.mealie.backend.auth.AuthTokens;
import io.mealie.backend.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecipeLastMadeController {
    private final RecipeLastMadeService recipes;
    private final RecipeLastMadeValidation validation;
    public RecipeLastMadeController(RecipeLastMadeService recipes, RecipeLastMadeValidation validation) {
        this.recipes = recipes;
        this.validation = validation;
    }
    @PatchMapping("/api/recipes/{slug}/last-made")
    public Map<String, Object> update(@PathVariable String slug, RecipeLastMadeBody body, AuthUser user, HttpServletRequest request) {
        var timestamp = validation.timestamp(body);
        var claim = JWT.decode(AuthTokens.extract(request).orElseThrow()).getClaim("integration_id");
        String integration = claim.isMissing() ? "generic" : claim.asString();
        return recipes.update(slug, timestamp, user, request.getHeader("Accept-Language") == null
                ? "en-US" : request.getHeader("Accept-Language"), integration);
    }
    @ExceptionHandler(RecipeCreateFailure.class)
    ResponseEntity<Object> failure(RecipeCreateFailure failure) {
        return ResponseEntity.status(failure.status).contentType(MediaType.APPLICATION_JSON).body(failure.body);
    }
    @ExceptionHandler(RecipeService.InvalidRecipeException.class)
    ResponseEntity<Object> invalidRecipe() { return failure(RecipeCreateFailure.error(500, "Unknown Error", "ValidationError")); }
}
