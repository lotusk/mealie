package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.ShoppingListExtraRow;
import io.mealie.backend.persistence.model.ShoppingListLabelSettingRow;
import io.mealie.backend.persistence.model.ShoppingListRecipeRefRow;
import io.mealie.backend.persistence.model.ShoppingListRow;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.query.SqlFragment;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ShoppingListMapper {
    long count(@Param("where") SqlFragment where);

    List<ShoppingListRow> page(@Param("where") SqlFragment where, @Param("order") SqlFragment order,
            @Param("limit") Long limit, @Param("offset") long offset, @Param("sqlite") boolean sqlite);

    List<IdRow> ids(@Param("where") SqlFragment where, @Param("order") SqlFragment order);

    List<ShoppingListExtraRow> extras(@Param("ids") List<Object> ids);

    List<ShoppingListRecipeRefRow> recipeReferences(@Param("ids") List<Object> ids);

    List<ShoppingListLabelSettingRow> labelSettings(@Param("ids") List<Object> ids);
}
