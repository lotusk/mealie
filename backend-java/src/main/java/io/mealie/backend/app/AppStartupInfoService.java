package io.mealie.backend.app;

import io.mealie.backend.config.MealieEnv;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Builds startup information using the same default-user heuristic as Python. */
@Service
public class AppStartupInfoService {

    private static final String DEFAULT_EMAIL = "changeme@example.com";

    private final MealieEnv env;
    private final AppInfoRepository repository;

    public AppStartupInfoService(MealieEnv env, AppInfoRepository repository) {
        this.env = env;
        this.repository = repository;
    }

    public AppStartupInfo get() {
        return new AppStartupInfo(repository.userExistsByEmail(DEFAULT_EMAIL), isDemo());
    }

    private boolean isDemo() {
        String value = env.get("IS_DEMO", "false").strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "1", "on", "t", "true", "y", "yes" -> true;
            case "0", "off", "f", "false", "n", "no" -> false;
            default -> throw new IllegalArgumentException("IS_DEMO is not a valid boolean");
        };
    }
}
