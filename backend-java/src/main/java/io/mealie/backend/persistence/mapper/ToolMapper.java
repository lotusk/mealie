package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.persistence.model.ToolHouseholdRow;
import io.mealie.backend.persistence.model.ToolRow;
import io.mealie.backend.query.SqlFragment;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ToolMapper {

    long count(@Param("where") SqlFragment where);

    List<ToolRow> page(@Param("where") SqlFragment where, @Param("order") SqlFragment order,
            @Param("limit") Long limit, @Param("offset") long offset, @Param("sqlite") boolean sqlite);

    List<IdRow> ids(@Param("where") SqlFragment where, @Param("order") SqlFragment order);

    List<ToolHouseholdRow> householdSlugs(@Param("toolIds") List<Object> toolIds);
}
