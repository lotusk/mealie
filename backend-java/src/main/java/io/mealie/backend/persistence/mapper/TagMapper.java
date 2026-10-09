package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.TagRow;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface TagMapper {
    List<TagRow> list(Map<String, Object> query);
    long count(Map<String, Object> query);
    TagRow find(@Param("groupId") Object groupId, @Param("id") Object id, @Param("slug") String slug);
    Boolean canOrganize(@Param("userId") Object userId);
    int insert(Map<String, Object> values);
    int update(Map<String, Object> values);
    int delete(@Param("groupId") Object groupId, @Param("id") Object id);
    int deleteRecipeLinks(@Param("id") Object id);
    int moveRecipes(@Param("fromId") Object fromId, @Param("toId") Object toId);
    List<Map<String, Object>> recipes(@Param("groupId") Object groupId, @Param("id") Object id);
    List<Map<String, Object>> recipeTags(@Param("id") Object id);
    List<Map<String, Object>> recipeCategories(@Param("id") Object id);
    List<Map<String, Object>> recipeTools(@Param("id") Object id);
    List<String> toolHouseholds(@Param("id") Object id);
}
