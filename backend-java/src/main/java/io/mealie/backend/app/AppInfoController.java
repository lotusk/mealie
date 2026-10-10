package io.mealie.backend.app;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppInfoController {

    private final AppInfoService service;
    private final AppThemeService themeService;
    private final AppStartupInfoService startupInfoService;

    public AppInfoController(
            AppInfoService service, AppThemeService themeService, AppStartupInfoService startupInfoService) {
        this.service = service;
        this.themeService = themeService;
        this.startupInfoService = startupInfoService;
    }

    @GetMapping("/api/app/about")
    AppInfo getAppInfo() {
        return service.get();
    }

    @GetMapping("/api/app/about/theme")
    ResponseEntity<AppTheme> getAppTheme() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=604800")
                .body(themeService.get());
    }

    @GetMapping("/api/app/about/startup-info")
    AppStartupInfo getStartupInfo() {
        return startupInfoService.get();
    }
}
