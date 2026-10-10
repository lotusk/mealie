package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.persistence.model.RecipeSummaryRow;
import io.mealie.backend.persistence.model.TagRow;
import io.mealie.backend.query.SqlFragment;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TagMapper {

    long count(@Param("where") SqlFragment where);

    List<TagRow> page(@Param("where") SqlFragment where, @Param("order") SqlFragment order,
            @Param("limit") Long limit, @Param("offset") long offset, @Param("sqlite") boolean sqlite);

    List<IdRow> ids(@Param("where") SqlFragment where, @Param("order") SqlFragment order);

    TagRow findById(@Param("groupId") Object groupId, @Param("id") Object id);

    TagRow findBySlug(@Param("groupId") Object groupId, @Param("slug") String slug);

    List<TagRow> findEmpty(@Param("groupId") Object groupId);

    int insert(@Param("id") Object id, @Param("groupId") Object groupId, @Param("name") String name,
            @Param("slug") String slug, @Param("createdAt") Object createdAt, @Param("updateAt") Object updateAt);

    int update(@Param("id") Object id, @Param("groupId") Object groupId, @Param("name") String name,
            @Param("slug") String slug, @Param("updateAt") Object updateAt);

    /** The association rows SQLAlchemy removes when a tag is deleted: those whose recipe exists. */
    int deleteRecipeLinks(@Param("tagId") Object tagId);

    int delete(@Param("id") Object id);

    int moveRecipeLinks(@Param("fromId") Object fromId, @Param("toId") Object toId);

    int deleteAllRecipeLinks(@Param("tagId") Object tagId);

    List<RecipeSummaryRow> recipeRows(@Param("tagId") Object tagId);
}
