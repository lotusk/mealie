package io.mealie.backend.household;

import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.HouseholdStatisticsMapper;
import io.mealie.backend.persistence.model.HouseholdStatisticsRow;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class HouseholdStatisticsRepository {

    private final HouseholdStatisticsMapper mapper;
    private final SqlDialect dialect;

    public HouseholdStatisticsRepository(HouseholdStatisticsMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }

    public HouseholdStatisticsRow calculate(UUID groupId, UUID householdId) {
        return mapper.calculate(dialect.uuid(groupId), dialect.uuid(householdId));
    }
}
