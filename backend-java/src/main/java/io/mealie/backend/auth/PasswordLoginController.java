package io.mealie.backend.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PasswordLoginController {

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn) {
    }

    private final AuthenticationManager authenticationManager;

    public PasswordLoginController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/api/auth/token")
    ResponseEntity<TokenResponse> login(
            HttpServletRequest request,
            @RequestParam(defaultValue = "") String username,
            @RequestParam(defaultValue = "") String password,
            @RequestParam(name = "remember_me", defaultValue = "false") boolean rememberMe) {
        PasswordLoginService.LoginResult result;
        try {
            PasswordCredentialsAuthentication authentication = (PasswordCredentialsAuthentication) authenticationManager
                    .authenticate(PasswordCredentialsAuthentication.unauthenticated(username, password, rememberMe));
            result = authentication.result();
        } catch (MealieAuthenticationException e) {
            throw e.apiException();
        }
        boolean secure = request.isSecure() || firstForwardedProto(request).equalsIgnoreCase("https");
        boolean embedded = secure && "true".equalsIgnoreCase(request.getHeader("x-mealie-embedded"));
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(AuthTokens.COOKIE_NAME, result.token())
                .path("/").httpOnly(false).secure(secure).sameSite(embedded ? "None" : "Lax");
        if (rememberMe) {
            cookie.maxAge(Duration.ofSeconds(result.expiresInSeconds()));
        }
        if (embedded) {
            cookie.partitioned(true);
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.build().toString())
                .body(new TokenResponse(result.token(), "bearer", result.expiresInSeconds()));
    }

    private static String firstForwardedProto(HttpServletRequest request) {
        String value = request.getHeader("x-forwarded-proto");
        return value == null ? "" : value.split(",", 2)[0].strip();
    }
}
