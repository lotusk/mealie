package io.mealie.backend.household;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.persistence.model.HouseholdStatisticsRow;
import io.mealie.backend.web.validation.PythonEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HouseholdStatisticsController {

    private final HouseholdStatisticsService service;

    public HouseholdStatisticsController(HouseholdStatisticsService service) {
        this.service = service;
    }

    @GetMapping("/api/households/statistics")
    @PythonEndpoint(file = "mealie/routes/households/controller_household_self_service.py", line = 86,
            function = "get_statistics")
    HouseholdStatisticsRow getStatistics(AuthUser user) {
        return service.calculate(user);
    }
}
