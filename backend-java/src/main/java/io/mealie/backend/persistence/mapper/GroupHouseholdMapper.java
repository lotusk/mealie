package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.GroupHouseholdRow;
import io.mealie.backend.persistence.model.IdRow;
import io.mealie.backend.query.SqlFragment;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface GroupHouseholdMapper {

    long count(@Param("where") SqlFragment where);

    List<GroupHouseholdRow> page(@Param("where") SqlFragment where, @Param("order") SqlFragment order,
            @Param("limit") Long limit, @Param("offset") long offset, @Param("sqlite") boolean sqlite);

    List<IdRow> ids(@Param("where") SqlFragment where);

    GroupHouseholdRow findOne(@Param("groupId") Object groupId, @Param("id") Object id,
            @Param("slug") String slug);
}
