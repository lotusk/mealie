package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.RecipeLastMadeRow;
import java.util.Map;

public interface RecipeLastMadeMapper {
    RecipeLastMadeRow find(Map<String, Object> values);
    int setHousehold(Map<String, Object> values);
    int advanceRecipe(Map<String, Object> values);
}
