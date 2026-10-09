package io.mealie.backend.tags;

import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.TagMapper;
import io.mealie.backend.persistence.model.TagRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class TagRepository {
    private final TagMapper mapper;
    private final SqlDialect dialect;

    public TagRepository(TagMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }

    public boolean canOrganize(UUID userId) { return Boolean.TRUE.equals(mapper.canOrganize(dialect.uuid(userId))); }
    public List<TagRow> list(Map<String, Object> query) { return mapper.list(query); }
    public long count(Map<String, Object> query) { return mapper.count(query); }
    public TagRow find(UUID groupId, UUID id, String slug) {
        return mapper.find(dialect.uuid(groupId), dialect.uuid(id), slug);
    }
    public void save(UUID groupId, UUID id, String name, boolean create) {
        Map<String, Object> values = Map.of("groupId", dialect.uuid(groupId), "id", dialect.uuid(id),
                "name", name, "slug", TagSlug.slugify(name), "now", dialect.timestamp(OffsetDateTime.now()));
        if (create) mapper.insert(values); else mapper.update(values);
    }
    public void delete(UUID groupId, UUID id) {
        mapper.deleteRecipeLinks(dialect.uuid(id));
        mapper.delete(dialect.uuid(groupId), dialect.uuid(id));
    }
    public void merge(UUID groupId, UUID from, UUID to) {
        mapper.moveRecipes(dialect.uuid(from), dialect.uuid(to));
        delete(groupId, from);
    }

    public List<Map<String, Object>> recipes(UUID groupId, UUID tagId, long tagRecipeCount) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : mapper.recipes(dialect.uuid(groupId), dialect.uuid(tagId))) {
            UUID id = uuid(row.get("id"));
            Map<String, Object> recipe = new LinkedHashMap<>();
            for (String key : List.of("id", "user_id", "household_id", "group_id", "name", "slug", "image",
                    "recipe_servings", "recipe_yield_quantity", "recipe_yield", "total_time", "prep_time", "cook_time",
                    "perform_time", "total_time_seconds", "prep_time_seconds", "perform_time_seconds", "description",
                    "rating", "org_url", "date_added", "date_updated", "created_at", "update_at", "last_made")) {
                Object value = row.get(key);
                if (key.endsWith("_id") || key.equals("id")) value = uuid(value);
                if (List.of("date_updated", "created_at", "update_at", "last_made").contains(key) && value != null) {
                    value = timestamp(value);
                }
                if (List.of("recipe_servings", "recipe_yield_quantity").contains(key) && value == null) value = 0;
                if (key.equals("date_added") && value != null) value = value.toString();
                recipe.put(key.equals("org_url") ? "orgURL" : key.equals("update_at") ? "updatedAt" : camel(key), value);
            }
            var tags = organizers(mapper.recipeTags(dialect.uuid(id)), false);
            for (var tag : tags) {
                // SQLAlchemy's identity map reuses the counted parent tag in its recipes' tag collections.
                if (tagId.equals(tag.get("id"))) tag.put("recipeCount", tagRecipeCount);
            }
            recipe.put("tags", tags);
            recipe.put("recipeCategory", organizers(mapper.recipeCategories(dialect.uuid(id)), false));
            recipe.put("tools", organizers(mapper.recipeTools(dialect.uuid(id)), true));
            result.add(recipe);
        }
        return result;
    }

    private List<Map<String, Object>> organizers(List<Map<String, Object>> rows, boolean tools) {
        return rows.stream().map(row -> {
            UUID id = uuid(row.get("id"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("groupId", uuid(row.get("group_id")));
            out.put("name", row.get("name"));
            out.put("slug", row.get("slug"));
            out.put("recipeCount", 0);
            if (tools) out.put("householdsWithTool", mapper.toolHouseholds(dialect.uuid(id)));
            return out;
        }).toList();
    }

    static UUID uuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID id) return id;
        String hex = value.toString().replace("-", "");
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
    }
    static String timestamp(Object value) {
        String text = value.toString().replace(' ', 'T');
        // SQLAlchemy's NaiveDateTime is serialized with an explicit UTC offset.
        return format(java.time.LocalDateTime.parse(text)) + "Z";
    }
    static String format(java.time.LocalDateTime value) {
        return value.format(java.time.format.DateTimeFormatter.ofPattern(value.getNano() == 0
                ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd'T'HH:mm:ss.SSSSSS"));
    }
    static String camel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : value.toCharArray()) {
            if (c == '_') upper = true;
            else { result.append(upper ? Character.toUpperCase(c) : c); upper = false; }
        }
        return result.toString();
    }
}
