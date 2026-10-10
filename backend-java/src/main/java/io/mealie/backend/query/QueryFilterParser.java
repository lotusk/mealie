package io.mealie.backend.query;

import io.mealie.backend.compat.PyStr;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Mealie's query filter language, e.g. {@code name LIKE "%cake%" AND (slug IN [a, b] OR createdAt > $NOW-7d)}.
 *
 * <p>This is a line-by-line port of the parsing half of QueryFilterBuilder in
 * mealie/services/query_filter/builder.py (plus keywords.py and operators.py), quirks included: the same input must
 * be accepted, rejected or misread exactly as Python does. Python's ValueErrors become {@link QueryFilterException}
 * (a 400 with the same message); its IndexErrors and other crashes become an {@link IllegalStateException} (a 500).
 */
final class QueryFilterParser {

    enum LogicalOperator { AND, OR }

    /** One relational statement: {@code attribute relationship value}. */
    record Component(String attributeName, String relationship, Object rawValue, Object value) {
    }

    static final List<String> RELATIONAL_OPERATORS = List.of("=", "<>", ">", "<", ">=", "<=");
    static final List<String> RELATIONAL_KEYWORDS =
            List.of("IS", "IS NOT", "IN", "NOT IN", "CONTAINS ALL", "LIKE", "NOT LIKE");
    private static final List<String> OPERATORS_LONGEST_FIRST = longestFirst(RELATIONAL_OPERATORS);
    private static final List<String> KEYWORDS_LONGEST_FIRST = longestFirst(RELATIONAL_KEYWORDS);
    private static final Pattern LOGICAL_OPERATORS = Pattern.compile("(?iU)(\\bAND\\b|\\bOR\\b)");

    private QueryFilterParser() {
    }

    /** Filter components: "(", ")", {@link LogicalOperator} and {@link Component}. */
    static List<Object> parse(String filter) {
        List<String> components = breakIntoComponents(filter);
        List<Object> baseComponents = breakIntoBaseComponents(components);
        validateParenthesis(baseComponents);
        return parseFilterComponents(baseComponents);
    }

    private static List<String> longestFirst(List<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort((a, b) -> b.length() - a.length());
        return List.copyOf(sorted);
    }

    private static boolean isGroupSeparator(Object value) {
        return "(".equals(value) || ")".equals(value);
    }

    private static void validateParenthesis(List<Object> baseComponents) {
        int depth = 0;
        for (Object component : baseComponents) {
            if ("(".equals(component)) {
                depth++;
            } else if (")".equals(component)) {
                depth--;
                if (depth < 0) {
                    throw new QueryFilterException("invalid query string: parenthesis are unbalanced");
                }
            }
        }
        if (depth != 0) {
            throw new QueryFilterException("invalid query string: parenthesis are unbalanced");
        }
    }

    /** _break_filter_string_into_components: split at parentheses outside of quotes, until nothing changes. */
    static List<String> breakIntoComponents(String filter) {
        List<String> components = List.of(filter);
        boolean inQuotes = false;
        while (true) {
            List<String> subcomponents = new ArrayList<>();
            for (String component : components) {
                if (isGroupSeparator(component)) {
                    subcomponents.add(component);
                    continue;
                }
                StringBuilder current = new StringBuilder();
                for (int i = 0; i < component.length(); i++) {
                    char c = component.charAt(i);
                    if (c == '"') {
                        inQuotes = !inQuotes;
                    }
                    if ((c == '(' || c == ')') && !inQuotes) {
                        if (!current.isEmpty()) {
                            subcomponents.add(current.toString());
                        }
                        subcomponents.add(String.valueOf(c));
                        current.setLength(0);
                        continue;
                    }
                    current.append(c);
                }
                if (!current.isEmpty()) {
                    subcomponents.add(PyStr.strip(current.toString()));
                }
            }
            if (components.equals(subcomponents)) {
                return components;
            }
            components = subcomponents;
        }
    }

    /** _break_components_into_base_components: split out lists, quoted values, logical and relational operators. */
    static List<Object> breakIntoBaseComponents(List<String> components) {
        boolean inList = false;
        List<Object> baseComponents = new ArrayList<>();
        List<List<String>> listValueComponents = new ArrayList<>();
        for (String component : components) {
            List<String> listParts = split(component, "[");
            for (int i = 1; i < listParts.size(); i++) {
                List<String> values = split(listParts.get(i), "]");
                for (int j = 0; j < values.size(); j += 2) {
                    List<String> items = new ArrayList<>();
                    for (String item : split(values.get(j), ",")) {
                        items.add(PyStr.strip(item));
                    }
                    listValueComponents.add(items);
                }
            }

            int quoteOffset = 0;
            List<String> subcomponents = new ArrayList<>(split(component, "\""));
            for (int i = 0; i < subcomponents.size(); i++) {
                String subcomponent = subcomponents.get(i);
                if (inList) {
                    if (subcomponent.contains("]")) {
                        if (listValueComponents.isEmpty()) {
                            throw new IllegalStateException("pop from empty list");
                        }
                        baseComponents.add(listValueComponents.removeFirst());
                        subcomponent = PyStr.strip(subcomponent.substring(subcomponent.indexOf(']') + 1));
                        inList = false;
                    } else {
                        continue;
                    }
                }

                if (isGroupSeparator(subcomponent)) {
                    quoteOffset++;
                    baseComponents.add(subcomponent);
                    continue;
                }

                if ((i + quoteOffset) % 2 != 0) {
                    baseComponents.add("\"" + PyStr.strip(subcomponent) + "\"");
                    continue;
                }

                if (subcomponent.isEmpty()) {
                    continue;
                }

                if (!inList && subcomponent.contains("[")) {
                    int open = subcomponent.indexOf('[');
                    String rest = subcomponent.substring(open + 1);
                    subcomponent = PyStr.strip(subcomponent.substring(0, open));
                    subcomponents.add(i + 1, rest);
                    quoteOffset++;
                    inList = true;
                }

                for (String part : splitKeepingLogicalOperators(subcomponent)) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    List<String> parsed = parseRelationalOperator(part);
                    if (parsed == null) {
                        parsed = parseRelationalKeyword(part);
                    }
                    if (parsed != null) {
                        baseComponents.addAll(parsed);
                    } else {
                        baseComponents.add(part);
                    }
                }
            }
        }
        return baseComponents;
    }

    /** {@code [x.strip() for x in LOGICAL_OPERATORS.split(s) if x]}: re.split keeps the captured operators. */
    private static List<String> splitKeepingLogicalOperators(String value) {
        List<String> parts = new ArrayList<>();
        Matcher m = LOGICAL_OPERATORS.matcher(value);
        int last = 0;
        while (m.find()) {
            parts.add(value.substring(last, m.start()));
            parts.add(m.group(1));
            last = m.end();
        }
        parts.add(value.substring(last));
        return parts.stream().filter(p -> !p.isEmpty()).map(PyStr::strip).toList();
    }

    /** RelationalOperator.parse_component. */
    static List<String> parseRelationalOperator(String component) {
        for (String operator : OPERATORS_LONGEST_FIRST) {
            if (!component.contains(operator)) {
                continue;
            }
            List<String> parsed = new ArrayList<>();
            for (String part : split(component, operator)) {
                if (!part.isEmpty()) {
                    parsed.add(PyStr.strip(part));
                }
            }
            parsed.add(Math.min(1, parsed.size()), operator);
            return parsed;
        }
        return null;
    }

    /** RelationalKeyword.parse_component. */
    static List<String> parseRelationalKeyword(String component) {
        List<String> parsed = splitWhitespace(component, 1);
        if (parsed.size() < 2) {
            return null;
        }
        String keyword = matchKeyword(parsed.get(1));
        if (keyword != null) {
            return List.of(parsed.get(0), keyword);
        }
        List<String> keywordAndValue = rsplitWhitespace(parsed.getLast(), 1);
        if (keywordAndValue.size() != 2) {
            return null;
        }
        keyword = matchKeyword(keywordAndValue.get(0));
        return keyword == null ? null : List.of(parsed.get(0), keyword, keywordAndValue.get(1));
    }

    private static String matchKeyword(String candidate) {
        String possible = PyStr.lower(PyStr.strip(candidate));
        for (String keyword : KEYWORDS_LONGEST_FIRST) {
            if (PyStr.lower(keyword).equals(possible)) {
                return keyword;
            }
        }
        return null;
    }

    /** _parse_base_components_into_filter_components. */
    static List<Object> parseFilterComponents(List<Object> baseComponents) {
        List<Object> components = new ArrayList<>();
        for (int i = 0; i < baseComponents.size(); i++) {
            Object base = baseComponents.get(i);
            if (base instanceof List<?>) {
                continue;
            }
            String value = (String) base;
            if (isGroupSeparator(value)) {
                components.add(value);
            } else if (RELATIONAL_KEYWORDS.contains(value) || RELATIONAL_OPERATORS.contains(value)) {
                // Python indexes base_components[i - 1] and [i + 1]: -1 wraps around, past the end is an IndexError.
                Object attribute = baseComponents.get(i == 0 ? baseComponents.size() - 1 : i - 1);
                if (i + 1 >= baseComponents.size()) {
                    throw new IllegalStateException("list index out of range");
                }
                if (!(attribute instanceof String attributeName)) {
                    throw new IllegalStateException("attribute name is not a string: " + attribute);
                }
                components.add(component(attributeName, value, baseComponents.get(i + 1)));
            } else if (value.toUpperCase(Locale.ROOT).equals("AND") || value.toUpperCase(Locale.ROOT).equals("OR")) {
                components.add(LogicalOperator.valueOf(value.toUpperCase(Locale.ROOT)));
            }
        }
        return components;
    }

    /** QueryFilterBuilderComponent.__init__. */
    private static Component component(String attributeName, String relationship, Object value) {
        if (value instanceof String s) {
            value = stripQuotes(s);
        } else if (value instanceof List<?> list) {
            value = list.stream().map(v -> stripQuotes((String) v)).toList();
        }

        if (List.of("IN", "NOT IN", "CONTAINS ALL").contains(relationship) && !(value instanceof List<?>)) {
            throw new QueryFilterException("invalid query string: " + relationship
                    + " must be given a list of valuesenclosed by [ and ]");
        }

        Object rawValue;
        if (relationship.equals("IS") || relationship.equals("IS NOT")) {
            if (!(value instanceof String s) || !List.of("null", "none").contains(PyStr.lower(s))) {
                throw new QueryFilterException("invalid query string: \"" + relationship
                        + "\" can only be used with \"NULL\", not \"" + pyStr(value) + "\"");
            }
            rawValue = null;
        } else {
            rawValue = value;
        }
        return new Component(Humps.decamelize(attributeName), relationship, rawValue, placeholders(rawValue));
    }

    private static String stripQuotes(String value) {
        return value.length() > 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static String pyStr(Object value) {
        if (value instanceof List<?> list) {
            return io.mealie.backend.compat.PyRepr.repr(list);
        }
        return String.valueOf(value);
    }

    // -- placeholders (keywords.py) -----------------------------------------------------------------------------

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    /** PlaceholderKeyword.parse_value: replaces "$NOW[+-]<n><unit>" with a local, naive ISO timestamp. */
    private static Object placeholders(Object value) {
        if (value instanceof List<?> list) {
            return list.isEmpty() ? list : list.stream().map(v -> (Object) parseNow((String) v)).toList();
        }
        return value instanceof String s ? parseNow(s) : value;
    }

    private static final Pattern PY_INT = Pattern.compile("[+-]?[0-9]+(?:_[0-9]+)*");

    private static String parseNow(String value) {
        if (value.isEmpty() || !value.startsWith("$NOW")) {
            return value;
        }
        LocalDateTime now = LocalDateTime.now();
        String remainder = value.substring(4);
        LocalDateTime dt = now;
        if (!remainder.isEmpty()) {
            if (remainder.length() < 3) {
                throw new QueryFilterException("Invalid remainder in NOW string (" + value + ")");
            }
            char op = remainder.charAt(0);
            String amountText = PyStr.strip(remainder.substring(1, remainder.length() - 1));
            char unit = remainder.charAt(remainder.length() - 1);
            if (!PY_INT.matcher(amountText).matches()) {
                throw new QueryFilterException("Invalid amount in NOW string (" + value + ")");
            }
            long amount = Long.parseLong(amountText.replace("_", "").replaceFirst("^\\+", ""));
            if (op == '-') {
                amount = -amount;
            } else if (op != '+') {
                throw new QueryFilterException("Invalid operator in NOW string (" + value + ")");
            }
            dt = switch (unit) {
                case 'y' -> now.plusYears(amount);
                case 'm' -> now.plusMonths(amount);
                case 'd' -> now.plusDays(amount);
                case 'H' -> now.plusHours(amount);
                case 'M' -> now.plusMinutes(amount);
                case 'S' -> now.plusSeconds(amount);
                default -> throw new QueryFilterException("Invalid time unit in NOW string (" + value + ")");
            };
        }
        int micros = dt.getNano() / 1000;
        return micros == 0 ? ISO_SECONDS.format(dt) : ISO_MICROS.format(dt.withNano(micros * 1000));
    }

    // -- Python str.split / rsplit ------------------------------------------------------------------------------

    /** {@code s.split(sep)}: a literal separator, keeping empty strings. */
    static List<String> split(String s, String sep) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = s.indexOf(sep, start)) >= 0) {
            parts.add(s.substring(start, index));
            start = index + sep.length();
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** {@code s.split(maxsplit=n)}. */
    static List<String> splitWhitespace(String s, int maxsplit) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && PyStr.isSpace(s.charAt(i))) {
                i++;
            }
            if (i == n) {
                break;
            }
            if (parts.size() == maxsplit) {
                parts.add(s.substring(i));
                return parts;
            }
            int j = i;
            while (j < n && !PyStr.isSpace(s.charAt(j))) {
                j++;
            }
            parts.add(s.substring(i, j));
            i = j;
        }
        return parts;
    }

    /** {@code s.rsplit(maxsplit=n)}. */
    static List<String> rsplitWhitespace(String s, int maxsplit) {
        List<String> parts = new ArrayList<>();
        int i = s.length();
        while (i > 0) {
            while (i > 0 && PyStr.isSpace(s.charAt(i - 1))) {
                i--;
            }
            if (i == 0) {
                break;
            }
            if (parts.size() == maxsplit) {
                parts.addFirst(s.substring(0, i));
                return parts;
            }
            int j = i;
            while (j > 0 && !PyStr.isSpace(s.charAt(j - 1))) {
                j--;
            }
            parts.addFirst(s.substring(j, i));
            i = j;
        }
        return parts;
    }
}
