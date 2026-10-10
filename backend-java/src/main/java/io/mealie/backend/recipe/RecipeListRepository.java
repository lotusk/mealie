package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Recipe list selection runs entirely against the shared schema with bound JDBC parameters. */
@Repository
public class RecipeListRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SqlDialect dialect;

    public RecipeListRepository(NamedParameterJdbcTemplate jdbc, SqlDialect dialect) { this.jdbc = jdbc; this.dialect = dialect; }

    Selection select(RecipeListQuery query, AuthUser user) {
        var compiler = new RecipeQueryCompiler(dialect, user);
        List<String> predicates = new ArrayList<>();
        predicates.add("r.group_id=" + compiler.bind(dialect.uuid(user.groupId())));
        predicates.add("EXISTS (SELECT 1 FROM users owner WHERE owner.id=r.user_id AND owner.household_id IS NOT NULL)");
        String cookbookFilter = cookbook(query.cookbook, user);
        if (cookbookFilter != null) {
            if (!cookbookFilter.isEmpty()) predicates.add(compiler.filter(cookbookFilter));
        } else {
            for (String organizer : List.of("categories", "tags", "tools")) {
                List<UUID> ids = resolve(query.values(organizer), organizer);
                String link = "recipes_to_" + organizer;
                String key = organizer.equals("categories") ? "category_id" : organizer.substring(0, organizer.length() - 1) + "_id";
                if (!ids.isEmpty()) {
                    if (query.requireAll.get(organizer)) for (UUID id : ids)
                        predicates.add("EXISTS (SELECT 1 FROM " + link + " l WHERE l.recipe_id=r.id AND l." + key + "=" + compiler.bind(dialect.uuid(id)) + ")");
                    else predicates.add("EXISTS (SELECT 1 FROM " + link + " l WHERE l.recipe_id=r.id AND l." + key + " IN ("
                            + ids.stream().map(id -> compiler.bind(dialect.uuid(id))).collect(java.util.stream.Collectors.joining(",")) + "))");
                }
            }
            List<String> foods = query.values("foods");
            if (!foods.isEmpty()) {
                List<String> foodFilters = new ArrayList<>();
                for (String food : foods) {
                    UUID id = RecipeIdentifier.uuid(food).orElseThrow(() -> new RecipeListQuery.Failure(500, "Internal Server Error"));
                    String bound = compiler.bind(dialect.uuid(id));
                    foodFilters.add("EXISTS (SELECT 1 FROM recipes_ingredients i WHERE i.recipe_id=r.id AND (i.food_id=" + bound
                            + " OR EXISTS (SELECT 1 FROM recipes_ingredients_substitutions s WHERE s.ingredient_id=i.id AND s.substitute_food_id=" + bound + ")))");
                }
                predicates.add("(" + String.join(query.requireAll.get("foods") ? " AND " : " OR ", foodFilters) + ")");
            }
            List<UUID> households = resolve(query.values("households"), "households");
            if (!households.isEmpty()) predicates.add("EXISTS (SELECT 1 FROM users owner WHERE owner.id=r.user_id AND owner.household_id IN ("
                    + households.stream().map(id -> compiler.bind(dialect.uuid(id))).collect(java.util.stream.Collectors.joining(",")) + "))");
        }
        List<String> orders = new ArrayList<>();
        if (query.search != null && !query.search.isEmpty()) search(compiler, query.search, predicates, orders);
        if (query.filter != null && !query.filter.isEmpty()) predicates.add(compiler.filter(query.filter));
        String from = " FROM recipes r WHERE " + String.join(" AND ", predicates);
        Long totalResult = jdbc.queryForObject("SELECT COUNT(*)" + from, compiler.parameters, Long.class);
        long total = totalResult == null ? 0 : totalResult;
        BigInteger all = BigInteger.valueOf(-1);
        BigInteger perPage = query.perPage.equals(all) ? BigInteger.valueOf(total) : query.perPage;
        long totalPages = perPage.signum() == 0 ? 0 : (long) Math.ceil(total / perPage.doubleValue());
        BigInteger page = (query.page.equals(all) ? BigInteger.valueOf(totalPages) : query.page).max(BigInteger.ONE);
        String orderBy = query.orderBy;
        if ((orderBy == null || orderBy.isEmpty()) && (query.search == null || query.search.isEmpty())) orderBy = "created_at";
        if ("random".equals(orderBy)) {
            List<UUID> allIds = ids("SELECT r.id" + from + ordering(orders), compiler.parameters);
            if (!allIds.isEmpty()) {
                int[] ranks = new PythonRandom(query.seed).shuffledRanks(allIds.size());
                StringBuilder random = new StringBuilder("CASE r.id");
                for (int i = 0; i < allIds.size(); i++) random.append(" WHEN ").append(compiler.bind(dialect.uuid(allIds.get(i)))).append(" THEN ").append(ranks[i]);
                orders.add(random.append(" END").toString());
            }
        } else if (orderBy != null && !orderBy.isEmpty()) {
            for (String part : orderBy.split(",", -1)) {
                String segment = part.strip();
                try {
                    String[] pieces = segment.split(":", -1);
                    if (pieces.length > 2) throw new IllegalArgumentException();
                    String direction = pieces.length == 2 ? pieces[1] : query.direction;
                    if (!List.of("asc", "desc").contains(direction)) throw new IllegalArgumentException();
                    orders.add(compiler.order(pieces[0], direction.equals("desc")) + " " + direction.toUpperCase(java.util.Locale.ROOT)
                            + (query.nullPosition == null ? "" : " NULLS " + query.nullPosition.toUpperCase(java.util.Locale.ROOT)));
                } catch (RecipeListQuery.Failure e) {
                    String detail = ((Map<?, ?>) e.body).get("detail").toString();
                    throw RecipeListQuery.Failure.bad("Invalid order_by statement \"" + orderBy + "\": "
                            + (detail.startsWith("Cannot filter on ") ? detail : "\"" + segment + "\" is invalid"));
                } catch (IllegalArgumentException e) {
                    throw RecipeListQuery.Failure.bad("Invalid order_by statement \"" + orderBy + "\": \"" + segment + "\" is invalid");
                }
            }
        }
        // Python's ORJSON response serializer permits only 64-bit integers, including unsigned positive pages.
        if (page.bitLength() > 64) throw new RecipeListQuery.Failure(500, "Internal Server Error");
        String sql = "SELECT r.id" + from + ordering(orders);
        try {
            if (!query.perPage.equals(all)) sql += " LIMIT " + compiler.bind(query.perPage.longValueExact());
            else if (dialect.engine() == DbEngine.SQLITE) sql += " LIMIT -1";
            long offset = page.subtract(BigInteger.ONE).multiply(perPage).longValueExact();
            sql += " OFFSET " + compiler.bind(offset);
        } catch (ArithmeticException overflow) {
            // Python accepts arbitrary-size integers; its DB driver returns a 500 for an oversized LIMIT/OFFSET.
            throw new RecipeListQuery.Failure(500, "Internal Server Error");
        }
        return new Selection(page, perPage, total, totalPages, ids(sql, compiler.parameters));
    }

    private List<UUID> ids(String sql, MapSqlParameterSource parameters) { return jdbc.query(sql, parameters, (rs, n) -> dialect.getUuid(rs, "id")); }
    private static String ordering(List<String> orders) { return orders.isEmpty() ? "" : " ORDER BY " + String.join(",", orders); }

    private String cookbook(String identifier, AuthUser user) {
        if (identifier == null || identifier.isEmpty()) return null;
        var id = RecipeIdentifier.uuid(identifier);
        var parameters = new MapSqlParameterSource("groupId", dialect.uuid(user.groupId()))
                .addValue("identifier", id.<Object>map(dialect::uuid).orElse(identifier));
        List<String> filters = jdbc.query("SELECT query_filter_string FROM cookbooks WHERE group_id=:groupId AND "
                + (id.isPresent() ? "id" : "slug") + "=:identifier", parameters, (rs, n) -> rs.getString(1));
        if (filters.isEmpty()) throw new RecipeListQuery.Failure(404, Map.of("detail", "cookbook not found"));
        return filters.getFirst() == null ? "" : filters.getFirst();
    }

    private List<UUID> resolve(List<String> identifiers, String table) {
        List<UUID> ids = new ArrayList<>();
        List<String> slugs = new ArrayList<>();
        for (String identifier : identifiers) {
            var id = RecipeIdentifier.uuid(identifier);
            if (id.isPresent()) ids.add(id.get()); else slugs.add(identifier);
        }
        if (!slugs.isEmpty()) ids.addAll(jdbc.query("SELECT id FROM " + table + " WHERE slug IN (:slugs)",
                new MapSqlParameterSource("slugs", slugs), (rs, n) -> dialect.getUuid(rs, "id")));
        return ids;
    }

    private void search(RecipeQueryCompiler compiler, String raw, List<String> predicates, List<String> orders) {
        String search = RecipeSearch.normalize(raw);
        if (dialect.engine() == DbEngine.POSTGRES && !RecipeSearch.quoted(raw)) {
            String bound = compiler.bind(search);
            // Python prefetched ingredient matches before lowering the name/description threshold to 0.5.
            List<Integer> ingredientIds = jdbc.query("SELECT id FROM recipes_ingredients WHERE note_normalized %> " + bound
                    + " OR original_text_normalized %> " + bound, compiler.parameters, (rs, n) -> rs.getInt(1));
            jdbc.getJdbcTemplate().execute("SET LOCAL pg_trgm.word_similarity_threshold = 0.5");
            String ingredients = ingredientIds.isEmpty() ? "1=0" : "EXISTS (SELECT 1 FROM recipes_ingredients i WHERE i.recipe_id=r.id AND i.id IN ("
                    + ingredientIds.stream().map(compiler::bind).collect(java.util.stream.Collectors.joining(",")) + "))";
            predicates.add("(r.name_normalized %> " + bound + " OR r.description_normalized %> " + bound + " OR " + ingredients + ")");
            orders.add("r.name_normalized <->> " + bound + " ASC");
        } else {
            List<String> conditions = new ArrayList<>();
            for (String token : RecipeSearch.tokens(search)) {
                String bound = compiler.bind("%" + token + "%");
                conditions.add("r.name_normalized LIKE " + bound);
                conditions.add("r.description_normalized LIKE " + bound);
                conditions.add("EXISTS (SELECT 1 FROM recipes_ingredients i WHERE i.recipe_id=r.id AND (i.note_normalized LIKE "
                        + bound + " OR i.original_text_normalized LIKE " + bound + "))");
            }
            predicates.add(conditions.isEmpty() ? "EXISTS (SELECT 1 FROM recipes_ingredients i WHERE i.recipe_id=r.id)" : "(" + String.join(" OR ", conditions) + ")");
            orders.add("(r.name_normalized LIKE " + compiler.bind("%" + search + "%") + ") DESC");
        }
    }

    record Selection(BigInteger page, BigInteger perPage, long total, long totalPages, List<UUID> ids) { }
}
