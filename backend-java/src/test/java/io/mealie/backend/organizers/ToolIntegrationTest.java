package io.mealie.backend.organizers;

import static org.hamcrest.Matchers.containsString;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class ToolIntegrationTest {

    static final String GROUP = "81c3fa6a7b734766af45af76b806aab4";
    static final String OTHER_GROUP = "11111111111141118111111111111111";
    static final String USER = "b9dcf0b0-5b3d-4d1c-82ec-384d5cd191dd";
    static final String EMPTY_USER = "f414f6af-f5bc-46de-9116-47464f064456";
    static final String PAN = "29bf7010-a6ba-4881-9607-9c57b83d41ea";
    static final String WHISK = "6cba93ff-0325-41ea-aeba-2e6c801ebbe4";
    static final String FOREIGN = "7e0c7355-eff5-4beb-9380-5f426ef62a23";

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
            st.executeUpdate("""
                    CREATE TABLE tools (id CHAR(32) PRIMARY KEY, group_id CHAR(32), name VARCHAR, slug VARCHAR,
                        created_at DATETIME, update_at DATETIME)""");
            st.executeUpdate("CREATE TABLE recipes (id CHAR(32) PRIMARY KEY, slug VARCHAR)");
            st.executeUpdate("CREATE TABLE recipes_to_tools (recipe_id CHAR(32), tool_id CHAR(32))");
            st.executeUpdate("CREATE TABLE households (id CHAR(32) PRIMARY KEY, slug VARCHAR)");
            st.executeUpdate("CREATE TABLE households_to_tools (household_id CHAR(32), tool_id CHAR(32))");
            st.executeUpdate("INSERT INTO users VALUES ('" + hex(USER) + "', 'viewer', 0, '" + GROUP
                    + "', NULL, NULL, 0)");
            st.executeUpdate("INSERT INTO users VALUES ('" + hex(EMPTY_USER) + "', 'empty', 1, "
                    + "'22222222222242228222222222222222', NULL, NULL, 1)");
            st.executeUpdate("INSERT INTO tools VALUES ('" + hex(PAN) + "', '" + GROUP
                    + "', 'Baking Pan', 'baking-pan', '2026-01-01 00:00:00', NULL)");
            st.executeUpdate("INSERT INTO tools VALUES ('" + hex(WHISK) + "', '" + GROUP
                    + "', 'Whisk', 'whisk', '2026-01-02 00:00:00', NULL)");
            st.executeUpdate("INSERT INTO tools VALUES ('" + hex(FOREIGN) + "', '" + OTHER_GROUP
                    + "', 'Foreign Tool', 'foreign-tool', '2026-01-03 00:00:00', NULL)");
            st.executeUpdate("INSERT INTO recipes VALUES ('aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa', 'cake')");
            st.executeUpdate("INSERT INTO recipes_to_tools VALUES ('aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa', '"
                    + hex(PAN) + "')");
            st.executeUpdate("INSERT INTO households VALUES ('bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb', 'main')");
            st.executeUpdate("INSERT INTO households VALUES ('cccccccccccc4ccc8ccccccccccccccc', 'second')");
            st.executeUpdate("INSERT INTO households_to_tools VALUES ('bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb', '"
                    + hex(PAN) + "')");
            st.executeUpdate("INSERT INTO households_to_tools VALUES ('cccccccccccc4ccc8ccccccccccccccc', '"
                    + hex(PAN) + "')");
        }
    }

    static String hex(String value) {
        return value.replace("-", "");
    }

    static String token(String id) {
        return JWT.create().withSubject(id).withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plusSeconds(3600))
                .sign(Algorithm.HMAC256("shh-secret-test-key"));
    }

    static MockHttpServletRequestBuilder list() {
        return get("/api/organizers/tools").header("Authorization", "Bearer " + token(USER));
    }

    @Test
    void listsAllWithRecipeCountsAndHouseholdsWithoutDuplicatingTools() throws Exception {
        mvc.perform(list().param("page", "1").param("perPage", "-1").param("orderBy", "name")
                        .param("orderDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.per_page").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.total_pages").value(1))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(PAN))
                .andExpect(jsonPath("$.items[0].recipeCount").value(1))
                .andExpect(jsonPath("$.items[0].householdsWithTool", hasSize(2)))
                .andExpect(jsonPath("$.items[1].id").value(WHISK))
                .andExpect(jsonPath("$.items[1].recipeCount").value(0))
                .andExpect(jsonPath("$.items[1].householdsWithTool", hasSize(0)))
                .andExpect(jsonPath("$.next").value(nullValue()));
    }

    @Test
    void paginatesWithPythonLinksAndHandlesEmptyGroups() throws Exception {
        mvc.perform(list().param("perPage", "1").param("orderBy", "name").param("orderDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.next").value(containsString("/tools?orderBy=name")))
                .andExpect(jsonPath("$.next").value(containsString("page=2&perPage=1")));
        mvc.perform(list().param("perPage", "1").param("page", "-1")
                        .param("orderBy", "name").param("orderDirection", "asc"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[0].id").value(WHISK));
        mvc.perform(get("/api/organizers/tools").param("perPage", "-1")
                        .header("Authorization", "Bearer " + token(EMPTY_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void searchesAndFiltersRecipesAndHouseholdsWithinTheUsersGroup() throws Exception {
        mvc.perform(list().param("search", "bak"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(PAN));
        mvc.perform(list().param("queryFilter", "recipes.slug = cake"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(list().param("queryFilter", "householdsWithTool.slug = main"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(PAN));
        mvc.perform(list().param("queryFilter", "slug = foreign-tool"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(list().param("orderBy", "name; DROP TABLE tools"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthenticationAndValidatesQueries() throws Exception {
        mvc.perform(get("/api/organizers/tools").param("page", "bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/api/organizers/tools").cookie(new Cookie("mealie.access_token", token(USER))))
                .andExpect(status().isOk());
        mvc.perform(list().param("page", "bad").param("orderDirection", "sideways"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status_code").value(422))
                .andExpect(jsonPath("$.message").value(containsString("'loc': ('query', 'page')")));
    }
}
