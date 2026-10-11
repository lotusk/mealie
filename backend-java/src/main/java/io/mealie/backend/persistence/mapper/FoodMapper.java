package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.FoodRow;
import io.mealie.backend.persistence.model.FoodRows;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.query.SqlFragment;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FoodMapper {
    long count(@Param("where") SqlFragment where);
    List<FoodRow> page(@Param("where") SqlFragment where, @Param("order") SqlFragment order,
            @Param("limit") Long limit, @Param("offset") long offset, @Param("sqlite") boolean sqlite);
    List<IdRow> ids(@Param("where") SqlFragment where, @Param("order") SqlFragment order);
    List<FoodRows.NameRow> aliases(@Param("ids") List<Object> ids);
    List<FoodRows.NameRow> households(@Param("ids") List<Object> ids);
    List<FoodRows.ExtraRow> extras(@Param("ids") List<Object> ids);
    List<FoodRows.SubstitutionRow> substitutions(@Param("ids") List<Object> ids);
}
