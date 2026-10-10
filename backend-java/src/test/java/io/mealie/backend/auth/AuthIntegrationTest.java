package io.mealie.backend.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the app against a throwaway SQLite database laid out like Python's (CHAR(32) hex ids, 0/1 booleans, text
 * datetimes) and checks every rule in AuthService with tokens shaped like Python's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthIntegrationTest.WhoAmIController.class)
class AuthIntegrationTest {

    static final String SECRET = "secret-from-the-data-dir";
    static final String USER_ID = "b9dcf0b0-5b3d-4d1c-82ec-384d5cd191dd";
    static final String LOCKED_OUT_USER_ID = "f414f6af-f5bc-46de-9116-47464f064456";
    static final String GROUP_ID = "81c3fa6a-7b73-4766-af45-af76b806aab4";
    static final String EMPTY_GROUP_ID = "251b19fa-d564-4b4a-8bb3-0322cfb0ff23";
    static final String EMPTY_GROUP_USER_ID = "a2100144-2e66-42d7-9c06-51c5d778ed0d";
    static final String HOUSEHOLD_ID = "6eb8957b-f7d9-4c08-95cb-d4c5e3ea014e";
    static final String LOGIN_USER_ID = "973579a6-351b-4b27-b708-28b0b56d9451";
    static final String GROUP_PREFERENCES_ID = "bb59433f-b8a6-4278-ae25-b39ca9755c1f";
    static final String AI_SETTINGS_ID = "52016a78-a435-418c-9890-c66078b4b41c";
    static final String AI_PROVIDER_ID = "77609a76-6572-432e-adba-e7cae9cdba50";
    static final String STALE_PROVIDER_ID = "4372ccf1-f41a-4297-ab0c-f10a4c5a2737";

    @TempDir
    static Path dataDir;

    @Autowired
    MockMvc mvc;

    /** Stands in for a migrated endpoint that declares Depends(get_current_user). */
    @RestController
    static class WhoAmIController {
        @GetMapping("/api/test/whoami")
        AuthUser whoami(AuthUser user) {
            return user;
        }

        @GetMapping("/api/test/security-context")
        Map<String, Object> securityContext(Authentication authentication) {
            AuthUser user = (AuthUser) authentication.getPrincipal();
            return Map.of(
                    "userId", user.id(),
                    "authorities", authentication.getAuthorities().stream()
                            .map(authority -> authority.getAuthority())
                            .sorted()
                            .toList());
        }

        @GetMapping("/api/test/public")
        Map<String, String> publicEndpoint() {
            return Map.of("status", "public");
        }
    }

    @DynamicPropertySource
    static void mealieEnvironment(DynamicPropertyRegistry registry) {
        // TESTING resolves DATA_DIR against the base dir; an absolute path stays as is.
        registry.add("TESTING", () -> "true");
        registry.add("PRODUCTION", () -> "true");
        registry.add("DATA_DIR", () -> dataDir.toString());
        registry.add("DB_ENGINE", () -> "sqlite");
    }

    @BeforeAll
    static void createDatabase() throws Exception {
        Files.writeString(dataDir.resolve(".secret"), SECRET + "\n");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)");
            st.executeUpdate("INSERT INTO alembic_version VALUES ('27621d27c7e1')");
            st.executeUpdate("CREATE TABLE groups (id CHAR(32) NOT NULL PRIMARY KEY, name VARCHAR, slug VARCHAR)");
            st.executeUpdate("CREATE TABLE households (id CHAR(32) NOT NULL PRIMARY KEY, name VARCHAR, slug VARCHAR)");
            st.executeUpdate("CREATE TABLE group_preferences (id CHAR(32) NOT NULL PRIMARY KEY, "
                    + "group_id CHAR(32), private_group BOOLEAN, show_announcements BOOLEAN)");
            st.executeUpdate("CREATE TABLE ai_provider_settings (id CHAR(32) NOT NULL PRIMARY KEY, "
                    + "group_id CHAR(32), default_provider_id CHAR(32), audio_provider_id CHAR(32), "
                    + "image_provider_id CHAR(32))");
            st.executeUpdate("CREATE TABLE ai_providers (id CHAR(32) NOT NULL PRIMARY KEY, "
                    + "settings_id CHAR(32), name VARCHAR, api_key VARCHAR)");
            st.executeUpdate("INSERT INTO groups VALUES ('" + hex(GROUP_ID) + "', 'Home Group', 'home-group')");
            st.executeUpdate("INSERT INTO groups VALUES ('" + hex(EMPTY_GROUP_ID) + "', 'Empty Group', 'empty-group')");
            st.executeUpdate("INSERT INTO households VALUES ('" + hex(HOUSEHOLD_ID)
                    + "', 'Main Household', 'main-household')");
            st.executeUpdate("INSERT INTO group_preferences VALUES ('" + hex(GROUP_PREFERENCES_ID) + "', '"
                    + hex(GROUP_ID) + "', 1, 0)");
            st.executeUpdate("INSERT INTO ai_provider_settings VALUES ('" + hex(AI_SETTINGS_ID) + "', '"
                    + hex(GROUP_ID) + "', '" + hex(AI_PROVIDER_ID) + "', '" + hex(STALE_PROVIDER_ID)
                    + "', '" + hex(AI_PROVIDER_ID) + "')");
            st.executeUpdate("INSERT INTO ai_providers VALUES ('" + hex(AI_PROVIDER_ID) + "', '"
                    + hex(AI_SETTINGS_ID) + "', 'Provider One', 'never-expose-this-api-key')");
            st.executeUpdate("""
                    CREATE TABLE users (id CHAR(32) NOT NULL PRIMARY KEY, username VARCHAR, admin BOOLEAN,
                        group_id CHAR(32) NOT NULL, household_id CHAR(32), tokens_valid_after DATETIME,
                        email VARCHAR, password VARCHAR, auth_method VARCHAR, login_attemps INTEGER,
                        locked_at DATETIME, full_name VARCHAR, advanced BOOLEAN, show_announcements BOOLEAN,
                        last_read_announcement VARCHAR, can_invite BOOLEAN, can_manage BOOLEAN,
                        can_manage_household BOOLEAN, can_organize BOOLEAN, cache_key VARCHAR)""");
            st.executeUpdate("""
                    CREATE TABLE long_live_tokens (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR NOT NULL,
                        token VARCHAR NOT NULL, user_id CHAR(32), created_at DATETIME)""");
            st.executeUpdate("INSERT INTO users (id, username, admin, group_id) VALUES ('" + hex(USER_ID)
                    + "', 'admin', 1, '" + hex(GROUP_ID) + "')");
            st.executeUpdate("INSERT INTO users (id, username, admin, group_id) VALUES ('"
                    + hex(EMPTY_GROUP_USER_ID) + "', 'empty-user', 0, '" + hex(EMPTY_GROUP_ID) + "')");
            // Password changed at a known time: tokens issued before it are void.
            st.executeUpdate("INSERT INTO users (id, username, admin, group_id, tokens_valid_after) VALUES ('"
                    + hex(LOCKED_OUT_USER_ID) + "', 'kai', 0, '" + hex(GROUP_ID)
                    + "', '2026-01-01 12:00:00.000000')");
            st.executeUpdate("INSERT INTO users (id, username, email, password, auth_method, login_attemps, admin, "
                    + "group_id) VALUES ('" + hex(LOGIN_USER_ID)
                    + "', 'login-user', 'login@example.com', 'test-pass', 'MEALIE', 0, 0, '" + hex(GROUP_ID) + "')");
            st.executeUpdate("UPDATE users SET household_id = '" + hex(HOUSEHOLD_ID)
                    + "', full_name = 'Login User', advanced = 1, show_announcements = 1, "
                    + "last_read_announcement = '2026-09', can_invite = 1, can_manage = 0, "
                    + "can_manage_household = 1, can_organize = 0, cache_key = 'avatar-42' WHERE id = '"
                    + hex(LOGIN_USER_ID) + "'");
            st.executeUpdate("INSERT INTO long_live_tokens (id, name, token, user_id, created_at) VALUES "
                    + "(12, 'Automation', 'private-token-value', '"
                    + hex(LOGIN_USER_ID) + "', '2026-01-02 03:04:05.000000')");
        }
    }

    static String hex(String uuid) {
        return uuid.replace("-", "");
    }

    static String token(String secret, Consumer<JWTCreator.Builder> claims) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JWTCreator.Builder builder = JWT.create()
                .withIssuer("mealie")
                .withIssuedAt(now)
                .withExpiresAt(now.plus(1, ChronoUnit.HOURS));
        claims.accept(builder);
        return builder.sign(Algorithm.HMAC256(secret));
    }

    static String userToken(String userId) {
        return token(SECRET, b -> b.withSubject(userId));
    }

    @Test
    void validTokenResolvesTheUser() throws Exception {
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + userToken(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.id").value(USER_ID))
                .andExpect(jsonPath("$.groupId").value(GROUP_ID))
                .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void userSelfReturnsPythonCompatibleProfileWithoutSecrets() throws Exception {
        mvc.perform(get("/api/users/self").header("Authorization", "Bearer " + userToken(LOGIN_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LOGIN_USER_ID))
                .andExpect(jsonPath("$.username").value("login-user"))
                .andExpect(jsonPath("$.fullName").value("Login User"))
                .andExpect(jsonPath("$.email").value("login@example.com"))
                .andExpect(jsonPath("$.authMethod").value("Mealie"))
                .andExpect(jsonPath("$.group").value("Home Group"))
                .andExpect(jsonPath("$.groupId").value(GROUP_ID))
                .andExpect(jsonPath("$.groupSlug").value("home-group"))
                .andExpect(jsonPath("$.household").value("Main Household"))
                .andExpect(jsonPath("$.householdId").value(HOUSEHOLD_ID))
                .andExpect(jsonPath("$.householdSlug").value("main-household"))
                .andExpect(jsonPath("$.advanced").value(true))
                .andExpect(jsonPath("$.showAnnouncements").value(true))
                .andExpect(jsonPath("$.lastReadAnnouncement").value("2026-09"))
                .andExpect(jsonPath("$.canInvite").value(true))
                .andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.canManageHousehold").value(true))
                .andExpect(jsonPath("$.canOrganize").value(false))
                .andExpect(jsonPath("$.cacheKey").value("avatar-42"))
                .andExpect(jsonPath("$.tokens[0].id").value(12))
                .andExpect(jsonPath("$.tokens[0].name").value("Automation"))
                .andExpect(jsonPath("$.tokens[0].createdAt").exists())
                .andExpect(jsonPath("$.tokens[0].token").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void userSelfRequiresAuthenticationAndAcceptsCookie() throws Exception {
        mvc.perform(get("/api/users/self"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/api/users/self").cookie(new Cookie(AuthTokens.COOKIE_NAME,
                        userToken(LOGIN_USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LOGIN_USER_ID));
    }

    @Test
    void groupSelfReturnsPreferencesAndOnlyPublicAiProviderSummaries() throws Exception {
        String body = mvc.perform(get("/api/groups/self")
                        .header("Authorization", "Bearer " + userToken(LOGIN_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(GROUP_ID))
                .andExpect(jsonPath("$.name").value("Home Group"))
                .andExpect(jsonPath("$.slug").value("home-group"))
                .andExpect(jsonPath("$.preferences.id").value(GROUP_PREFERENCES_ID))
                .andExpect(jsonPath("$.preferences.groupId").value(GROUP_ID))
                .andExpect(jsonPath("$.preferences.privateGroup").value(true))
                .andExpect(jsonPath("$.preferences.showAnnouncements").value(false))
                .andExpect(jsonPath("$.aiProviderSettings.defaultProviderId").value(AI_PROVIDER_ID))
                .andExpect(jsonPath("$.aiProviderSettings.audioProviderId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.aiProviderSettings.imageProviderId").value(AI_PROVIDER_ID))
                .andExpect(jsonPath("$.aiProviderSettings.aiEnabled").value(true))
                .andExpect(jsonPath("$.aiProviderSettings.audioProviderEnabled").value(false))
                .andExpect(jsonPath("$.aiProviderSettings.imageProviderEnabled").value(true))
                .andExpect(jsonPath("$.aiProviderSettings.providers[0].id").value(AI_PROVIDER_ID))
                .andExpect(jsonPath("$.aiProviderSettings.providers[0].name").value("Provider One"))
                .andExpect(jsonPath("$.aiProviderSettings.providers[0].apiKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("never-expose-this-api-key"));
    }

    @Test
    void groupSelfRequiresAuthAndStaysWithinTheCurrentUsersGroup() throws Exception {
        mvc.perform(get("/api/groups/self"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/api/groups/self").cookie(new Cookie(AuthTokens.COOKIE_NAME,
                        userToken(EMPTY_GROUP_USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(EMPTY_GROUP_ID))
                .andExpect(jsonPath("$.preferences").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.aiProviderSettings").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void passwordLoginIssuesAPythonCompatibleTokenAndSessionCookie() throws Exception {
        String body = mvc.perform(post("/api/auth/token")
                        .param("username", "LOGIN@EXAMPLE.COM")
                        .param("password", "test-pass")
                        .param("remember_me", "true")
                        .header("x-forwarded-proto", "https")
                        .header("x-mealie-embedded", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(jsonPath("$.expires_in").value(172800))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=172800")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=None")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Partitioned")))
                .andReturn().getResponse().getContentAsString();

        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("access_token").asText();
        DecodedJWT jwt = JWT.require(Algorithm.HMAC256(SECRET)).build().verify(token);
        org.junit.jupiter.api.Assertions.assertEquals(LOGIN_USER_ID, jwt.getSubject());
        org.junit.jupiter.api.Assertions.assertEquals("mealie", jwt.getIssuer());
        org.junit.jupiter.api.Assertions.assertTrue(jwt.getClaim("rme").asBoolean());
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LOGIN_USER_ID));
    }

    @Test
    void passwordLoginTracksFailuresAndLocksTheUser() throws Exception {
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                mvc.perform(post("/api/auth/token").param("username", "login-user")
                                .param("password", "wrong"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.detail").value("Unauthorized"));
            }
            mvc.perform(post("/api/auth/token").param("username", "login-user")
                            .param("password", "test-pass"))
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.detail").value("User is locked out"));
        } finally {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                    Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE users SET login_attemps = 0, locked_at = NULL WHERE id = '"
                        + hex(LOGIN_USER_ID) + "'");
            }
        }
    }

    @Test
    void multipartLoginResetsFailedAttemptsAndUsesASessionCookieByDefault() throws Exception {
        try {
            mvc.perform(post("/api/auth/token").param("username", "login-user").param("password", "wrong"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(multipart("/api/auth/token")
                            .param("username", "login-user")
                            .param("password", "test-pass"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("Max-Age"))))
                    .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")));
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                    Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT login_attemps FROM users WHERE id = '"
                            + hex(LOGIN_USER_ID) + "'")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next());
                org.junit.jupiter.api.Assertions.assertEquals(0, rs.getInt(1));
            }
        } finally {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                    Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE users SET login_attemps = 0, locked_at = NULL WHERE id = '"
                        + hex(LOGIN_USER_ID) + "'");
            }
        }
    }

    @Test
    void validTokenPopulatesTheSpringSecurityContextAndAuthorities() throws Exception {
        mvc.perform(get("/api/test/security-context").header("Authorization", "Bearer " + userToken(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.authorities[1]").value("ROLE_USER"));
    }

    @Test
    void invalidCredentialsDoNotMakePublicRoutesPrivate() throws Exception {
        mvc.perform(get("/api/test/public").header("Authorization", "Bearer nonsense"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("public"));
    }

    @Test
    void schemeIsCaseInsensitive() throws Exception {
        mvc.perform(get("/api/test/whoami").header("Authorization", "bearer " + userToken(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void cookieIsTheFallback() throws Exception {
        mvc.perform(get("/api/test/whoami").cookie(new Cookie("mealie.access_token", userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID));
    }

    @Test
    void missingTokenIsRejectedLikePython() throws Exception {
        mvc.perform(get("/api/test/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void wrongSecretIsRejected() throws Exception {
        String forged = token("shh-secret-test-key", b -> b.withSubject(USER_ID));
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String expired = token(SECRET, b -> b.withSubject(USER_ID)
                .withIssuedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .withExpiresAt(Instant.now().minus(1, ChronoUnit.SECONDS)));
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherAlgorithmsAreRejected() throws Exception {
        String hs512 = JWT.create().withSubject(USER_ID).withIssuedAt(Instant.now())
                .sign(Algorithm.HMAC512(SECRET));
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + hs512))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserIsRejected() throws Exception {
        mvc.perform(get("/api/test/whoami")
                        .header("Authorization", "Bearer " + userToken("00000000-0000-0000-0000-000000000001")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokensIssuedBeforePasswordChangeAreRejected() throws Exception {
        String before = token(SECRET, b -> b.withSubject(LOCKED_OUT_USER_ID)
                .withIssuedAt(Instant.parse("2026-01-01T11:59:59Z")));
        String after = token(SECRET, b -> b.withSubject(LOCKED_OUT_USER_ID)
                .withIssuedAt(Instant.parse("2026-01-01T12:00:00Z")));
        String noIat = JWT.create().withSubject(LOCKED_OUT_USER_ID).sign(Algorithm.HMAC256(SECRET));
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + before))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + noIat))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + after))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("kai"));
    }

    @Test
    void apiTokenMustBeStoredForItsUser() throws Exception {
        String stored = token(SECRET, b -> b.withClaim("long_token", true).withClaim("id", USER_ID)
                .withClaim("name", "stored"));
        String notStored = token(SECRET, b -> b.withClaim("long_token", true).withClaim("id", USER_ID)
                .withClaim("name", "revoked"));
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO long_live_tokens (id, name, token, user_id) VALUES "
                    + "(11, 'stored', '"
                    + stored + "', '" + hex(USER_ID) + "')");
        }
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + stored))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID));
        // Python raises a bare HTTPException(401) here: default detail, no WWW-Authenticate.
        mvc.perform(get("/api/test/whoami").header("Authorization", "Bearer " + notStored))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.detail").value("Unauthorized"));
    }

    @Test
    void healthReportsEngineAndTokenState() throws Exception {
        mvc.perform(get("/api/java/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbEngine").value("sqlite"))
                .andExpect(jsonPath("$.database.connected").value(true))
                .andExpect(jsonPath("$.database.alembicRevision").value("27621d27c7e1"))
                .andExpect(jsonPath("$.auth").doesNotExist());
        mvc.perform(get("/api/java/health").header("Authorization", "Bearer " + userToken(USER_ID)))
                .andExpect(jsonPath("$.auth.authenticated").value(true))
                .andExpect(jsonPath("$.auth.userId").value(USER_ID));
        mvc.perform(get("/api/java/health").header("Authorization", "Bearer nonsense"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.authenticated").value(false));
    }

    @Test
    void unknownPathsAnswerLikeStarlette() throws Exception {
        mvc.perform(get("/api/foods"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Not Found"));
    }
}
