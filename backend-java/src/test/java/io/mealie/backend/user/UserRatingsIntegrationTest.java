package io.mealie.backend.user;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class UserRatingsIntegrationTest {

    static final String PATH = "/api/users/self/ratings";
    static final String USER = "b9dcf0b0-5b3d-4d1c-82ec-384d5cd191dd";
    static final String ADMIN = "f414f6af-f5bc-46de-9116-47464f064456";
    static final String EMPTY_USER = "7e0c7355-eff5-4beb-9380-5f426ef62a23";
    static final String RECIPE = "29bf7010-a6ba-4881-9607-9c57b83d41ea";
    static final String FAVORITE = "6cba93ff-0325-41ea-aeba-2e6c801ebbe4";
    static final String ZERO = "819da05d-90bc-4147-bbfc-842ccbd52002";

    @TempDir
    static Path dataDir;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("TESTING", () -> "true");
        registry.add("PRODUCTION", () -> "false");
        registry.add("DATA_DIR", () -> dataDir.toString());
        registry.add("DB_ENGINE", () -> "sqlite");
    }

    @BeforeAll
    static void database() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                var st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE users (id CHAR(32) PRIMARY KEY, username VARCHAR, admin BOOLEAN, group_id CHAR(32),
                        household_id CHAR(32), tokens_valid_after DATETIME, can_organize BOOLEAN)""");
            // Nullable favorite also exercises Python's legacy-null normalization.
            st.executeUpdate("""
                    CREATE TABLE users_to_recipes (id CHAR(32), user_id CHAR(32), recipe_id CHAR(32),
                        rating FLOAT, is_favorite BOOLEAN, PRIMARY KEY (id, user_id, recipe_id))""");
            st.executeUpdate("CREATE TABLE long_live_tokens (token VARCHAR, user_id CHAR(32))");
            for (String id : new String[] {USER, ADMIN, EMPTY_USER}) {
                st.executeUpdate("INSERT INTO users VALUES ('" + hex(id) + "', 'viewer', "
                        + (id.equals(ADMIN) ? 1 : 0)
                        + ", '81c3fa6a7b734766af45af76b806aab4', NULL, NULL, 0)");
            }
            for (String[] row : new String[][] {
                    {USER, RECIPE, "3.5", "0"}, {USER, FAVORITE, "NULL", "1"},
                    {USER, ZERO, "0", "NULL"}, {ADMIN, RECIPE, "5", "1"}}) {
                st.executeUpdate("INSERT INTO users_to_recipes VALUES ('" + hex(UUID.randomUUID().toString())
                        + "', '" + hex(row[0]) + "', '" + hex(row[1]) + "', " + row[2] + ", " + row[3] + ")");
            }
        }
    }

    static String hex(String value) {
        return value.replace("-", "");
    }

    static String token(String user) {
        return JWT.create().withSubject(user).withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusSeconds(3600)).sign(Algorithm.HMAC256("shh-secret-test-key"));
    }

    @Test
    void preservesSummaryFieldsNullRatingAndFavoriteOnlyRecords() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.ratings", hasSize(3)))
                .andExpect(jsonPath("$.ratings[?(@.recipeId == '" + RECIPE + "')].rating", contains(3.5)))
                .andExpect(jsonPath("$.ratings[?(@.recipeId == '" + RECIPE + "')].isFavorite", contains(false)))
                .andExpect(jsonPath("$.ratings[?(@.recipeId == '" + FAVORITE + "')].rating", contains(nullValue())))
                .andExpect(jsonPath("$.ratings[?(@.recipeId == '" + FAVORITE + "')].isFavorite", contains(true)))
                .andExpect(jsonPath("$.ratings[?(@.recipeId == '" + ZERO + "')].rating", contains(0.0)))
                .andExpect(jsonPath("$.ratings[?(@.recipeId == '" + ZERO + "')].isFavorite", contains(false)))
                .andExpect(jsonPath("$.ratings[0].*", hasSize(3)))
                .andExpect(jsonPath("$.ratings[0].userId").doesNotExist())
                .andExpect(jsonPath("$.ratings[0].id").doesNotExist());
    }

    @Test
    void adminStillOnlySeesOwnRatingsAndCannotOverrideUserViaQuery() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token(ADMIN)).param("userId", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratings", hasSize(1)))
                .andExpect(jsonPath("$.ratings[0].recipeId").value(RECIPE))
                .andExpect(jsonPath("$.ratings[0].rating").value(5.0));
    }

    @Test
    void returnsEmptyArrayForUserWithoutRatings() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token(EMPTY_USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ratings", hasSize(0)));
    }

    @Test
    void supportsSessionCookie() throws Exception {
        mvc.perform(get(PATH).cookie(new Cookie("mealie.access_token", token(USER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ratings", hasSize(3)));
    }

    @Test
    void rejectsMissingInvalidExpiredAndDeletedUserCredentials() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        String expired = JWT.create().withSubject(USER).withExpiresAt(Instant.now().minusSeconds(60))
                .sign(Algorithm.HMAC256("shh-secret-test-key"));
        for (String bearer : new String[] {"invalid", expired, token(UUID.randomUUID().toString())}) {
            mvc.perform(get(PATH).header("Authorization", "Bearer " + bearer))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"));
        }
    }
}
