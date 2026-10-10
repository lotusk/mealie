package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipeListService {
    private final RecipeListRepository listing;
    private final RecipeRepository recipes;
    public RecipeListService(RecipeListRepository listing, RecipeRepository recipes) { this.listing = listing; this.recipes = recipes; }

    @Transactional(readOnly = true)
    Map<String, Object> getAll(RecipeListQuery query, AuthUser user) {
        var selected = listing.select(query, user);
        var result = new LinkedHashMap<String, Object>();
        result.put("page", selected.page()); result.put("per_page", selected.perPage()); result.put("total", selected.total());
        result.put("total_pages", selected.totalPages()); result.put("items", recipes.summaries(selected.ids(), user.groupId()));
        result.put("next", selected.page().compareTo(BigInteger.valueOf(selected.totalPages())) < 0 ? query.guide(selected.page().add(BigInteger.ONE)) : null);
        result.put("previous", selected.page().compareTo(BigInteger.ONE) > 0 ? query.guide(selected.page().subtract(BigInteger.ONE)) : null);
        return result;
    }
}
