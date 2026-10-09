package io.mealie.backend.tags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.mealie.backend.config.MealieEnv;
import io.mealie.backend.db.SqlDialect;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** Same HTTP/database checks on SQLite by default, or a disposable Postgres schema when TAG_TEST_POSTGRES_URL is set. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TagIntegrationTest.EventsConfig.class)
class TagIntegrationTest {
    static final String ROOT = "/api/organizers/tags";
    static final UUID GROUP = UUID.randomUUID(), OTHER = UUID.randomUUID(), USER = UUID.randomUUID(), READER = UUID.randomUUID();
    static final String PG = System.getenv("TAG_TEST_POSTGRES_URL");
    static final String SCHEMA = "tags_test_" + UUID.randomUUID().toString().replace("-", "");
    @TempDir static Path dataDir;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlDialect dialect;
    @Autowired ObjectMapper json;
    @Autowired CapturingEvents events;

    static class CapturingEvents extends TagEventPublisher {
        final List<String> operations = new ArrayList<>();
        CapturingEvents() { super(null, new MealieEnv(k -> null, Map.of())); }
        @Override public void publish(String operation, Map<String, Object> tag, HttpServletRequest request) { operations.add(operation); }
    }
    @TestConfiguration static class EventsConfig {
        @Bean @Primary CapturingEvents testEvents() { return new CapturingEvents(); }
    }
    static String url() { return PG == null ? "jdbc:sqlite:" + dataDir.resolve("mealie.db") : PG + (PG.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA; }
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) {
        registry.add("TESTING", () -> "true"); registry.add("PRODUCTION", () -> "false");
        registry.add("DATA_DIR", () -> dataDir.toString()); registry.add("DB_ENGINE", () -> PG == null ? "sqlite" : "postgres");
        if (PG != null) registry.add("POSTGRES_URL_OVERRIDE", () -> url().replace("jdbc:", ""));
    }
    @BeforeAll static void database() throws Exception {
        if (PG != null) try (var c = DriverManager.getConnection(PG); var st = c.createStatement()) { st.execute("CREATE SCHEMA " + SCHEMA); st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm"); }
        String guid = PG == null ? "CHAR(32)" : "UUID", time = PG == null ? "DATETIME" : "TIMESTAMP";
        try (var c = DriverManager.getConnection(url()); var st = c.createStatement()) {
            st.execute("CREATE TABLE users (id " + guid + " PRIMARY KEY, username VARCHAR, admin BOOLEAN, group_id " + guid + ", household_id " + guid + ", tokens_valid_after " + time + ", can_organize BOOLEAN)");
            st.execute("CREATE TABLE tags (id " + guid + " PRIMARY KEY, group_id " + guid + " NOT NULL, name VARCHAR NOT NULL, slug VARCHAR NOT NULL, created_at " + time + ", update_at " + time + ", UNIQUE(slug, group_id))");
            st.execute("CREATE TABLE recipes_to_tags (recipe_id " + guid + ", tag_id " + guid + " REFERENCES tags(id), UNIQUE(recipe_id, tag_id))");
            st.execute("CREATE TABLE recipes (id " + guid + " PRIMARY KEY, user_id " + guid + ", group_id " + guid + ", name VARCHAR, slug VARCHAR, recipe_servings FLOAT, recipe_yield_quantity FLOAT)");
            for (String table : List.of("categories", "tools")) st.execute("CREATE TABLE " + table + " (id " + guid + " PRIMARY KEY, group_id " + guid + ", name VARCHAR, slug VARCHAR)");
            st.execute("CREATE TABLE recipes_to_categories (recipe_id " + guid + ", category_id " + guid + ")");
            st.execute("CREATE TABLE recipes_to_tools (recipe_id " + guid + ", tool_id " + guid + ")");
            st.execute("CREATE TABLE households (id " + guid + " PRIMARY KEY, slug VARCHAR)");
            st.execute("CREATE TABLE households_to_tools (household_id " + guid + ", tool_id " + guid + ")");
        }
    }
    @AfterAll static void cleanup() throws Exception { if (PG != null) try (var c = DriverManager.getConnection(PG); var st = c.createStatement()) { st.execute("DROP SCHEMA " + SCHEMA + " CASCADE"); } }
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM recipes_to_tags"); jdbc.update("DELETE FROM tags"); jdbc.update("DELETE FROM recipes"); jdbc.update("DELETE FROM users");
        for (UUID id : List.of(USER, READER)) jdbc.update("INSERT INTO users (id, username, admin, group_id, household_id, can_organize) VALUES (?, ?, ?, ?, ?, ?)",
                dialect.uuid(id), "user", dialect.bool(true), dialect.uuid(GROUP), dialect.uuid(UUID.randomUUID()), dialect.bool(id.equals(USER)));
        events.operations.clear();
    }
    String auth(UUID id) { return "Bearer " + JWT.create().withSubject(id.toString()).withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plusSeconds(3600)).sign(Algorithm.HMAC256("shh-secret-test-key")); }
    Map<String, Object> create(String name) throws Exception {
        var response = mvc.perform(post(ROOT).header("Authorization", auth(USER)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readValue(response.getContentAsString(), Map.class);
    }
    void link(UUID recipe, Object tag) { jdbc.update("INSERT INTO recipes_to_tags VALUES (?, ?)", dialect.uuid(recipe), dialect.uuid(UUID.fromString(tag.toString()))); }
    @Test void crudAndSlugs() throws Exception {
        var tag = create("  Crème brûlée & tea  ");
        assertThat(tag.get("name")).isEqualTo("Crème brûlée & tea"); assertThat(tag.get("slug")).isEqualTo("creme-brulee-tea");
        mvc.perform(get(ROOT + "/" + tag.get("id")).header("Authorization", auth(USER))).andExpect(status().isOk()).andExpect(jsonPath("$.recipes").isEmpty());
        mvc.perform(put(ROOT + "/" + tag.get("id")).header("Authorization", auth(USER)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Dinner\",\"groupId\":\"" + OTHER + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("dinner")).andExpect(jsonPath("$.groupId").value(GROUP.toString()));
        mvc.perform(delete(ROOT + "/" + tag.get("id")).header("Authorization", auth(USER))).andExpect(status().isOk()).andExpect(content().string("null"));
        assertThat(events.operations).containsExactly("create", "update", "delete");
    }
    @Test void listCountsPagingSearchAndFilters() throws Exception {
        var alpha = create("Alpha"); create("Beta"); create("Gamma"); link(UUID.randomUUID(), alpha.get("id"));
        jdbc.update("INSERT INTO tags (id, group_id, name, slug) VALUES (?, ?, 'Secret', 'secret')", dialect.uuid(UUID.randomUUID()), dialect.uuid(OTHER));
        mvc.perform(get(ROOT).param("orderBy", "name").param("orderDirection", "asc").param("perPage", "1").header("Authorization", auth(USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.total_pages").value(3)).andExpect(jsonPath("$.items[0].recipeCount").value(1)).andExpect(jsonPath("$.previous").value(nullValue()));
        mvc.perform(get(ROOT).param("page", "-1").param("perPage", "2").param("orderBy", "name:asc").header("Authorization", auth(USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.items[0].name").value("Gamma"));
        mvc.perform(get(ROOT).param("perPage", "-1").header("Authorization", auth(USER))).andExpect(jsonPath("$.per_page").value(3));
        mvc.perform(get(ROOT).param("search", "\"Alph\"").header("Authorization", auth(USER))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(ROOT).param("queryFilter", "(name = \"alpha\" OR name = \"BETA\") AND slug NOT IN [\"gamma\"]").header("Authorization", auth(USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(ROOT + "/empty").header("Authorization", auth(USER))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }
    @Test void duplicateSlugIsScopedToGroupAndRollsBack() throws Exception {
        create("Dinner");
        mvc.perform(post(ROOT).header("Authorization", auth(USER)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Dinner!\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail.message").value("This item already exists."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Long.class)).isEqualTo(1);
        var second = create("Lunch");
        mvc.perform(put(ROOT + "/" + second.get("id")).header("Authorization", auth(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Dinner\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get(ROOT + "/" + second.get("id")).header("Authorization", auth(USER)))
                .andExpect(jsonPath("$.name").value("Lunch"));
        jdbc.update("INSERT INTO tags (id, group_id, name, slug) VALUES (?, ?, 'Dinner', 'dinner')",
                dialect.uuid(UUID.randomUUID()), dialect.uuid(OTHER));
    }
    @Test void mergeOverlapAndDeletePreserveRecipes() throws Exception {
        var from = create("From"); var to = create("To"); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        link(a, from.get("id")); link(a, to.get("id")); link(b, from.get("id"));
        mvc.perform(post(ROOT + "/merge").header("Authorization", auth(USER)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("from_id", from.get("id"), "to_id", to.get("id")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recipeCount").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipes_to_tags", Long.class)).isEqualTo(2);
        mvc.perform(delete(ROOT + "/" + to.get("id")).header("Authorization", auth(USER))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipes_to_tags", Long.class)).isZero();
    }
    @Test void permissionsIsolationAndValidation() throws Exception {
        var tag = create("Own"); UUID foreign = UUID.randomUUID();
        jdbc.update("INSERT INTO tags (id, group_id, name, slug) VALUES (?, ?, 'Other', 'other')", dialect.uuid(foreign), dialect.uuid(OTHER));
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT).header("Authorization", auth(READER))).andExpect(status().isOk());
        for (var request : List.of(post(ROOT).content("{\"name\":\"Denied\"}"), put(ROOT + "/" + tag.get("id")).content("{\"name\":\"Denied\"}"), delete(ROOT + "/" + tag.get("id")),
                post(ROOT + "/merge").content(json.writeValueAsString(Map.of("fromId", tag.get("id"), "toId", foreign))))) {
            mvc.perform(request.header("Authorization", auth(READER)).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        }
        mvc.perform(get(ROOT + "/" + foreign).header("Authorization", auth(USER))).andExpect(status().isNotFound());
        mvc.perform(delete(ROOT + "/" + foreign).header("Authorization", auth(USER))).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/merge").header("Authorization", auth(USER)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("fromId", tag.get("id"), "toId", foreign))))
                .andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/").header("Authorization", auth(USER))).andExpect(status().isTemporaryRedirect());
        mvc.perform(post(ROOT).header("Authorization", auth(USER)).contentType(MediaType.APPLICATION_JSON).content("["))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get(ROOT + "/not-a-uuid").header("Authorization", auth(USER))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(ROOT).header("Authorization", auth(USER)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get(ROOT).param("orderBy", "name; DROP TABLE tags").header("Authorization", auth(USER))).andExpect(status().isBadRequest());
    }
    @Test void slugIncludesRecipeSummariesAndNestedOrganizers() throws Exception {
        var tag = create("Recipe tag"); UUID recipe = UUID.randomUUID();
        jdbc.update("INSERT INTO recipes (id, user_id, group_id, name, slug) VALUES (?, ?, ?, 'Soup', 'soup')", dialect.uuid(recipe), dialect.uuid(USER), dialect.uuid(GROUP));
        link(recipe, tag.get("id"));
        mvc.perform(get(ROOT + "/slug/recipe-tag").header("Authorization", auth(USER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipes[0].id").value(recipe.toString())).andExpect(jsonPath("$.recipes[0].tags[0].id").value(tag.get("id")))
                .andExpect(jsonPath("$.recipes[0].tags[0].recipeCount").value(1))
                .andExpect(jsonPath("$.recipes[0].recipeCategory").isArray()).andExpect(jsonPath("$.recipes[0].tools").isArray());
        mvc.perform(get(ROOT).header("Authorization", auth(USER)).param("queryFilter", "recipes.name = \"SOUP\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }
}
