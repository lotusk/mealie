package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.CategoryRow;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.query.SqlFragment;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CategoryMapper {
    long count(@Param("where") SqlFragment where);
    List<CategoryRow> page(@Param("where") SqlFragment where, @Param("order") SqlFragment order,
            @Param("limit") Long limit, @Param("offset") long offset, @Param("sqlite") boolean sqlite);
    List<IdRow> ids(@Param("where") SqlFragment where, @Param("order") SqlFragment order);
    CategoryRow findById(@Param("groupId") Object groupId, @Param("id") Object id);
    CategoryRow findBySlug(@Param("groupId") Object groupId, @Param("slug") String slug);
    List<CategoryRow> findEmpty(@Param("groupId") Object groupId);
    int insert(@Param("id") Object id, @Param("groupId") Object groupId, @Param("name") String name,
            @Param("slug") String slug, @Param("createdAt") Object createdAt, @Param("updateAt") Object updateAt);
    int update(@Param("id") Object id, @Param("groupId") Object groupId, @Param("name") String name,
            @Param("slug") String slug, @Param("updateAt") Object updateAt);
    int deleteRecipeLinks(@Param("categoryId") Object categoryId);
    int delete(@Param("id") Object id);
    int moveRecipeLinks(@Param("fromId") Object fromId, @Param("toId") Object toId);
    int deleteAllRecipeLinks(@Param("categoryId") Object categoryId);
    List<IdRow> recipeIds(@Param("groupId") Object groupId, @Param("categoryId") Object categoryId);
}
