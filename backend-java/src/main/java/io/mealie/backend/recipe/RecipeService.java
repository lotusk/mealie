package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipeService {
    private final RecipeRepository recipes;

    public RecipeService(RecipeRepository recipes) {
        this.recipes = recipes;
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getOne(String slugOrId, AuthUser user, String locale) {
        return recipes.find(slugOrId, user.groupId(), locale);
    }

    static class InvalidRecipeException extends RuntimeException { }
}
