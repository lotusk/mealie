package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecipeListController {
    private final RecipeListService recipes;
    public RecipeListController(RecipeListService recipes) { this.recipes = recipes; }

    @GetMapping("/api/recipes")
    public Map<String, Object> getAll(AuthUser user, HttpServletRequest request) {
        return recipes.getAll(RecipeListQuery.from(request), user);
    }

    @ExceptionHandler(RecipeListQuery.Failure.class)
    ResponseEntity<?> failure(RecipeListQuery.Failure failure) {
        return ResponseEntity.status(failure.status).contentType(failure.body instanceof String ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON).body(failure.body);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<String> databaseFailure() {
        return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN).body("Internal Server Error");
    }
}
