package io.mealie.backend.recipe;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.db.SqlDialect;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Bound SQL predicates and correlated aggregates over Python's filterable model graph. */
final class RecipeQueryCompiler {
    private static final JsonNode MODELS;
    private static final Pattern ATOM = Pattern.compile("^(.+?)\\s*(>=|<=|<>|=|>|<|\\b(?i:CONTAINS ALL|NOT LIKE|NOT IN|IS NOT|LIKE|IN|IS)\\b)\\s*(.*)$");
    static {
        try (var source = RecipeQueryCompiler.class.getResourceAsStream("/recipe-list/schema.json")) {
            if (source == null) throw new IllegalStateException("Missing recipe filter whitelist");
            MODELS = new ObjectMapper().readTree(source).get("models");
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    final MapSqlParameterSource parameters = new MapSqlParameterSource();
    private final SqlDialect dialect;
    private final AuthUser user;
    private int parameterIndex, aliasIndex;

    RecipeQueryCompiler(SqlDialect dialect, AuthUser user) { this.dialect = dialect; this.user = user; }

    String bind(Object value) { String key = "q" + parameterIndex++; parameters.addValue(key, value); return ":" + key; }

    String filter(String value) {
        if (value == null || value.isEmpty()) return "1=1";
        List<String> parts = tokenize(value);
        int[] cursor = {0};
        String sql = expression(parts, cursor);
        if (cursor[0] != parts.size()) throw RecipeListQuery.Failure.bad("invalid query string: parenthesis are unbalanced");
        return sql;
    }

    private String expression(List<String> parts, int[] cursor) {
        List<String> operands = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        while (cursor[0] < parts.size() && !parts.get(cursor[0]).equals(")")) {
            String part = parts.get(cursor[0]++);
            if (part.equals("(")) {
                operands.add(expression(parts, cursor));
                if (cursor[0] >= parts.size() || !parts.get(cursor[0]++).equals(")"))
                    throw RecipeListQuery.Failure.bad("invalid query string: parenthesis are unbalanced");
            } else if (part.equalsIgnoreCase("AND") || part.equalsIgnoreCase("OR")) operators.add(part.toUpperCase(Locale.ROOT));
            else {
                String condition = atom(part);
                if (condition != null) operands.add(condition);
            }
        }
        if (operands.isEmpty()) return "NULL"; // Python ignores bare words and filters on NULL.
        String result = operands.getLast();
        for (int i = operands.size() - 2; i >= 0; i--) {
            if (operators.isEmpty()) throw new RecipeListQuery.Failure(500, "Internal Server Error");
            result = "(" + operands.get(i) + " " + operators.removeLast() + " " + result + ")";
        }
        return result;
    }

    private String atom(String term) {
        var match = ATOM.matcher(term);
        if (!match.matches()) return null;
        String attr = match.group(1).strip(), operator = match.group(2).toUpperCase(Locale.ROOT), raw = match.group(3).strip();
        boolean list = raw.startsWith("[") && raw.endsWith("]");
        if (List.of("IN", "NOT IN", "CONTAINS ALL").contains(operator) && !list)
            throw RecipeListQuery.Failure.bad("invalid query string: " + operator + " must be given a list of valuesenclosed by [ and ]");
        if ((operator.equals("IS") || operator.equals("IS NOT")) && !List.of("null", "none").contains(unquote(raw).toLowerCase(Locale.ROOT)))
            throw RecipeListQuery.Failure.bad("invalid query string: \"" + operator + "\" can only be used with \"NULL\", not \"" + unquote(raw) + "\"");
        Field field = resolve(attr);
        String column = transform(field);
        List<Object> values = new ArrayList<>();
        if (list) for (String item : raw.substring(1, raw.length() - 1).split(",", -1)) values.add(convert(field.kind, unquote(item.strip()), operator));
        else if (!operator.startsWith("IS")) values.add(convert(field.kind, unquote(raw), operator));
        if (operator.equals("CONTAINS ALL") && values.size() != 1)
            return "(" + values.stream().map(v -> wrap(field, column + " = " + bind(v))).collect(java.util.stream.Collectors.joining(" AND ")) + ")";
        if (List.of("IN", "NOT IN", "CONTAINS ALL").contains(operator)) {
            String in = column + " IN (" + values.stream().map(this::bind).collect(java.util.stream.Collectors.joining(",")) + ")";
            if (operator.equals("NOT IN") && !field.relations.isEmpty()) return "NOT (" + wrap(field, in) + ")";
            return wrap(field, (operator.equals("NOT IN") ? "NOT (" + in + ")" : in));
        }
        String predicate = operator.startsWith("IS") ? column + " " + operator + " NULL"
                : column + " " + operator + " " + bind(values.getFirst());
        return wrap(field, predicate);
    }

    String order(String attribute, boolean descending) {
        Field field = resolve(attribute);
        String column = transform(field);
        if (field.relations.isEmpty()) return column;
        List<String> tables = new ArrayList<>(), joins = new ArrayList<>();
        for (Relation relation : field.relations) {
            tables.add(relation.table + " " + relation.target);
            if (relation.secondary != null) tables.add(relation.secondary + " " + relation.link);
            joins.add(relation.join);
        }
        return "(SELECT " + (descending ? "MAX" : "MIN") + "(" + column + ") FROM "
                + String.join(",", tables) + " WHERE " + String.join(" AND ", joins) + ")";
    }

    private String transform(Field field) { return field.kind.equals("string") ? "LOWER(" + field.column + ")" : field.column; }

    private Field resolve(String original) {
        String attribute = snake(original);
        String[] parts = attribute.split("\\.", -1);
        JsonNode model = MODELS.get("RecipeModel");
        String alias = "r";
        List<Relation> relations = new ArrayList<>();
        List<String> path = new ArrayList<>(List.of(parts));
        for (int i = 0; i < path.size(); i++) {
            String name = path.get(i);
            JsonNode proxy = model.get("proxies").get(name);
            if (proxy != null) {
                path.set(i, proxy.get(0).asText());
                path.add(i + 1, proxy.get(1).asText());
                name = path.get(i);
            }
            if (i == path.size() - 1) {
                JsonNode column = model.get("columns").get(name);
                if (column == null) {
                    JsonNode relation = model.get("relations").get(name);
                    if (relation != null) throw RecipeListQuery.Failure.bad("Cannot filter on " + relation.get("display").asText());
                    throw missing(original);
                }
                if (!column.get("filterable").asBoolean()) throw RecipeListQuery.Failure.bad("Cannot filter on " + column.get("display").asText());
                String expression = alias + "." + column.get("name").asText();
                if (name.equals("rating")) {
                    String id = bind(dialect.uuid(user.id()));
                    expression = "CAST(CASE WHEN EXISTS (SELECT 1 FROM users_to_recipes ur WHERE ur.recipe_id=r.id AND ur.user_id=" + id
                            + " AND ur.rating>0) THEN (SELECT MAX(ur.rating) FROM users_to_recipes ur WHERE ur.recipe_id=r.id AND ur.user_id="
                            + id + ") ELSE NULLIF(r.rating,0) END AS " + (dialect.engine().name().equals("POSTGRES") ? "FLOAT" : "REAL") + ")";
                    relations.clear();
                } else if (name.equals("last_made")) {
                    expression = "COALESCE((SELECT hr.last_made FROM households_to_recipes hr WHERE hr.recipe_id=r.id AND hr.household_id="
                            + bind(dialect.uuid(user.householdId())) + ")," + bind(dialect.timestamp(OffsetDateTime.parse("1900-01-01T00:00:00Z"))) + ")";
                    relations.clear();
                }
                return new Field(expression, column.get("kind").asText(), relations);
            }
            JsonNode relationship = model.get("relations").get(name);
            if (relationship == null) throw missing(original);
            JsonNode next = MODELS.get(relationship.get("model").asText());
            String target = "f" + aliasIndex++, link = "f" + aliasIndex++;
            String secondary = relationship.get("secondary").isNull() ? null : relationship.get("secondary").asText();
            String join = replace(relationship.get("join").asText(), alias, target, link);
            if (secondary != null) join += " AND " + replace(relationship.get("secondaryJoin").asText(), alias, target, link);
            relations.add(new Relation(next.get("table").asText(), alias, target, secondary, link, join));
            model = next; alias = target;
        }
        throw missing(original);
    }

    private static String replace(String sql, String parent, String target, String link) {
        return sql.replace("{parent}", parent).replace("{target}", target).replace("{link}", link);
    }

    private static String wrap(Field field, String predicate) {
        for (int i = field.relations.size() - 1; i >= 0; i--) {
            Relation relation = field.relations.get(i);
            predicate = "EXISTS (SELECT 1 FROM " + relation.table + " " + relation.target
                    + (relation.secondary == null ? "" : "," + relation.secondary + " " + relation.link)
                    + " WHERE " + relation.join + " AND " + predicate + ")";
        }
        return predicate;
    }

    private Object convert(String kind, String raw, String operator) {
        if ((operator.equals("LIKE") || operator.equals("NOT LIKE")) && !kind.equals("string"))
            throw RecipeListQuery.Failure.bad("invalid query string: \"" + operator + "\" can only be used with string columns");
        final String value = now(raw);
        return switch (kind) {
            case "string" -> value.toLowerCase(Locale.ROOT);
            case "uuid" -> dialect.uuid(RecipeIdentifier.uuid(value).orElseThrow(() -> RecipeListQuery.Failure.bad("invalid query string: invalid UUID '" + value + "'")));
            case "bool" -> {
                if (value.isEmpty()) throw RecipeListQuery.Failure.bad("invalid query string");
                yield dialect.bool("ty".indexOf(Character.toLowerCase(value.charAt(0))) >= 0 || value.equals("1"));
            }
            case "date", "datetime" -> {
                try {
                    OffsetDateTime dt = RecipeFilterDate.parse(value);
                    yield kind.equals("date") ? dialect.date(dt.toLocalDate()) : dialect.timestamp(dt);
                } catch (java.time.DateTimeException e) {
                    throw RecipeListQuery.Failure.bad("invalid query string: unknown date or datetime format '" + value + "'");
                }
            }
            default -> value;
        };
    }

    private static String now(String raw) {
        if (!raw.startsWith("$NOW")) return raw;
        LocalDateTime now = LocalDateTime.now();
        String rest = raw.substring(4);
        if (!rest.isEmpty()) {
            if (rest.length() < 3) throw RecipeListQuery.Failure.bad("Invalid remainder in NOW string (" + raw + ")");
            int amount;
            try { amount = Integer.parseInt(rest.substring(1, rest.length() - 1)); }
            catch (NumberFormatException e) { throw RecipeListQuery.Failure.bad("Invalid amount in NOW string (" + raw + ")"); }
            if (rest.charAt(0) == '-') amount = -amount;
            else if (rest.charAt(0) != '+') throw RecipeListQuery.Failure.bad("Invalid operator in NOW string (" + raw + ")");
            now = switch (rest.charAt(rest.length() - 1)) {
                case 'y' -> now.plusYears(amount); case 'm' -> now.plusMonths(amount); case 'd' -> now.plusDays(amount);
                case 'H' -> now.plusHours(amount); case 'M' -> now.plusMinutes(amount); case 'S' -> now.plusSeconds(amount);
                default -> throw RecipeListQuery.Failure.bad("Invalid time unit in NOW string (" + raw + ")");
            };
        }
        return now.toString();
    }

    private static List<String> tokenize(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder term = new StringBuilder();
        boolean quoted = false; int list = 0, depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') quoted = !quoted;
            if (!quoted) {
                if (c == '[') list++;
                if (c == ']') list--;
                if (list == 0) {
                    if (c == '(' || c == ')') {
                        flush(result, term); result.add(String.valueOf(c)); depth += c == '(' ? 1 : -1;
                        if (depth < 0) throw RecipeListQuery.Failure.bad("invalid query string: parenthesis are unbalanced");
                        continue;
                    }
                    var logical = Pattern.compile("(?i)^(AND|OR)\\b").matcher(value.substring(i));
                    if ((i == 0 || !Character.isLetterOrDigit(value.charAt(i - 1)) && value.charAt(i - 1) != '_') && logical.find()) {
                        flush(result, term); result.add(logical.group()); i += logical.group().length() - 1; continue;
                    }
                }
            }
            term.append(c);
        }
        flush(result, term);
        if (depth != 0) throw RecipeListQuery.Failure.bad("invalid query string: parenthesis are unbalanced");
        return result;
    }

    private static void flush(List<String> result, StringBuilder term) {
        if (!term.toString().isBlank()) result.add(term.toString().strip());
        term.setLength(0);
    }
    private static String unquote(String value) { return value.length() > 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value; }
    private static String snake(String value) {
        return value.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
    private static RecipeListQuery.Failure missing(String attr) { return RecipeListQuery.Failure.bad("invalid attribute string: '" + attr + "' does not exist on this schema"); }
    private record Relation(String table, String parent, String target, String secondary, String link, String join) { }
    private record Field(String column, String kind, List<Relation> relations) { }
}
