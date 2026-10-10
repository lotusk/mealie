package io.mealie.backend.organizers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The tag endpoints against a throwaway SQLite database laid out like Python's. Exact parity with Python is
 * checked by dev/rebuild/diff_test.py and write_diff_tags.py against both live backends; this covers the behaviour
 * in CI without Python.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TagIntegrationTest {

    static final String GROUP = "81c3fa6a7b734766af45af76b806aab4";
    static final String OTHER_GROUP = "11111111111141118111111111111111";
    static final String ORGANIZER = "b9dcf0b0-5b3d-4d1c-82ec-384d5cd191dd";
    static final String VIEWER = "f414f6af-f5bc-46de-9116-47464f064456";
    static final String DESSERT = "29bf7010-a6ba-4881-9607-9c57b83d41ea";
    static final String SPICY = "6cba93ff-0325-41ea-aeba-2e6c801ebbe4";
    static final String FOREIGN = "7e0c7355-eff5-4beb-9380-5f426ef62a23";
    static final String RECIPE = "06ee43aa-0599-4d37-be00-3dfee3dd13aa";

    @TempDir
    static Path dataDir;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void mealieEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TESTING", () -> "true");
        registry.add("PRODUCTION", () -> "false");
        registry.add("DATA_DIR", () -> dataDir.toString());
        registry.add("DB_ENGINE", () -> "sqlite");
        // Nothing listens here: events are logged as undeliverable, which must not affect the response.
        registry.add("MEALIE_PYTHON_URL", () -> "http://127.0.0.1:9");
    }

    @BeforeAll
    static void createDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE users (id CHAR(32) PRIMARY KEY, username VARCHAR, admin BOOLEAN, group_id CHAR(32),
                        household_id CHAR(32), tokens_valid_after DATETIME, can_organize BOOLEAN)""");
            st.executeUpdate("""
                    CREATE TABLE tags (id CHAR(32) PRIMARY KEY, group_id CHAR(32) NOT NULL, name VARCHAR NOT NULL,
                        slug VARCHAR NOT NULL, created_at DATETIME, update_at DATETIME,
                        CONSTRAINT tags_slug_group_id_key UNIQUE (slug, group_id))""");
            st.executeUpdate("""
                    CREATE TABLE recipes (id CHAR(32) PRIMARY KEY, slug VARCHAR, group_id CHAR(32), user_id CHAR(32),
                        rating FLOAT, name VARCHAR, description VARCHAR, image VARCHAR, total_time VARCHAR,
                        prep_time VARCHAR, perform_time VARCHAR, total_time_seconds INTEGER,
                        prep_time_seconds INTEGER, perform_time_seconds INTEGER, recipe_yield VARCHAR,
                        recipe_yield_quantity FLOAT, recipe_servings FLOAT, org_url VARCHAR, date_added DATE,
                        date_updated DATETIME, last_made DATETIME, name_normalized VARCHAR,
                        description_normalized VARCHAR, cook_time VARCHAR, "recipeCuisine" VARCHAR,
                        is_ocr_recipe BOOLEAN, created_at DATETIME, update_at DATETIME)""");
            st.executeUpdate("CREATE TABLE recipes_to_tags (recipe_id CHAR(32), tag_id CHAR(32))");
            st.executeUpdate("""
                    CREATE TABLE categories (id CHAR(32) PRIMARY KEY, group_id CHAR(32), name VARCHAR, slug VARCHAR,
                        created_at DATETIME, update_at DATETIME)""");
            st.executeUpdate("CREATE TABLE recipes_to_categories (recipe_id CHAR(32), category_id CHAR(32))");
            st.executeUpdate("""
                    CREATE TABLE tools (id CHAR(32) PRIMARY KEY, group_id CHAR(32), name VARCHAR, slug VARCHAR,
                        on_hand BOOLEAN, created_at DATETIME, update_at DATETIME)""");
            st.executeUpdate("CREATE TABLE recipes_to_tools (recipe_id CHAR(32), tool_id CHAR(32))");
            st.executeUpdate("CREATE TABLE households (id CHAR(32) PRIMARY KEY, slug VARCHAR)");
            st.executeUpdate("CREATE TABLE households_to_tools (household_id CHAR(32), tool_id CHAR(32))");

            st.executeUpdate("INSERT INTO users VALUES ('" + hex(ORGANIZER) + "', 'admin', 1, '" + GROUP
                    + "', 'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa', NULL, 1)");
            st.executeUpdate("INSERT INTO users VALUES ('" + hex(VIEWER) + "', 'viewer', 0, '" + GROUP
                    + "', NULL, NULL, 0)");
            st.executeUpdate("INSERT INTO tags VALUES ('" + hex(DESSERT) + "', '" + GROUP
                    + "', 'Dessert', 'dessert', '2026-01-01 10:00:00.000000', '2026-01-02 10:00:00.123456')");
            st.executeUpdate("INSERT INTO tags VALUES ('" + hex(SPICY) + "', '" + GROUP
                    + "', 'Spicy', 'spicy', '2026-01-03 10:00:00.000000', '2026-01-03 10:00:00.000000')");
            st.executeUpdate("INSERT INTO tags VALUES ('" + hex(FOREIGN) + "', '" + OTHER_GROUP
                    + "', 'Other group', 'other-group', '2026-01-04 10:00:00.000000', NULL)");
            st.executeUpdate("INSERT INTO recipes (id, slug, group_id, user_id, name, description, recipe_servings,"
                    + " date_added, created_at, update_at) VALUES ('" + hex(RECIPE) + "', 'cake', '" + GROUP
                    + "', '" + hex(ORGANIZER) + "', 'Cake', '', 2.5, '2026-01-05', '2026-01-05 10:00:00.000000',"
                    + " '2026-01-05 10:00:00.500000')");
            st.executeUpdate("INSERT INTO recipes_to_tags VALUES ('" + hex(RECIPE) + "', '" + hex(DESSERT) + "')");
        }
    }

    static String hex(String uuid) {
        return uuid.replace("-", "");
    }

    static MockHttpServletRequestBuilder as(String userId, MockHttpServletRequestBuilder request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String token = JWT.create().withSubject(userId).withIssuedAt(now).withExpiresAt(now.plus(1, ChronoUnit.HOURS))
                .sign(Algorithm.HMAC256("shh-secret-test-key"));
        return request.header("Authorization", "Bearer " + token);
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void listsOnlyTheUsersGroupWithPythonsPaginationShape() throws Exception {
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags").param("perPage", "1").param("orderBy", "name")
                        .param("orderDirection", "asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.per_page").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.total_pages").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Dessert"))
                .andExpect(jsonPath("$.items[0].recipeCount").value(1))
                .andExpect(jsonPath("$.next").value("/tags?orderBy=name&orderByNullPosition=None"
                        + "&orderDirection=asc&queryFilter=None&paginationSeed=None&page=2&perPage=1"))
                .andExpect(jsonPath("$.previous").value(nullValue()));
    }

    @Test
    void filtersSearchesAndRejectsBadFilters() throws Exception {
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags").param("queryFilter", "recipes.slug = cake")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].slug").value("dessert"));
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags").param("search", "spi")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].slug").value("spicy"));
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags").param("queryFilter", "foo = 1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("invalid attribute string: 'foo' does not exist on this schema"));
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags").param("page", "x")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status_code").value(422))
                .andExpect(jsonPath("$.message").value(containsString("'loc': ('query', 'page')")));
    }

    @Test
    void readsOneTagBySlugWithItsRecipes() throws Exception {
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags/slug/dessert")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipes[0].slug").value("cake"))
                .andExpect(jsonPath("$.recipes[0].householdId").value("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
                .andExpect(jsonPath("$.recipes[0].recipeServings").value(2.5))
                .andExpect(jsonPath("$.recipes[0].updatedAt").value("2026-01-05T10:00:00.500000Z"))
                .andExpect(jsonPath("$.recipes[0].tags[0].recipeCount").value(1));
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags/" + DESSERT)))
                .andExpect(jsonPath("$.recipes", hasSize(0)));
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags/" + FOREIGN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail.message").value("Not found."));
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags/empty")))
                .andExpect(jsonPath("$[0].slug").value("spicy"))
                .andExpect(jsonPath("$[0].created_at").value("2026-01-03T10:00:00+00:00"));
    }

    @Test
    void createsUpdatesMergesAndDeletes() throws Exception {
        String created = mvc.perform(as(ORGANIZER,
                        json(post("/api/organizers/tags"), "{\"name\": \"  Crème Brûlée! \"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Crème Brûlée!"))
                .andExpect(jsonPath("$.slug").value("creme-brulee"))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(as(ORGANIZER, json(post("/api/organizers/tags"), "{\"name\": \"Creme Brulee\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail.message").value("This item already exists."));
        mvc.perform(as(ORGANIZER, json(put("/api/organizers/tags/" + id), "{\"name\": \"Brulee\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("brulee"));
        String other = mvc.perform(as(ORGANIZER, json(post("/api/organizers/tags"), "{\"name\": \"Merge me\"}")))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(as(ORGANIZER, json(post("/api/organizers/tags/merge"),
                        "{\"fromId\": \"" + other + "\", \"toId\": \"" + id + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("brulee"));
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags/" + other)))
                .andExpect(status().isNotFound());
        mvc.perform(as(ORGANIZER, delete("/api/organizers/tags/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
        mvc.perform(as(ORGANIZER, delete("/api/organizers/tags/" + id)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Bad Request"));
    }

    @Test
    void enforcesPermissionsAndFastApiOrdering() throws Exception {
        mvc.perform(as(VIEWER, json(post("/api/organizers/tags"), "{\"name\": \"x\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/organizers/tags"), "{\"name\": \"x\"}"))
                .andExpect(status().isUnauthorized());
        // The body is parsed before authentication, as in FastAPI.
        mvc.perform(json(post("/api/organizers/tags"), "{bad"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("'type': 'json_invalid', 'loc': ('body', 1)")));
        mvc.perform(as(ORGANIZER, get("/api/organizers/tags/")))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string("Location", "http://localhost/api/organizers/tags"));
    }
}
