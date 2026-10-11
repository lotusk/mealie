package io.mealie.backend.recipe;
import com.auth0.jwt.JWT;
import io.mealie.backend.auth.AuthTokens;
import io.mealie.backend.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class RecipeDuplicateController {
    private final RecipeDuplicateService recipes;
    private final RecipeDuplicateValidation validation;
    public RecipeDuplicateController(RecipeDuplicateService recipes, RecipeDuplicateValidation validation) {
        this.recipes = recipes; this.validation = validation;
    }
    @PostMapping("/api/recipes/{slug}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicate(@PathVariable String slug, RecipeDuplicateBody body,
            AuthUser user, HttpServletRequest request) {
        String name = validation.name(body);
        var claim = JWT.decode(AuthTokens.extract(request).orElseThrow()).getClaim("integration_id");
        String integration = claim.isMissing() ? "generic" : claim.asString();
        return ResponseEntity.status(201).body(recipes.duplicate(slug, name, user,
                request.getHeader("Accept-Language") == null ? "en-US" : request.getHeader("Accept-Language"), integration));
    }
    @ExceptionHandler(RecipeCreateFailure.class)
    ResponseEntity<Object> failure(RecipeCreateFailure failure) {
        return ResponseEntity.status(failure.status).contentType(MediaType.APPLICATION_JSON).body(failure.body);
    }
    @ExceptionHandler(RecipeDuplicateService.NotificationMetadataException.class)
    ResponseEntity<String> invalidEventMetadata() {
        // This exception occurs outside Python's duplication try/except, after its database and file copy.
        return ResponseEntity.status(500).header("Content-Type", "text/plain; charset=utf-8").body("Internal Server Error");
    }
    @ExceptionHandler(RecipeService.InvalidRecipeException.class)
    ResponseEntity<Object> invalidRecipe() { return failure(RecipeCreateFailure.error(500, "Unknown Error", "ValidationError")); }
}
