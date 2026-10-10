package io.mealie.backend.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class RecipeListTest {
    private static final UUID GROUP = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID HOME = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private final SqlDialect dialect = SqlDialect.forEngine(DbEngine.SQLITE);
    private final AuthUser user = new AuthUser(USER, "tester", GROUP, HOME, false);
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void fixture() throws Exception {
        var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
        var sql = jdbc.getJdbcTemplate();
        sql.execute("CREATE TABLE recipes (id TEXT PRIMARY KEY,group_id TEXT,user_id TEXT,name TEXT,rating REAL,last_made TEXT,created_at TEXT,recipe_servings REAL)");
        sql.execute("CREATE TABLE users (id TEXT PRIMARY KEY,household_id TEXT)");
        sql.execute("CREATE TABLE tags (id TEXT PRIMARY KEY,name TEXT,slug TEXT)");
        sql.execute("CREATE TABLE recipes_to_tags (recipe_id TEXT,tag_id TEXT)");
        sql.execute("CREATE TABLE users_to_recipes (recipe_id TEXT,user_id TEXT,rating REAL,is_favorite INTEGER)");
        sql.execute("CREATE TABLE households_to_recipes (recipe_id TEXT,household_id TEXT,last_made TEXT)");
        sql.update("INSERT INTO users VALUES (?,?)", dialect.uuid(USER), dialect.uuid(HOME));
        for (int i = 1; i <= 4; i++) sql.update("INSERT INTO recipes VALUES (?,?,?,?,?,?,?,?)", Integer.toString(i),
                dialect.uuid(GROUP), dialect.uuid(USER), "Recipe " + i, i == 1 ? null : i, null, "2026-01-0" + i, i);
        sql.update("INSERT INTO tags VALUES ('a','Alpha','alpha'),('b','Beta','beta')");
        sql.update("INSERT INTO recipes_to_tags VALUES ('1','a'),('1','b'),('2','a'),('3','b')");
        sql.update("INSERT INTO users_to_recipes VALUES ('1',?,5,1)", dialect.uuid(USER));
    }

    @AfterEach
    void closeDatabase() { ((SingleConnectionDataSource) jdbc.getJdbcTemplate().getDataSource()).destroy(); }

    private List<String> matching(String filter) {
        var compiler = new RecipeQueryCompiler(dialect, user);
        return jdbc.query("SELECT r.id FROM recipes r WHERE " + compiler.filter(filter) + " ORDER BY r.id",
                compiler.parameters, (rs, n) -> rs.getString(1));
    }

    @Test
    void independentRelationshipConditionsAndAllMembership() {
        assertThat(matching("tags.name = \"Alpha\" AND tags.name = \"Beta\"")).containsExactly("1");
        assertThat(matching("tags.name CONTAINS ALL [\"Alpha\",\"Beta\"]")).containsExactly("1");
        assertThat(matching("tags.name NOT IN [\"Alpha\"]")).containsExactly("3", "4");
    }

    @Test
    void logicalOperatorsHavePythonRightAssociativityAndParentheses() {
        assertThat(matching("recipeServings = 1 AND recipeServings = 2 OR recipeServings = 4")).isEmpty();
        assertThat(matching("(recipeServings = 1 AND recipeServings = 2) OR recipeServings = 4")).containsExactly("4");
        assertThat(matching("name = \"Recipe AND OR (1)\"")).isEmpty();
    }

    @Test
    void ratingUsesTheAuthenticatedUserWithoutChangingTheProjection() {
        assertThat(matching("rating > 4")).containsExactly("1");
        assertThat(matching("favoritedBy.id = \"" + USER + "\"")).containsExactly("1");
    }

    @Test
    void rejectsPrivateAttributesAndBindsInjectionStrings() {
        assertThatThrownBy(() -> matching("user.email = \"secret\""))
                .isInstanceOf(RecipeListQuery.Failure.class).satisfies(e -> assertThat(((RecipeListQuery.Failure) e).body)
                        .isEqualTo(Map.of("detail", "Cannot filter on User.email")));
        assertThatThrownBy(() -> matching("user.password = \"secret\"")).isInstanceOf(RecipeListQuery.Failure.class);
        assertThat(matching("name = \"x' OR 1=1 --\"")).isEmpty();
        assertThat(matching("recipeServings >= 1")).hasSize(4);
    }

    @Test
    void nestedOrderingAggregatesWithoutDuplicatingRecipes() {
        var compiler = new RecipeQueryCompiler(dialect, user);
        String order = compiler.order("tags.name", false);
        assertThat(jdbc.query("SELECT r.id FROM recipes r ORDER BY " + order + " ASC NULLS LAST,r.id",
                compiler.parameters, (rs, n) -> rs.getString(1))).containsExactly("1", "2", "3", "4");
    }

    @Test
    void paginationLinksRetainPythonDefaultsLastRepeatedValueAndEncoding() {
        var parameters = new LinkedHashMap<String, String[]>();
        parameters.put("tags", new String[]{"first", "second"});
        parameters.put("search", new String[]{"a b&?*~"});
        parameters.put("perPage", new String[]{"2"});
        parameters.put("_searchSeed", new String[]{"frontend"});
        parameters.put("__search_seed", new String[]{"double"});
        parameters.put("OrderBy", new String[]{"unknown"});
        parameters.put("unknown-name", new String[]{"kept"});
        assertThat(new RecipeListQuery(parameters).guide(2))
                .isEqualTo("/recipes?orderDirection=desc&page=2&perPage=2&tags=second&search=a+b%26%3F%2A~&_searchSeed=frontend&__searchSeed=double&orderBy=unknown&unknownName=kept");
    }

    @Test
    void validationAndRandomSeedRequirementsMatchTheOracle() {
        assertThatThrownBy(() -> new RecipeListQuery(Map.of("orderBy", new String[]{"random"})))
                .isInstanceOf(RecipeListQuery.Failure.class).satisfies(e -> assertThat(((RecipeListQuery.Failure) e).status).isEqualTo(422));
        assertThat(new RecipeListQuery(Map.of("page", new String[]{"1.0"})).page).isEqualTo(java.math.BigInteger.ONE);
        assertThatThrownBy(() -> new RecipeListQuery(Map.of("perPage", new String[]{"bad"}))).isInstanceOf(RecipeListQuery.Failure.class);
    }

    @Test
    void stringSeedRandomPermutationMatchesCPythonIncludingUnicode() {
        assertThat(new PythonRandom("seed-重庆").shuffledRanks(12)).containsExactly(1, 11, 2, 8, 10, 0, 4, 3, 9, 7, 6, 5);
    }

    @Test
    void dateFiltersAcceptPythonFormatsAndRejectInvalidCalendarDates() {
        assertThat(RecipeFilterDate.parse("2 january 2026 1:30 PM").toString()).isEqualTo("2026-01-02T13:30Z");
        assertThat(RecipeFilterDate.parse("01/02/26").toLocalDate().toString()).isEqualTo("2026-01-02");
        assertThat(RecipeFilterDate.parse("Fri, 2 Jan 2026 13:00:00 GMT").toString()).isEqualTo("2026-01-02T13:00Z");
        for (String value : List.of("Jan 2nd 2026", "2026 Jan 2", "Jan 2, 26", "2-Jan-26", "2026.01.02"))
            assertThat(RecipeFilterDate.parse(value).toLocalDate().toString()).isEqualTo("2026-01-02");
        assertThat(RecipeFilterDate.parse("2026-01-02 at 5pm").toString()).isEqualTo("2026-01-02T17:00Z");
        assertThat(RecipeFilterDate.parse("31/01/2026").toLocalDate().toString()).isEqualTo("2026-01-31");
        assertThat(RecipeFilterDate.parse("Jan 2nd of 2026").toString()).isEqualTo("2026-01-02T00:00Z");
        assertThat(RecipeFilterDate.parse("2026-01-02 5h30m").toString()).isEqualTo("2026-01-02T05:30Z");
        assertThat(RecipeFilterDate.parse("20260102T130000").toString()).isEqualTo("2026-01-02T13:00Z");
        assertThat(RecipeFilterDate.parse("2026-01-02 13:00 PST").toString()).isEqualTo("2026-01-02T13:00Z");
        assertThat(RecipeFilterDate.parse("2026-01-02 13:00 GMT+3").toString()).isEqualTo("2026-01-02T13:00-03:00");
        assertThat(RecipeFilterDate.parse("2026-01-02 5.5h").toString()).isEqualTo("2026-01-02T05:30Z");
        assertThat(RecipeFilterDate.parse("Friday").toLocalDate().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.FRIDAY);
        assertThatThrownBy(() -> RecipeFilterDate.parse("2026-02-30")).isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    void searchNormalizationAndQuotedPhrasesMatchPython() {
        assertThat(RecipeSearch.normalize("Café 重庆 — crème 🍰")).isEqualTo("cafe zhong qing  -- creme");
        assertThat(RecipeSearch.tokens(RecipeSearch.normalize("\"basil parsley\" cake"))).containsExactly("basil parsley", "cake");
        assertThat(RecipeSearch.quoted("'tomato soup'")).isTrue();
    }
}
