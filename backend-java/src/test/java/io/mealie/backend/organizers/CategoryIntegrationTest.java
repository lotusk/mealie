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
import java.util.UUID;
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
 * The category endpoints against a throwaway SQLite database laid out like Python's. Exact parity with Python is
 * checked by dev/rebuild/diff_test.py and write_diff_categories.py against both live backends; this covers the behaviour
 * in CI without Python.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryIntegrationTest {

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
                    CREATE TABLE tags (id CHAR(32) PRIMARY KEY, group_id CHAR(32), name VARCHAR, slug VARCHAR,
                        created_at DATETIME, update_at DATETIME)""");
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
                    CREATE TABLE categories (id CHAR(32) PRIMARY KEY, group_id CHAR(32) NOT NULL, name VARCHAR NOT NULL,
                        slug VARCHAR NOT NULL, created_at DATETIME, update_at DATETIME,
                        CONSTRAINT category_slug_group_id_key UNIQUE (slug, group_id))""");
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
            st.executeUpdate("INSERT INTO categories VALUES ('" + hex(DESSERT) + "', '" + GROUP
                    + "', 'Dessert', 'dessert', '2026-01-01 10:00:00.000000', '2026-01-02 10:00:00.123456')");
            st.executeUpdate("INSERT INTO categories VALUES ('" + hex(SPICY) + "', '" + GROUP
                    + "', 'Spicy', 'spicy', '2026-01-03 10:00:00.000000', '2026-01-03 10:00:00.000000')");
            st.executeUpdate("INSERT INTO categories VALUES ('" + hex(FOREIGN) + "', '" + OTHER_GROUP
                    + "', 'Other group', 'other-group', '2026-01-04 10:00:00.000000', NULL)");
            st.executeUpdate("INSERT INTO recipes (id, slug, group_id, user_id, name, description, recipe_servings,"
                    + " date_added, created_at, update_at) VALUES ('" + hex(RECIPE) + "', 'cake', '" + GROUP
                    + "', '" + hex(ORGANIZER) + "', 'Cake', '', 2.5, '2026-01-05', '2026-01-05 10:00:00.000000',"
                    + " '2026-01-05 10:00:00.500000')");
            st.executeUpdate("INSERT INTO recipes_to_categories VALUES ('" + hex(RECIPE) + "', '" + hex(DESSERT) + "')");
            st.executeUpdate("INSERT INTO recipes_to_categories VALUES ('" + hex(RECIPE) + "', '" + hex(SPICY) + "')");
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

    static final String C = "/api/organizers/categories";

    static String idOf(String json) {
        return json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    String create(String name) throws Exception {
        return idOf(mvc.perform(as(ORGANIZER, json(post(C), "{\"name\": \"" + name + "\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    String updateAt(String id) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                Statement st = conn.createStatement();
                var rs = st.executeQuery("SELECT update_at FROM categories WHERE id = '" + hex(id) + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    int count(String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                Statement st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void listsOnlyTheUsersGroupWithPythonsPaginationShape() throws Exception {
        mvc.perform(as(ORGANIZER, get(C).param("perPage", "1").param("orderBy", "name")
                        .param("orderDirection", "asc").param("queryFilter", "id IN [\"" + DESSERT + "\", \""
                                + SPICY + "\", \"" + FOREIGN + "\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.per_page").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.total_pages").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Dessert"))
                .andExpect(jsonPath("$.items[0].recipeCount").value(1))
                .andExpect(jsonPath("$.next").value(containsString("/categories?orderBy=name")))
                .andExpect(jsonPath("$.previous").value(nullValue()));
        mvc.perform(as(ORGANIZER, get(C).param("search", "spi")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].slug").value("spicy"));
        mvc.perform(as(ORGANIZER, get(C).param("page", "x")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("'loc': ('query', 'page')")));
    }

    @Test
    void emptyIsCamelCaseCategoryBase() throws Exception {
        String id = create("Unused");
        mvc.perform(as(ORGANIZER, get(C + "/empty")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].groupId").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].slug").value("unused"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].recipeCount").isEmpty())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].created_at").isEmpty());
        mvc.perform(as(ORGANIZER, delete(C + "/" + id))).andExpect(status().isOk());
    }

    @Test
    void getByIdReturnsOnlyIdSlugName() throws Exception {
        mvc.perform(as(ORGANIZER, get(C + "/" + DESSERT)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"" + DESSERT + "\",\"slug\":\"dessert\",\"name\":\"Dessert\"}",
                        true));
        mvc.perform(as(ORGANIZER, get(C + "/" + FOREIGN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail.message").value("Not found."))
                .andExpect(jsonPath("$.detail.exception").value(nullValue()));
        mvc.perform(as(ORGANIZER, get(C + "/merge"))).andExpect(status().isUnprocessableContent());
    }

    @Test
    void readsOneCategoryBySlugWithItsRecipes() throws Exception {
        mvc.perform(as(ORGANIZER, get(C + "/slug/dessert")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()))
                .andExpect(jsonPath("$.name").value("Dessert"))
                .andExpect(jsonPath("$.recipes[0].slug").value("cake"))
                .andExpect(jsonPath("$.recipes[0].updatedAt").value("2026-01-05T10:00:00.500000Z"))
                .andExpect(jsonPath("$.recipes[0].recipeCategory", hasSize(2)));
        mvc.perform(as(ORGANIZER, get(C + "/slug/nope")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail.message").value("Not found."));
    }

    @Test
    void createStripsSlugifiesAndRejectsDuplicatesAndBlanks() throws Exception {
        mvc.perform(as(ORGANIZER, json(post(C), "{\"name\": \"  Crème Brûlée! \"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Crème Brûlée!"))
                .andExpect(jsonPath("$.slug").value("creme-brulee"))
                .andExpect(jsonPath("$.recipeCount").value(0));
        mvc.perform(as(ORGANIZER, json(post(C), "{\"name\": \"Creme Brulee\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail.message").value("This item already exists."))
                .andExpect(jsonPath("$.detail.exception").value(containsString(
                        "UNIQUE constraint failed: categories.slug, categories.group_id\n[SQL: INSERT INTO categories"
                                + " (group_id, id, name, slug, created_at, update_at) VALUES (?, ?, ?, ?, ?, ?)]")));
        mvc.perform(as(ORGANIZER, json(post(C), "{\"name\": \"  \"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.detail.exception").value(""));
    }

    @Test
    void updateWritesOnlyChangedColumns() throws Exception {
        String id = create("Rename me");
        String before = updateAt(id);
        mvc.perform(as(ORGANIZER, json(put(C + "/" + id), "{\"name\": \" Rename me \"}")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"" + id + "\",\"slug\":\"rename-me\",\"name\":\"Rename me\"}",
                        true));
        org.junit.jupiter.api.Assertions.assertEquals(before, updateAt(id));

        mvc.perform(as(ORGANIZER, json(put(C + "/" + id), "{\"name\": \" Renamed \"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("renamed"))
                .andExpect(jsonPath("$.name").value("Renamed"));
        org.junit.jupiter.api.Assertions.assertNotEquals(before, updateAt(id));

        mvc.perform(as(ORGANIZER, json(put(C + "/" + id), "{\"name\": \"Dessert\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail.message").value("This item already exists."))
                .andExpect(jsonPath("$.detail.exception").value(containsString(
                        "[SQL: UPDATE categories SET name=?, slug=?, update_at=? WHERE categories.id = ?]")));
        mvc.perform(as(ORGANIZER, json(put(C + "/" + id), "{\"name\": \"  \"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail.exception").value(""));
        mvc.perform(as(ORGANIZER, json(put(C + "/" + UUID.randomUUID()), "{\"name\": \"x\"}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail.message").value("Not found."));
    }

    @Test
    void deleteRemovesLinksAndRepeatsAsNotFound() throws Exception {
        String id = create("Doomed");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO recipes_to_categories VALUES ('" + hex(RECIPE) + "', '" + hex(id) + "')");
        }
        mvc.perform(as(ORGANIZER, delete(C + "/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                count("SELECT count(*) FROM recipes_to_categories WHERE category_id = '" + hex(id) + "'"));
        mvc.perform(as(ORGANIZER, delete(C + "/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.detail.exception").value("No row was found when one was required"));
    }

    @Test
    void mergeMovesLinksWithoutDuplicates() throws Exception {
        String from = create("Merge from");
        String to = create("Merge to");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("mealie.db"));
                Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO recipes_to_categories VALUES ('" + hex(RECIPE) + "', '" + hex(from) + "')");
            st.executeUpdate("INSERT INTO recipes_to_categories VALUES ('" + hex(RECIPE) + "', '" + hex(to) + "')");
        }
        String same = "{\"fromId\": \"" + from + "\", \"toId\": \"" + from + "\"}";
        mvc.perform(as(ORGANIZER, json(post(C + "/merge"), same)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from_id and to_id must be different"));
        String unknownFrom = "{\"fromId\": \"" + UUID.randomUUID() + "\", \"toId\": \"" + to + "\"}";
        mvc.perform(as(ORGANIZER, json(post(C + "/merge"), unknownFrom)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("from_id category not found"));
        String unknownTo = "{\"fromId\": \"" + from + "\", \"toId\": \"" + FOREIGN + "\"}";
        mvc.perform(as(ORGANIZER, json(post(C + "/merge"), unknownTo)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("to_id category not found"));
        mvc.perform(as(ORGANIZER, json(post(C + "/merge"), "{\"fromId\": \"bad\"}")))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(as(ORGANIZER, json(post(C + "/merge"),
                        "{\"fromId\": \"" + from + "\", \"toId\": \"" + to + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(to))
                .andExpect(jsonPath("$.recipeCount").value(1));
        mvc.perform(as(ORGANIZER, get(C + "/" + from))).andExpect(status().isNotFound());
    }

    @Test
    void enforcesPermissionsAndFastApiOrdering() throws Exception {
        mvc.perform(as(VIEWER, json(post(C), "{\"name\": \"x\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(VIEWER, delete(C + "/" + DESSERT))).andExpect(status().isForbidden());
        mvc.perform(json(post(C), "{\"name\": \"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(json(post(C), "{bad"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("'type': 'json_invalid', 'loc': ('body', 1)")));
        mvc.perform(as(ORGANIZER, get(C + "/slug/dessert/")))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string("Location", "http://localhost" + C + "/slug/dessert"));
    }
}
