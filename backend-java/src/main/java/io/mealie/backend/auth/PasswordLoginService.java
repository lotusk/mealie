package io.mealie.backend.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.mealie.backend.config.MealieEnv;
import io.mealie.backend.config.MealieSettings;
import io.mealie.backend.persistence.model.LoginUserRow;
import io.mealie.backend.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mealie password login, including the shared database's failed-attempt and lockout state. */
@Service
public class PasswordLoginService {

    public record LoginResult(String token, long expiresInSeconds) {
    }

    private static final String FAKE_HASH = "$2b$12$JdHtJOlkPFwyxdjdygEzPOtYmdQF5/R5tHxw5Tq8pxjubyLqdIX5i";

    private final PasswordLoginRepository users;
    private final MealieEnv env;
    private final MealieSettings settings;
    private final JwtSecret secret;

    public PasswordLoginService(PasswordLoginRepository users, MealieEnv env, MealieSettings settings,
            JwtSecret secret) {
        this.users = users;
        this.env = env;
        this.settings = settings;
        this.secret = secret;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String username, String password, boolean rememberMe) {
        // LDAP login can create accounts and has its own bind rules. Route that deployment to Python.
        if (ldapReady()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE);
        }

        LoginUserRow user = users.findByUsernameOrEmail(username).orElse(null);
        if (user == null || !"MEALIE".equals(user.authMethod())) {
            verifyFakePassword();
            throw new ApiException(HttpStatus.UNAUTHORIZED);
        }

        int maxAttempts = Integer.parseInt(env.get("SECURITY_MAX_LOGIN_ATTEMPTS", "5"));
        int attempts = user.loginAttempts() == null ? 0 : user.loginAttempts();
        int lockoutHours = Integer.parseInt(env.get("SECURITY_USER_LOCKOUT_TIME", "24"));
        if (attempts >= maxAttempts || user.lockedAt() != null
                && user.lockedAt().plusHours(lockoutHours).isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ApiException(HttpStatus.LOCKED, "User is locked out", Map.of());
        }

        if (!passwordMatches(password, user.password())) {
            if (!isDemoDefaultUser(user)) {
                users.recordFailedLogin(user.id(), maxAttempts, OffsetDateTime.now(ZoneOffset.UTC));
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED);
        }

        if (!isDemoDefaultUser(user)) {
            users.resetLoginAttempts(user.id());
        }

        String key = secret.current().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE));
        int hours = Math.min(Integer.parseInt(env.get("TOKEN_TIME", "48").strip()), 400 * 24);
        Duration lifetime = Duration.ofHours(hours);
        Instant now = Instant.now();
        String token = JWT.create()
                .withSubject(user.id().toString())
                .withClaim("rme", rememberMe)
                .withIssuer("mealie")
                .withIssuedAt(now)
                .withExpiresAt(now.plus(lifetime))
                .sign(Algorithm.HMAC256(key));
        return new LoginResult(token, lifetime.toSeconds());
    }

    private boolean passwordMatches(String raw, String stored) {
        if (settings.testing()) {
            return stored != null && MessageDigest.isEqual(
                    raw.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
        }
        if (stored == null) {
            verifyFakePassword();
            return false;
        }
        byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
        // Python bcrypt hashes only the first 72 UTF-8 bytes.
        byte[] bcryptBytes = Arrays.copyOf(rawBytes, Math.min(rawBytes.length, 72));
        return BCrypt.checkpw(bcryptBytes, stored);
    }

    private void verifyFakePassword() {
        if (!settings.testing()) {
            BCrypt.checkpw("abc123cba321", FAKE_HASH);
        }
    }

    private boolean isDemoDefaultUser(LoginUserRow user) {
        return env.flag("IS_DEMO", false) && "changeme@example.com".equalsIgnoreCase(user.email());
    }

    private boolean ldapReady() {
        return env.flag("LDAP_AUTH_ENABLED", false)
                && env.get("LDAP_SERVER_URL").isPresent()
                && env.get("LDAP_BASE_DN").isPresent();
    }
}
