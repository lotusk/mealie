package io.mealie.backend.recipe;

import io.mealie.backend.compat.PyDateTime;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.RecipeSummaryMapper;
import io.mealie.backend.persistence.model.RecipeSummaryRow;
import io.mealie.backend.persistence.model.UserHouseholdRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Component;

/**
 * Builds RecipeSummary objects from joined rows the way SQLAlchemy's eager loading does: recipes, and each recipe's
 * categories, tools and tags, keep the order in which they first appear in the rows, without duplicates.
 */
@Component
public class RecipeSummaryAssembler {

    private final RecipeSummaryMapper mapper;
    private final SqlDialect dialect;

    public RecipeSummaryAssembler(RecipeSummaryMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }

    private static final class Builder {
        final RecipeSummaryRow recipe;
        final Map<UUID, RecipeSummaryRow> categories = new LinkedHashMap<>();
        final Map<UUID, RecipeSummaryRow> tools = new LinkedHashMap<>();
        final Map<UUID, RecipeSummaryRow> tags = new LinkedHashMap<>();

        Builder(RecipeSummaryRow recipe) {
            this.recipe = recipe;
        }
    }

    /**
     * {@code tagRecipeCount} gives each nested tag's recipeCount. In Python only an object already loaded with its
     * count expression carries a count (the session's identity map hands back that same object), so the tag being
     * viewed shows its count and every other nested organizer shows 0.
     */
    public List<RecipeSummary> assemble(List<RecipeSummaryRow> rows, ToLongFunction<UUID> tagRecipeCount) {
        Map<UUID, Builder> recipes = new LinkedHashMap<>();
        for (RecipeSummaryRow row : rows) {
            Builder builder = recipes.computeIfAbsent(row.recipeId, id -> new Builder(row));
            if (row.categoryId != null) {
                builder.categories.putIfAbsent(row.categoryId, row);
            }
            if (row.toolId != null) {
                builder.tools.putIfAbsent(row.toolId, row);
            }
            if (row.tagId != null) {
                builder.tags.putIfAbsent(row.tagId, row);
            }
        }

        Map<UUID, UUID> households = userHouseholds(recipes.values());
        Map<UUID, List<String>> toolHouseholds = new HashMap<>();
        List<RecipeSummary> result = new ArrayList<>();
        for (Builder builder : recipes.values()) {
            RecipeSummaryRow r = builder.recipe;
            List<OrganizerSummary> categories = builder.categories.values().stream()
                    .map(c -> new OrganizerSummary(c.categoryId, c.categoryGroupId, c.categoryName, c.categorySlug, 0))
                    .toList();
            List<OrganizerSummary> tags = builder.tags.values().stream()
                    .map(t -> new OrganizerSummary(t.tagId, t.tagGroupId, t.tagName, t.tagSlug,
                            tagRecipeCount.applyAsLong(t.tagId)))
                    .toList();
            List<RecipeToolSummary> tools = builder.tools.values().stream()
                    .map(t -> new RecipeToolSummary(t.toolId, t.toolGroupId, t.toolName, t.toolSlug, 0,
                            toolHouseholds.computeIfAbsent(t.toolId,
                                    id -> mapper.toolHouseholdSlugs(dialect.uuid(id)))))
                    .toList();
            result.add(new RecipeSummary(
                    r.recipeId,
                    r.userId,
                    r.userId == null ? null : households.get(r.userId),
                    r.groupId,
                    r.name,
                    r.slug,
                    r.image,
                    orZero(r.recipeServings),
                    orZero(r.recipeYieldQuantity),
                    r.recipeYield,
                    r.totalTime,
                    r.prepTime,
                    r.cookTime,
                    r.performTime,
                    r.totalTimeSeconds,
                    r.prepTimeSeconds,
                    r.performTimeSeconds,
                    r.description,
                    categories,
                    tags,
                    tools,
                    r.rating,
                    r.orgUrl,
                    PyDateTime.date(r.dateAdded),
                    PyDateTime.pydantic(r.dateUpdated),
                    PyDateTime.pydantic(r.createdAt),
                    PyDateTime.pydantic(r.updateAt),
                    PyDateTime.pydantic(r.lastMade)));
        }
        return result;
    }

    private Map<UUID, UUID> userHouseholds(Iterable<Builder> recipes) {
        List<Object> userIds = new ArrayList<>();
        for (Builder builder : recipes) {
            if (builder.recipe.userId != null) {
                userIds.add(dialect.uuid(builder.recipe.userId));
            }
        }
        Map<UUID, UUID> households = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (UserHouseholdRow row : mapper.userHouseholds(userIds)) {
                households.put(row.userId(), row.householdId());
            }
        }
        return households;
    }

    /** RecipeSummary.clean_numbers: {@code val or 0}. */
    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }
}
