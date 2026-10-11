package io.mealie.backend.persistence.mapper;
import java.util.Map;
public interface RecipeDuplicateMapper {
    int insertRecipe(Map<String, Object> values);
    int copyOrganizers(Map<String, Object> values);
    int copyCategories(Map<String, Object> values);
    int copyTools(Map<String, Object> values);
    int insertNutrition(Map<String, Object> values);
    int insertSettings(Map<String, Object> values);
    int insertInstruction(Map<String, Object> values);
    int insertIngredientReference(Map<String, Object> values);
    int insertNoteReference(Map<String, Object> values);
    int insertSubstitution(Map<String, Object> values);
    int insertNote(Map<String, Object> values);
    int insertAsset(Map<String, Object> values);
    int insertExtra(Map<String, Object> values);
    long insertIngredient(Map<String, Object> values);
}
