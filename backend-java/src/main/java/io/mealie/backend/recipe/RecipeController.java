package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecipeController {
    private final RecipeService recipes;

    public RecipeController(RecipeService recipes) {
        this.recipes = recipes;
    }

    @GetMapping("/api/recipes/{slug}")
    public Map<String, Object> getOne(@PathVariable String slug, AuthUser user, HttpServletResponse response,
            @RequestHeader(value = "Accept-Language", defaultValue = "en-US") String locale) {
        var recipe = recipes.getOne(slug, user, locale).orElseThrow(RecipeNotFoundException::new);
        if (recipe.get("updatedAt") instanceof String modified) {
            // Python uses its ISO JSON timestamp here. Write it directly so Spring does not parse/remove it
            // as an RFC-1123 conditional-request timestamp when processing a ResponseEntity.
            response.setHeader("Last-Modified", modified);
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        }
        return recipe;
    }

    static class RecipeNotFoundException extends RuntimeException { }

    @ExceptionHandler(RecipeNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return error(404, "No Entry Found", null);
    }

    @ExceptionHandler(RecipeService.InvalidRecipeException.class)
    ResponseEntity<Map<String, Object>> invalidRecipe() {
        return error(500, "Unknown Error", "ValidationError");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message, String exception) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("message", message);
        detail.put("error", true);
        detail.put("exception", exception);
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
