package io.mealie.backend.recipe;

import io.mealie.backend.config.MealieSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecipeCreateValidation {
    private final MealieSettings settings;
    private final String sourceTrace;

    public RecipeCreateValidation(MealieSettings settings) {
        this.settings = settings;
        var source = settings.baseDir().resolve("mealie/routes/recipe/recipe_crud_routes.py");
        int line = 595;
        try {
            var lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("@router.post(\"\", status_code=201")) {
                line = i + 1;
                break;
            }
        } catch (IOException ignored) { /* Compatibility fallback for the current Python baseline. */ }
        sourceTrace = "  File \"" + source + "\", line " + line + ", in create_one   POST /api/recipes";
    }

    RecipeCreateBody parse(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readAllBytes();
        if (bytes.length == 0) return new RecipeCreateBody(null, true);
        String type = request.getContentType();
        if (type != null) {
            String media = type.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
            if (!media.equals("application/json") && !(media.startsWith("application/") && media.endsWith("+json"))) {
                return new RecipeCreateBody(new ByteBody(bytes), false);
            }
        }
        try {
            Object value = new PythonJson(decode(bytes)).parse();
            return new RecipeCreateBody(value, value == null);
        } catch (CharacterCodingException e) {
            throw new RecipeCreateFailure(400, Map.of("detail", "There was an error parsing the body"));
        } catch (PythonJson.Failure e) {
            throw invalid("json_invalid", List.of("body", e.position), "JSON decode error", Map.of(),
                    Map.of("error", e.getMessage()));
        }
    }

    public String name(RecipeCreateBody body) {
        if (body.missing()) throw invalid("missing", List.of("body"), "Field required", null, null);
        if (!(body.value() instanceof Map<?, ?> values)) {
            throw invalid("model_attributes_type", List.of("body"),
                    "Input should be a valid dictionary or object to extract fields from", body.value(), null);
        }
        if (!values.containsKey("name")) throw invalid("missing", List.of("body", "name"), "Field required", values, null);
        if (!(values.get("name") instanceof String name)) {
            throw invalid("string_type", List.of("body", "name"), "Input should be a valid string", values.get("name"), null);
        }
        return name;
    }

    private RecipeCreateFailure invalid(String type, List<Object> location, String message, Object input,
            Map<String, Object> context) {
        var error = new LinkedHashMap<String, Object>();
        error.put("type", type);
        error.put("loc", location);
        error.put("msg", message);
        error.put("input", input instanceof ByteBody bytes ? new String(bytes.bytes(), StandardCharsets.UTF_8) : input);
        if (context != null) error.put("ctx", context);
        if (settings.production() && !settings.testing()) return new RecipeCreateFailure(422, Map.of("detail", List.of(error)));
        var display = new LinkedHashMap<>(error);
        display.put("loc", new PythonTuple(location));
        display.put("input", input);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("status_code", 422);
        envelope.put("message", "1 validation error: " + repr(display) + sourceTrace);
        envelope.put("data", null);
        return new RecipeCreateFailure(422, envelope);
    }

    private record PythonTuple(List<Object> values) { }
    private record ByteBody(byte[] bytes) { }

    static String repr(Object value) {
        if (value == null) return "None";
        if (value instanceof Boolean bool) return bool ? "True" : "False";
        if (value instanceof String string) return quote(string);
        if (value instanceof ByteBody bytes) {
            var text = new StringBuilder();
            for (byte item : bytes.bytes()) {
                int n = item & 255;
                if (n >= 128) text.append("\\x%02x".formatted(n));
                else text.append((char) n);
            }
            // ASCII byte bodies use the same quoting; avoid double escaping non-ASCII byte escapes.
            return "b" + quote(text.toString()).replace("\\\\x", "\\x");
        }
        if (value instanceof PythonTuple tuple) return "(" + join(tuple.values()) + (tuple.values().size() == 1 ? "," : "") + ")";
        if (value instanceof List<?> list) return "[" + join(list) + "]";
        if (value instanceof Map<?, ?> map) return "{" + map.entrySet().stream()
                .map(e -> repr(e.getKey()) + ": " + repr(e.getValue())).collect(java.util.stream.Collectors.joining(", ")) + "}";
        if (value instanceof Double n) {
            if (Double.isNaN(n)) return "nan";
            if (n.isInfinite()) return n > 0 ? "inf" : "-inf";
            if (n == 0) return Double.doubleToRawLongBits(n) < 0 ? "-0.0" : "0.0";
            var decimal = BigDecimal.valueOf(n).stripTrailingZeros();
            int exponent = decimal.precision() - decimal.scale() - 1;
            if (exponent >= 16 || exponent < -4) {
                return decimal.movePointLeft(exponent).toPlainString() + "e"
                        + (exponent < 0 ? "-" : "+") + "%02d".formatted(Math.abs(exponent));
            }
            String fixed = decimal.toPlainString();
            return fixed.contains(".") ? fixed : fixed + ".0";
        }
        return value.toString();
    }

    private static String join(List<?> values) { return values.stream().map(RecipeCreateValidation::repr)
            .collect(java.util.stream.Collectors.joining(", ")); }

    private static String quote(String value) {
        char delimiter = value.indexOf('\'') >= 0 && value.indexOf('"') < 0 ? '"' : '\'';
        var text = new StringBuilder().append(delimiter);
        value.codePoints().forEach(cp -> {
            if (cp == delimiter || cp == '\\') text.append('\\').appendCodePoint(cp);
            else if (cp == '\n') text.append("\\n");
            else if (cp == '\r') text.append("\\r");
            else if (cp == '\t') text.append("\\t");
            else if (cp < 32 || cp == 127) text.append("\\x%02x".formatted(cp));
            else if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.SURROGATE || Character.getType(cp) == Character.UNASSIGNED
                    || cp == 0xa0 || Character.getType(cp) == Character.LINE_SEPARATOR || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR) {
                text.append(cp <= 255 ? "\\x%02x".formatted(cp) : cp <= 65535 ? "\\u%04x".formatted(cp) : "\\U%08x".formatted(cp));
            } else text.appendCodePoint(cp);
        });
        return text.append(delimiter).toString();
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        String charset = "UTF-8";
        int skip = 0;
        if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == (byte) 0xfe && bytes[3] == (byte) 0xff) { charset = "UTF-32BE"; skip = 4; }
        else if (bytes.length >= 4 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe && bytes[2] == 0 && bytes[3] == 0) { charset = "UTF-32LE"; skip = 4; }
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) { charset = "UTF-16BE"; skip = 2; }
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) { charset = "UTF-16LE"; skip = 2; }
        else if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) skip = 3;
        else if (bytes.length >= 4 && bytes[0] == 0) charset = bytes[1] == 0 ? "UTF-32BE" : "UTF-16BE";
        else if (bytes.length >= 4 && bytes[1] == 0) charset = bytes[2] == 0 && bytes[3] == 0 ? "UTF-32LE" : "UTF-16LE";
        else if (bytes.length == 2 && bytes[0] == 0) charset = "UTF-16BE";
        else if (bytes.length == 2 && bytes[1] == 0) charset = "UTF-16LE";
        return Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
    }
}
