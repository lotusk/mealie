package io.mealie.backend.recipe;
import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.RecipeDuplicateMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
@Repository
public class RecipeDuplicateRepository {
    private final RecipeDuplicateMapper mapper;
    private final SqlDialect dialect;
    private final RecipeCreationLocale locales;
    public RecipeDuplicateRepository(RecipeDuplicateMapper mapper, SqlDialect dialect, RecipeCreationLocale locales) {
        this.mapper = mapper; this.dialect = dialect; this.locales = locales;
    }
    void insert(Map<String, Object> source, UUID id, String name, String slug, AuthUser user, String locale) {
        Object now = dialect.timestamp(OffsetDateTime.now(ZoneOffset.UTC));
        var row = values(source, id, now);
        row.put("sourceId", uuid(source.get("id")));
        row.put("userId", dialect.uuid(user.id())); row.put("groupId", dialect.uuid(user.groupId()));
        row.put("name", name); row.put("slug", slug); row.put("nameNormalized", RecipeSlug.normalize(name));
        row.put("descriptionNormalized", source.get("description") == null ? null : RecipeSlug.normalize((String) source.get("description")));
        row.put("createdAt", timestamp(source.get("createdAt"), now));
        row.put("dateAdded", source.get("dateAdded") == null ? dialect.date(LocalDate.now(ZoneOffset.UTC)) : dialect.date(LocalDate.parse((String) source.get("dateAdded"))));
        row.put("image", truthy(source.get("image")) ? cacheKey() : null);
        row.put("no", dialect.bool(false)); row.put("yes", dialect.bool(true));
        mapper.insertRecipe(row);
        mapper.copyOrganizers(row);
        mapper.copyCategories(row);
        mapper.copyTools(row);
        mapper.insertNutrition(values(object(source.get("nutrition")), id, now));
        var settings = object(source.get("settings"));
        var config = values(settings, id, now);
        for (String key : List.of("public", "showNutrition", "showAssets", "landscapeView"))
            config.put(key, dialect.bool(settings.isEmpty() || Boolean.TRUE.equals(settings.get(key))));
        for (String key : List.of("disableComments", "locked")) config.put(key, dialect.bool(Boolean.TRUE.equals(settings.get(key))));
        config.put("yes", dialect.bool(true)); mapper.insertSettings(config);
        var ingredients = items(source.get("recipeIngredient"));
        if (ingredients.isEmpty()) ingredients = List.of(Map.of("quantity", 0, "note", locales.defaults(locale).ingredientNote()));
        int position = 0;
        for (var ingredient : ingredients) {
            var value = values(ingredient, id, now);
            value.put("position", position++); value.put("referenceId", dialect.uuid(UUID.randomUUID()));
            value.put("unitId", uuid(object(ingredient.get("unit")).get("id")));
            value.put("foodId", uuid(object(ingredient.get("food")).get("id")));
            value.put("referencedRecipeId", uuid(object(ingredient.get("referencedRecipe")).get("id")));
            value.put("noteNormalized", ingredient.get("note") == null ? null : RecipeSlug.normalize((String) ingredient.get("note")));
            value.put("originalTextNormalized", ingredient.get("originalText") == null ? null : RecipeSlug.normalize((String) ingredient.get("originalText")));
            long ingredientId = mapper.insertIngredient(value);
            int subPosition = 0;
            for (var substitution : items(ingredient.get("substitutions"))) {
                var sub = values(substitution, id, now);
                sub.put("id", dialect.uuid(UUID.randomUUID())); sub.put("ingredientId", ingredientId);
                sub.put("substituteFoodId", uuid(substitution.get("substituteFoodId")));
                sub.put("position", subPosition++); sub.put("groupId", dialect.uuid(user.groupId()));
                mapper.insertSubstitution(sub);
            }
        }
        var instructions = items(source.get("recipeInstructions"));
        if (instructions.isEmpty()) instructions = List.of(Map.of("title", "", "summary", "", "text", locales.defaults(locale).instructionText()));
        position = 0;
        for (var step : instructions) {
            var value = values(step, id, now);
            Object instructionId = dialect.uuid(UUID.randomUUID()); value.put("id", instructionId); value.put("position", position++);
            value.put("text", ((String) step.get("text")).replace((String) source.get("id"), id.toString()));
            mapper.insertInstruction(value);
            // Python regenerates ingredient reference IDs but preserves step reference values; retain that legacy behavior.
            for (var reference : items(step.get("ingredientReferences"))) mapper.insertIngredientReference(reference(reference, instructionId, now));
            for (var reference : items(step.get("noteReferences"))) mapper.insertNoteReference(reference(reference, instructionId, now));
        }
        for (var note : items(source.get("notes"))) {
            var value = values(note, id, now); value.put("referenceId", uuid(note.get("referenceId"))); mapper.insertNote(value);
        }
        for (var asset : items(source.get("assets"))) mapper.insertAsset(values(asset, id, now));
        for (var extra : object(source.get("extras")).entrySet()) {
            var value = values(Map.of(), id, now); value.put("key", extra.getKey()); value.put("value", extra.getValue()); mapper.insertExtra(value);
        }
        // Duplication does not call create_one: comments, timeline, ratings/favorites, last-made links and shares are not copied.
    }
    private Map<String, Object> reference(Map<String, Object> input, Object instructionId, Object now) {
        var value = new LinkedHashMap<String, Object>(); value.put("instructionId", instructionId);
        value.put("referenceId", uuid(input.get("referenceId"))); value.put("timestamp", now); return value;
    }
    private Map<String, Object> values(Map<String, Object> input, UUID id, Object now) {
        var value = new LinkedHashMap<>(input); value.put("recipeId", dialect.uuid(id)); value.put("timestamp", now); return value;
    }
    private Object uuid(Object value) { return value == null ? null : dialect.uuid(UUID.fromString(value.toString())); }
    private Object timestamp(Object value, Object fallback) { return value == null ? fallback : dialect.timestamp(OffsetDateTime.parse(value.toString())); }
    private static boolean truthy(Object value) { return value != null && !value.equals("") && !value.equals(false) && !value.equals(0); }
    private static String cacheKey() {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        var random = java.util.concurrent.ThreadLocalRandom.current(); var result = new StringBuilder();
        for (int i = 0; i < 4; i++) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return result.toString();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> items(Object value) { return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of(); }
}
