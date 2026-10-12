package io.mealie.backend.household;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.persistence.model.HouseholdStatisticsRow;
import org.springframework.stereotype.Service;

@Service
public class HouseholdStatisticsService {

    private final HouseholdStatisticsRepository repository;

    public HouseholdStatisticsService(HouseholdStatisticsRepository repository) {
        this.repository = repository;
    }

    public HouseholdStatisticsRow calculate(AuthUser user) {
        return repository.calculate(user.groupId(), user.householdId());
    }
}
