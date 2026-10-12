package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.persistence.model.UserRatingWriteRow;
import org.apache.ibatis.annotations.Param;

public interface UserRatingMapper {
    IdRow findRecipe(@Param("groupId") Object groupId, @Param("id") Object id, @Param("slug") String slug);
    UserRatingWriteRow findRating(@Param("userId") Object userId, @Param("recipeId") Object recipeId);
    int insert(@Param("id") Object id, @Param("userId") Object userId, @Param("recipeId") Object recipeId,
            @Param("rating") Double rating, @Param("favorite") Object favorite, @Param("now") Object now);
    int update(@Param("id") Object id, @Param("userId") Object userId, @Param("recipeId") Object recipeId,
            @Param("rating") Double rating, @Param("favorite") Object favorite, @Param("now") Object now);
    int recalculate(@Param("recipeId") Object recipeId, @Param("groupId") Object groupId, @Param("now") Object now);
}
