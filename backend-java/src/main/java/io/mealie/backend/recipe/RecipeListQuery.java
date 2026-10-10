package io.mealie.backend.recipe;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** FastAPI's camel-case query dependencies and Python-compatible pagination guides. */
final class RecipeListQuery {
    final Map<String, String[]> parameters;
    String orderBy, nullPosition, direction, filter, seed, cookbook, search;
    BigInteger page, perPage;
    final Map<String, Boolean> requireAll = new LinkedHashMap<>();

    RecipeListQuery(Map<String, String[]> parameters) {
        this.parameters = parameters;
        var errors = new ArrayList<String>();
        orderBy = value("orderBy");
        nullPosition = enumeration("orderByNullPosition", null, List.of("first", "last"), errors);
        direction = enumeration("orderDirection", "desc", List.of("asc", "desc"), errors);
        filter = value("queryFilter");
        seed = value("paginationSeed");
        page = integer("page", 1, errors);
        perPage = integer("perPage", 50, errors);
        cookbook = value("cookbook");
        search = value("search");
        for (String key : List.of("categories", "tags", "tools", "foods")) {
            String alias = "requireAll" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            String raw = value(alias);
            boolean result = false;
            if (raw != null) {
                String lowered = raw.toLowerCase(java.util.Locale.ROOT);
                if (Set.of("1", "true", "t", "yes", "y", "on").contains(lowered)) result = true;
                else if (!Set.of("0", "false", "f", "no", "n", "off").contains(lowered))
                    errors.add(validation("bool_parsing", alias, "Input should be a valid boolean, unable to interpret input", raw, null));
            }
            requireAll.put(key, result);
        }
        if (!errors.isEmpty()) {
            var body = new LinkedHashMap<String, Object>();
            body.put("status_code", 422);
            Path root = Path.of(System.getProperty("user.dir"));
            if (root.getFileName() != null && root.getFileName().toString().equals("backend-java")) root = root.getParent();
            String location = root.resolve("mealie/routes/recipe/recipe_crud_routes.py").toString();
            body.put("message", errors.size() + " validation error" + (errors.size() == 1 ? "" : "s") + ": "
                    + String.join(" ", errors) + "  File \"" + location + "\", line 509, in get_all   GET /api/recipes");
            body.put("data", null);
            throw new Failure(422, body);
        }
        if ("random".equals(orderBy) && (seed == null || seed.isEmpty())) {
            String message = "paginationSeed is required when orderBy is random";
            throw new Failure(422, Map.of("detail", List.of("dict: {'type': 'value_error', 'loc': ('paginationSeed',), "
                    + "'msg': 'Value error, " + message + "', 'input': " + repr(seed)
                    + ", 'ctx': {'error': ValueError('" + message + "')}, 'url': 'https://errors.pydantic.dev/2.13/v/value_error'}")));
        }
    }

    static RecipeListQuery from(HttpServletRequest request) { return new RecipeListQuery(request.getParameterMap()); }

    String value(String name) {
        String[] values = parameters.get(name);
        return values == null || values.length == 0 ? null : values[values.length - 1];
    }

    List<String> values(String name) {
        return parameters.containsKey(name) ? Arrays.asList(parameters.get(name)) : List.of();
    }

    String guide(long page) { return guide(BigInteger.valueOf(page)); }

    String guide(BigInteger page) {
        var values = new LinkedHashMap<String, Object>();
        if (orderBy != null) values.put("orderBy", orderBy);
        if (nullPosition != null) values.put("orderByNullPosition", nullPosition);
        values.put("orderDirection", direction);
        if (filter != null) values.put("queryFilter", filter);
        if (seed != null) values.put("paginationSeed", seed);
        values.put("page", this.page);
        values.put("perPage", perPage);
        // Starlette dict(QueryParams) retains the LAST value of repeated parameters, including filter lists.
        parameters.forEach((key, items) -> values.put(camel(key), items[items.length - 1]));
        values.put("page", page);
        return "/recipes?" + values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue().toString()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private BigInteger integer(String name, long fallback, List<String> errors) {
        String raw = value(name);
        if (raw == null) return BigInteger.valueOf(fallback);
        try {
            String cleaned = raw.strip();
            if (!cleaned.matches("[+-]?[0-9]+(?:_[0-9]+)*(?:\\.0+)?")) throw new NumberFormatException();
            return new BigDecimal(cleaned.replace("_", "")).toBigIntegerExact();
        } catch (NumberFormatException e) {
            errors.add(validation("int_parsing", name, "Input should be a valid integer, unable to parse string as an integer", raw, null));
            return BigInteger.valueOf(fallback);
        }
    }

    private String enumeration(String name, String fallback, List<String> allowed, List<String> errors) {
        String raw = value(name);
        if (raw == null) return fallback;
        if (!allowed.contains(raw)) {
            String expected = "'" + allowed.get(0) + "' or '" + allowed.get(1) + "'";
            errors.add(validation("enum", name, "Input should be " + expected, raw, expected));
        }
        return raw;
    }

    private static String validation(String type, String name, String message, String input, String expected) {
        return "{'type': '" + type + "', 'loc': ('query', '" + name + "'), 'msg': " + repr(message)
                + ", 'input': " + repr(input) + (expected == null ? "" : ", 'ctx': {'expected': " + repr(expected) + "}") + "}";
    }

    static String repr(String input) {
        if (input == null) return "None";
        char quote = input.contains("'") && !input.contains("\"") ? '"' : '\'';
        return quote + input.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                .replace(String.valueOf(quote), "\\" + quote) + quote;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%7E", "~").replace("*", "%2A");
    }
    private static String camel(String key) {
        // pyhumps leaves acronyms and leading separators intact, and converts both hyphens and underscores.
        if (isUpper(key) || key.chars().allMatch(Character::isDigit)) return key;
        if (!key.isEmpty() && !isUpper(key.substring(0, Math.min(2, key.length()))))
            key = Character.toLowerCase(key.charAt(0)) + key.substring(1);
        var matcher = java.util.regex.Pattern.compile("(?<=[^\\-_])[\\-_]+[^\\-_]").matcher(key);
        return matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                match.group().substring(match.group().length() - 1).toUpperCase(java.util.Locale.ROOT)));
    }
    private static boolean isUpper(String value) {
        return value.chars().anyMatch(Character::isUpperCase) && value.chars().noneMatch(Character::isLowerCase);
    }

    static class Failure extends RuntimeException {
        final int status;
        final Object body;
        Failure(int status, Object body) { this.status = status; this.body = body; }
        static Failure bad(String message) { return new Failure(400, Map.of("detail", message)); }
    }
}
