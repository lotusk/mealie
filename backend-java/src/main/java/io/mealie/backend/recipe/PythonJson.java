package io.mealie.backend.recipe;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** JSON grammar with CPython's error offsets/messages, used only for this route's validation compatibility. */
final class PythonJson {
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private final String text;
    private int position;
    PythonJson(String text) { this.text = text; }

    Object parse() {
        skip();
        Object result = value();
        skip();
        if (position != text.length()) fail("Extra data", position);
        return result;
    }

    private Object value() {
        skip();
        if (position >= text.length()) return fail("Expecting value", position);
        char token = text.charAt(position);
        if (token == '"') return string();
        if (token == '{') return object();
        if (token == '[') return array();
        for (String literal : new String[] {"true", "false", "null", "NaN", "Infinity", "-Infinity"}) {
            if (text.startsWith(literal, position)) {
                position += literal.length();
                return switch (literal) {
                    case "true" -> true;
                    case "false" -> false;
                    case "null" -> null;
                    case "NaN" -> Double.NaN;
                    case "Infinity" -> Double.POSITIVE_INFINITY;
                    default -> Double.NEGATIVE_INFINITY;
                };
            }
        }
        var matcher = NUMBER.matcher(text).region(position, text.length());
        if (matcher.lookingAt()) {
            String number = matcher.group();
            position = matcher.end();
            return number.contains(".") || number.contains("e") || number.contains("E")
                    ? Double.parseDouble(number) : new BigInteger(number);
        }
        return fail("Expecting value", position);
    }

    private Map<String, Object> object() {
        position++;
        skip();
        var result = new LinkedHashMap<String, Object>();
        if (take('}')) return result;
        while (true) {
            if (position >= text.length() || text.charAt(position) != '"') fail("Expecting property name enclosed in double quotes", position);
            String key = string();
            skip();
            if (!take(':')) fail("Expecting ':' delimiter", position);
            result.put(key, value());
            skip();
            if (take('}')) return result;
            if (!take(',')) fail("Expecting ',' delimiter", position);
            int comma = position - 1;
            skip();
            if (position < text.length() && text.charAt(position) == '}') fail("Illegal trailing comma before end of object", comma);
        }
    }

    private Object array() {
        position++;
        skip();
        var result = new ArrayList<>();
        if (take(']')) return result;
        while (true) {
            result.add(value());
            skip();
            if (take(']')) return result;
            if (!take(',')) fail("Expecting ',' delimiter", position);
            int comma = position - 1;
            skip();
            if (position < text.length() && text.charAt(position) == ']') fail("Illegal trailing comma before end of array", comma);
        }
    }

    private String string() {
        int start = position++;
        var value = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') return value.toString();
            if (c < 32) fail("Invalid control character at", position - 1);
            if (c != '\\') { value.append(c); continue; }
            if (position == text.length()) fail("Unterminated string starting at", start);
            char escape = text.charAt(position++);
            switch (escape) {
                case '"', '\\', '/' -> value.append(escape);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length() || !text.substring(position, position + 4).matches("[0-9a-fA-F]{4}")) fail("Invalid \\uXXXX escape", position - 1);
                    value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> fail("Invalid \\escape", position - 2);
            }
        }
        return fail("Unterminated string starting at", start);
    }

    private boolean take(char c) { if (position < text.length() && text.charAt(position) == c) { position++; return true; } return false; }
    private void skip() { while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++; }
    private <T> T fail(String message, int at) { throw new Failure(message, text.codePointCount(0, at)); }
    static final class Failure extends RuntimeException {
        final int position;
        Failure(String message, int position) { super(message); this.position = position; }
    }
}
