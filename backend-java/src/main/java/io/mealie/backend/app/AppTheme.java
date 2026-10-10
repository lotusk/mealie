package io.mealie.backend.app;

/** Public theme settings returned by Python's {@code /api/app/about/theme}. */
public record AppTheme(
        String lightPrimary,
        String lightAccent,
        String lightSecondary,
        String lightSuccess,
        String lightInfo,
        String lightWarning,
        String lightError,
        String darkPrimary,
        String darkAccent,
        String darkSecondary,
        String darkSuccess,
        String darkInfo,
        String darkWarning,
        String darkError) {
}
