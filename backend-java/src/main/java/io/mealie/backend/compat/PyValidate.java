package io.mealie.backend.compat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pydantic v2 (lax mode) validation for the field types migrated endpoints accept, with pydantic's error type,
 * message and context, so a rejected request produces the same validation error as in Python.
 */
public final class PyValidate {

    private PyValidate() {
    }

    /** A rejected value: one pydantic error without its location. */
    public static final class Invalid extends Exception {
        private final String type;
        private final String msg;
        private final Map<String, Object> ctx;

        public Invalid(String type, String msg, Map<String, Object> ctx) {
            super(msg, null, false, false);
            this.type = type;
            this.msg = msg;
            this.ctx = ctx;
        }

        public String type() {
            return type;
        }

        public String msg() {
            return msg;
        }

        public Map<String, Object> ctx() {
            return ctx;
        }
    }

    // -- str ----------------------------------------------------------------------------------------------------

    public static String str(Object input) throws Invalid {
        if (input instanceof String s) {
            return s;
        }
        throw new Invalid("string_type", "Input should be a valid string", null);
    }

    // -- int ----------------------------------------------------------------------------------------------------

    /** Digits with optional sign and underscores; a fraction made only of zeros is accepted ("1.0" is 1). */
    private static final Pattern INT = Pattern.compile("[+-]?[0-9]+(?:_[0-9]+)*(?:\\.0+)?");

    public static long integer(String input) throws Invalid {
        String value = PyStr.strip(input);
        if (!INT.matcher(value).matches()) {
            throw new Invalid("int_parsing", "Input should be a valid integer, unable to parse string as an integer",
                    null);
        }
        int dot = value.indexOf('.');
        String digits = (dot >= 0 ? value.substring(0, dot) : value).replace("_", "");
        BigInteger parsed = new BigInteger(digits.startsWith("+") ? digits.substring(1) : digits);
        // Python ints are unbounded; anything beyond a long is far outside what a page number can mean, and makes
        // the Python backend fail in the database layer too.
        return parsed.longValueExact();
    }

    // -- enum ---------------------------------------------------------------------------------------------------

    /** A str enum: returns the matching value, or pydantic's "Input should be 'a' or 'b'" error. */
    public static String enumValue(String input, List<String> allowed) throws Invalid {
        if (allowed.contains(input)) {
            return input;
        }
        String expected = expectedList(allowed);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("expected", expected);
        throw new Invalid("enum", "Input should be " + expected, ctx);
    }

    private static String expectedList(List<String> allowed) {
        List<String> quoted = allowed.stream().map(v -> "'" + v + "'").toList();
        if (quoted.size() == 1) {
            return quoted.getFirst();
        }
        return quoted.subList(0, quoted.size() - 1).stream().collect(Collectors.joining(", "))
                + " or " + quoted.getLast();
    }

    // -- UUID4 --------------------------------------------------------------------------------------------------

    private static final int[] GROUP_STARTS = {0, 9, 14, 19, 24};
    private static final int[] GROUP_LENGTHS = {8, 4, 4, 4, 12};

    /** pydantic's {@code UUID4}: parsed like the Rust uuid crate, then the version must be 4. */
    public static UUID uuid4(Object input) throws Invalid {
        if (!(input instanceof String s)) {
            throw new Invalid("uuid_type", "UUID input should be a string, bytes or UUID object", null);
        }
        UUID uuid = parseUuid(s);
        if (uuid.version() != 4) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("expected_version", 4);
            throw new Invalid("uuid_version", "UUID version 4 expected", ctx);
        }
        return uuid;
    }

    static UUID parseUuid(String input) throws Invalid {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        String hyphenated = switch (bytes.length) {
            case 32 -> isHex(input) ? input.substring(0, 8) + "-" + input.substring(8, 12) + "-"
                    + input.substring(12, 16) + "-" + input.substring(16, 20) + "-" + input.substring(20) : null;
            case 36 -> input;
            case 38 -> input.startsWith("{") && input.endsWith("}") ? input.substring(1, 37) : null;
            case 45 -> input.startsWith("urn:uuid:") ? input.substring(9) : null;
            default -> null;
        };
        if (hyphenated != null && isHyphenated(hyphenated)) {
            return UUID.fromString(hyphenated);
        }
        throw uuidError(input, bytes);
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0 || value.charAt(i) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHyphenated(String value) {
        if (value.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = value.charAt(i);
            boolean hyphenPosition = i == 8 || i == 13 || i == 18 || i == 23;
            if (hyphenPosition ? c != '-' : (Character.digit(c, 16) < 0 || c > 0x7f)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The uuid crate's InvalidUuid::into_err(): explain why the input isn't a UUID. Character positions are 1-based
     * bytes into the input with any braces or urn prefix removed, but the last group's length is measured against
     * the whole input (both quirks checked against pydantic).
     */
    private static Invalid uuidError(String input, byte[] bytes) {
        boolean simple = true;
        String body = input;
        if (bytes.length >= 2 && input.startsWith("{") && input.endsWith("}")) {
            body = input.substring(1, input.length() - 1);
            simple = false;
        } else if (input.startsWith("urn:uuid:")) {
            body = input.substring(9);
            simple = false;
        }

        int hyphens = 0;
        int[] groupBounds = new int[4];
        int byteIndex = 0;
        for (int i = 0; i < body.length(); ) {
            int cp = body.codePointAt(i);
            if (cp > 0x7f) {
                return uuidParsing("invalid character: found `" + Character.toString(cp) + "` at "
                        + (byteIndex + 1));
            } else if (cp == '-') {
                if (hyphens < 4) {
                    groupBounds[hyphens] = byteIndex;
                }
                hyphens++;
            } else if (Character.digit(cp, 16) < 0) {
                return uuidParsing("invalid character: found `" + (char) cp + "` at " + (byteIndex + 1));
            }
            byteIndex += Character.toString(cp).getBytes(StandardCharsets.UTF_8).length;
            i += Character.charCount(cp);
        }
        int length = byteIndex;
        int inputLength = bytes.length;

        if (hyphens == 0 && simple) {
            return uuidParsing("invalid length: expected length 32 for simple format, found " + length);
        } else if (hyphens != 4) {
            return uuidParsing("invalid group count: expected 5, found " + (hyphens + 1));
        }
        for (int i = 0; i < 4; i++) {
            if (groupBounds[i] != GROUP_STARTS[i + 1] - 1) {
                return uuidParsing("invalid group length in group " + i + ": expected " + GROUP_LENGTHS[i]
                        + ", found " + (groupBounds[i] - GROUP_STARTS[i]));
            }
        }
        return uuidParsing(
                "invalid group length in group 4: expected 12, found " + (inputLength - GROUP_STARTS[4]));
    }

    private static Invalid uuidParsing(String error) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("error", error);
        return new Invalid("uuid_parsing", "Input should be a valid UUID, " + error, ctx);
    }
}
