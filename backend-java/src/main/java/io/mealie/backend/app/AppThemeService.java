package io.mealie.backend.app;

import io.mealie.backend.config.MealieEnv;
import org.springframework.stereotype.Service;

/** Reads theme settings from the same THEME_* environment variables as Python. */
@Service
public class AppThemeService {

    private final MealieEnv env;

    public AppThemeService(MealieEnv env) {
        this.env = env;
    }

    public AppTheme get() {
        return new AppTheme(
                env.get("THEME_LIGHT_PRIMARY", "#E58325"),
                env.get("THEME_LIGHT_ACCENT", "#007A99"),
                env.get("THEME_LIGHT_SECONDARY", "#973542"),
                env.get("THEME_LIGHT_SUCCESS", "#43A047"),
                env.get("THEME_LIGHT_INFO", "#1976D2"),
                env.get("THEME_LIGHT_WARNING", "#FF6D00"),
                env.get("THEME_LIGHT_ERROR", "#EF5350"),
                env.get("THEME_DARK_PRIMARY", "#E58325"),
                env.get("THEME_DARK_ACCENT", "#007A99"),
                env.get("THEME_DARK_SECONDARY", "#973542"),
                env.get("THEME_DARK_SUCCESS", "#43A047"),
                env.get("THEME_DARK_INFO", "#1976D2"),
                env.get("THEME_DARK_WARNING", "#FF6D00"),
                env.get("THEME_DARK_ERROR", "#EF5350"));
    }
}
