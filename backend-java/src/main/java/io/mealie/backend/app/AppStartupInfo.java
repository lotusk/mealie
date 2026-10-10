package io.mealie.backend.app;

/** Public startup state returned by Python's {@code /api/app/about/startup-info}. */
public record AppStartupInfo(boolean isFirstLogin, boolean isDemo) {
}
