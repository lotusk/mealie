package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.GroupSelfProviderRow;
import io.mealie.backend.persistence.model.GroupSelfRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface GroupSelfMapper {

    GroupSelfRow findById(@Param("id") Object id);

    List<GroupSelfProviderRow> findProviders(@Param("settingsId") Object settingsId);
}
