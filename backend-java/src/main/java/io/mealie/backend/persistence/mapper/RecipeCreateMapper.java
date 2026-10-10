package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.RecipeCreationContextRow;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface RecipeCreateMapper {
    RecipeCreationContextRow context(@Param("userId") Object userId, @Param("groupId") Object groupId,
            @Param("householdId") Object householdId);
    int insertRecipe(Map<String, Object> values);
    int insertIngredient(Map<String, Object> values);
    int insertInstruction(Map<String, Object> values);
    int insertNutrition(Map<String, Object> values);
    int insertSettings(Map<String, Object> values);
    int insertTimeline(Map<String, Object> values);
}
