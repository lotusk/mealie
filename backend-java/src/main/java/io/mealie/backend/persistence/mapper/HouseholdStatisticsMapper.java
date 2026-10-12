package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.HouseholdStatisticsRow;
import org.apache.ibatis.annotations.Param;

public interface HouseholdStatisticsMapper {

    HouseholdStatisticsRow calculate(@Param("groupId") Object groupId, @Param("householdId") Object householdId);
}
