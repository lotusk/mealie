package io.mealie.backend.tags;

import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.web.ApiException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Compiles the organizer list's query to allowlisted SQL with bound values. */
final class TagQuery {
    private final SqlDialect dialect;
    final Map<String, Object> sql = new HashMap<>();
    final Map<String, String> params;
    final int requestedPage;
    final int requestedPerPage;

    TagQuery(SqlDialect dialect, UUID groupId, Map<String, String> params) {
        this.dialect = dialect;
        this.params = params;
        sql.put("groupId", dialect.uuid(groupId));
        requestedPage = integer("page", 1);
        requestedPerPage = integer("perPage", 50);
        String direction = params.getOrDefault("orderDirection", "desc");
        if (!List.of("asc", "desc").contains(direction)) throw invalid("Invalid orderDirection");
        String nulls = params.get("orderByNullPosition");
        if (nulls != null && !List.of("first", "last").contains(nulls)) throw invalid("Invalid orderByNullPosition");
        String search = params.get("search");
        boolean searching = search != null && !search.isEmpty();
        List<String> where = new ArrayList<>();
        String searchOrder = null;
        if (searching) {
            search = punctuation(search).strip();
            if (dialect.usesFuzzySearch(search)) {
                where.add(dialect.fuzzySearchPredicate("t.name", bind(search)));
                searchOrder = dialect.fuzzySearchOrder("t.name", bind(search));
            } else {
                List<String> tokens = new ArrayList<>();
                var matcher = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(search);
                while (matcher.find()) tokens.add(matcher.group(1) != null ? matcher.group(1).strip()
                        : matcher.group(2) != null ? matcher.group(2).strip() : matcher.group(3));
                if (!tokens.isEmpty()) where.add("(" + String.join(" OR ", tokens.stream()
                        .map(s -> "t.name LIKE " + bind("%" + s + "%")).toList()) + ")");
                searchOrder = "(t.name LIKE " + bind("%" + search + "%") + ") DESC";
            }
        }
        if (params.containsKey("queryFilter") && !params.get("queryFilter").isBlank()) {
            where.add(new Filter(params.get("queryFilter")).parse());
        }
        if (!where.isEmpty()) sql.put("where", String.join(" AND ", where));
        String orderBy = params.get("orderBy");
        if (orderBy == null && !searching) orderBy = "created_at";
        List<String> orders = new ArrayList<>();
        if (searchOrder != null) orders.add(searchOrder);
        if (orderBy != null && !orderBy.equals("random")) {
            for (String component : orderBy.split(",")) {
                String[] parts = component.strip().split(":", -1);
                String dir = parts.length == 2 ? parts[1] : direction;
                if (parts.length > 2 || !List.of("asc", "desc").contains(dir)) throw bad("Invalid order_by statement \"" + orderBy + "\"");
                String field = field(parts[0]);
                // Python orders all text columns case-insensitively.
                if (textColumn(field)) field = "LOWER(" + field + ")";
                if (field.startsWith("r.") || field.contains("(r.")) {
                    field = "(SELECT " + (dir.equals("desc") ? "MAX" : "MIN") + "(" + field
                            + ") FROM recipes r JOIN recipes_to_tags rt ON rt.recipe_id = r.id WHERE rt.tag_id = t.id)";
                } else if (field.startsWith("g.") || field.contains("(g.")) {
                    field = "(SELECT " + field + " FROM groups g WHERE g.id = t.group_id)";
                }
                orders.add(field + " " + dir.toUpperCase(Locale.ROOT) + (nulls == null ? "" : " NULLS " + nulls.toUpperCase(Locale.ROOT)));
            }
        }
        if (orderBy != null && orderBy.equals("random") && params.getOrDefault("paginationSeed", "").isEmpty()) throw invalid("paginationSeed is required when orderBy is random");
        if (!orders.isEmpty()) sql.put("order", String.join(", ", orders));
    }

    private static String punctuation(String search) {
        return search.replaceAll("[!#$%&()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~]", " ");
    }
    int integer(String key, int fallback) {
        try { return Integer.parseInt(params.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { throw invalid("Invalid integer: " + key); }
    }
    String bind(Object value) {
        String key = "p" + sql.size();
        sql.put(key, value);
        return "#{" + key + "}";
    }
    private boolean textColumn(String column) {
        return List.of("name", "slug", "description", "org_url", "recipe_yield", "total_time", "prep_time", "perform_time")
                .contains(column.substring(column.indexOf('.') + 1));
    }
    private String relation(String column, String condition) {
        if (column.startsWith("r.")) return "EXISTS (SELECT 1 FROM recipes r JOIN recipes_to_tags rt ON rt.recipe_id = r.id"
                + " WHERE rt.tag_id = t.id AND " + condition + ")";
        if (column.startsWith("g.")) return "EXISTS (SELECT 1 FROM groups g WHERE g.id = t.group_id AND " + condition + ")";
        return condition;
    }
    private String field(String name) {
        if (name.startsWith("recipes.") || name.startsWith("group.")) {
            String[] parts = name.split("\\.", 2);
            String column = parts[1].replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            List<String> common = List.of("id", "name", "slug", "created_at", "update_at");
            List<String> recipe = List.of("group_id", "user_id", "description", "rating", "recipe_servings", "recipe_yield_quantity",
                    "recipe_yield", "total_time", "prep_time", "perform_time", "total_time_seconds", "prep_time_seconds",
                    "perform_time_seconds", "date_added", "date_updated", "last_made", "org_url");
            if (!common.contains(column) && !(parts[0].equals("recipes") && recipe.contains(column))) throw bad("Cannot filter on " + name);
            return (parts[0].equals("recipes") ? "r." : "g.") + column;
        }
        return switch (name.strip()) {
            case "id" -> "t.id";
            case "groupId", "group_id" -> "t.group_id";
            case "name" -> "t.name";
            case "slug" -> "t.slug";
            case "createdAt", "created_at" -> "t.created_at";
            case "updatedAt", "updated_at", "updateAt", "update_at" -> "t.update_at";
            default -> throw bad("Cannot filter on " + name);
        };
    }
    static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of()); }
    static ApiException invalid(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, Map.of()); }

    private final class Filter {
        final List<String> tokens = new ArrayList<>();
        int index;
        Filter(String input) {
            var matcher = Pattern.compile("\\s*(\"[^\"]*\"|<>|>=|<=|[=><()\\[\\],]|[^\\s=><()\\[\\],]+)").matcher(input);
            int end = 0;
            while (matcher.find()) { tokens.add(matcher.group(1)); end = matcher.end(); }
            if (!input.substring(end).isBlank()) throw bad("invalid query string");
        }
        String parse() {
            String result = or();
            if (index != tokens.size()) throw bad("invalid query string");
            return result;
        }
        String or() {
            String result = and();
            while (take("OR")) result = "(" + result + " OR " + and() + ")";
            return result;
        }
        String and() {
            String result = atom();
            while (take("AND")) result = "(" + result + " AND " + atom() + ")";
            return result;
        }
        String atom() {
            if (take("(")) { String result = or(); expect(")"); return "(" + result + ")"; }
            String column = field(next());
            String operator = next().toUpperCase(Locale.ROOT);
            if (operator.equals("NOT") || operator.equals("CONTAINS")) operator += " " + next().toUpperCase(Locale.ROOT);
            if (operator.equals("IS") && take("NOT")) operator = "IS NOT";
            if (operator.startsWith("IS")) {
                String value = next();
                if (!value.equalsIgnoreCase("NULL") && !value.equalsIgnoreCase("NONE")) throw bad("invalid query string: IS can only be used with NULL");
                return relation(column, column + " " + operator + " NULL");
            }
            if (operator.equals("IN") || operator.equals("NOT IN") || operator.equals("CONTAINS ALL")) {
                expect("[");
                List<String> values = new ArrayList<>();
                if (!take("]")) {
                    do { values.add(bind(value(column, next()))); } while (take(","));
                    expect("]");
                }
                if (values.isEmpty()) return operator.equals("IN") ? "1 = 0" : "1 = 1";
                if (operator.equals("CONTAINS ALL")) return "(" + String.join(" AND ", values.stream()
                        .map(v -> relation(column, comparable(column) + " = " + v)).toList()) + ")";
                String condition = comparable(column) + " IN (" + String.join(",", values) + ")";
                if (operator.equals("NOT IN") && !column.startsWith("t.")) return "NOT " + relation(column, condition);
                return relation(column, comparable(column) + " " + operator + " (" + String.join(",", values) + ")");
            }
            if (!List.of("=", "<>", ">", "<", ">=", "<=", "LIKE", "NOT LIKE").contains(operator)) throw bad("invalid query string");
            return relation(column, comparable(column) + " " + operator + " " + bind(value(column, next())));
        }
        String comparable(String column) { return textColumn(column) ? "LOWER(" + column + ")" : column; }
        Object value(String column, String token) {
            if (token.startsWith("\"") && token.endsWith("\"")) token = token.substring(1, token.length() - 1);
            try {
                if (column.endsWith(".id") || column.endsWith("_id")) return dialect.uuid(TagRepository.uuid(token));
                if (column.endsWith("_at") || column.endsWith("date_updated") || column.endsWith("last_made")) {
                    LocalDateTime date = token.startsWith("$NOW") ? now(token) : token.length() == 10
                            ? java.time.LocalDate.parse(token).atStartOfDay() : LocalDateTime.parse(token.replace(' ', 'T'));
                    return dialect.timestamp(date.atOffset(ZoneOffset.UTC));
                }
                if (column.endsWith("date_added")) return dialect.date(java.time.LocalDate.parse(token));
                if (!textColumn(column)) return Double.valueOf(token);
                return token.toLowerCase(Locale.ROOT);
            } catch (RuntimeException e) { throw bad("invalid query string: invalid value '" + token + "'"); }
        }
        LocalDateTime now(String token) {
            LocalDateTime date = LocalDateTime.now(ZoneOffset.UTC);
            if (token.equals("$NOW")) return date;
            long amount = Long.parseLong(token.substring(4, token.length() - 1));
            return switch (token.charAt(token.length() - 1)) {
                case 'y' -> date.plusYears(amount); case 'm' -> date.plusMonths(amount); case 'd' -> date.plusDays(amount);
                case 'H' -> date.plusHours(amount); case 'M' -> date.plusMinutes(amount); case 'S' -> date.plusSeconds(amount);
                default -> throw bad("Invalid time unit in NOW string");
            };
        }
        String next() { if (index >= tokens.size()) throw bad("invalid query string"); return tokens.get(index++); }
        boolean take(String token) { if (index < tokens.size() && tokens.get(index).equalsIgnoreCase(token)) { index++; return true; } return false; }
        void expect(String token) { if (!take(token)) throw bad("invalid query string"); }
    }
}
