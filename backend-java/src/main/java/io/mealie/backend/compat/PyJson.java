package io.mealie.backend.compat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A port of CPython's {@code json.loads} (the C scanner in Modules/_json.c), so request bodies are accepted and
 * rejected exactly as FastAPI does, with the same error message and position. Positions count code points, like
 * Python string indices.
 *
 * <p>Values come back as {@link LinkedHashMap}, {@link ArrayList}, {@link String}, {@link BigInteger} (Python ints are
 * unbounded), {@link Double} (including NaN and +/-Infinity, which Python accepts), {@link Boolean} or {@code null}.
 */
public final class PyJson {

    /** {@code json.JSONDecodeError}: {@code msg} is the message without the "line X column Y" suffix. */
    public static final class DecodeError extends Exception {
        private final String msg;
        private final int pos;

        DecodeError(String msg, int pos) {
            super(msg + " at " + pos, null, false, false);
            this.msg = msg;
            this.pos = pos;
        }

        public String msg() {
            return msg;
        }

        public int pos() {
            return pos;
        }
    }

    /** The C scanner's StopIteration: no value starts at this index. */
    private static final class StopIteration extends Exception {
        final int index;

        StopIteration(int index) {
            super(null, null, false, false);
            this.index = index;
        }
    }

    private final int[] s;

    private PyJson(String text) {
        this.s = text.codePoints().toArray();
    }

    public static Object loads(String text) throws DecodeError {
        return new PyJson(text).decode();
    }

    private Object decode() throws DecodeError {
        int idx = skipWhitespace(0);
        Object[] result = new Object[1];
        int end;
        try {
            end = scanOnce(idx, result);
        } catch (StopIteration e) {
            throw new DecodeError("Expecting value", e.index);
        }
        end = skipWhitespace(end);
        if (end != s.length) {
            throw new DecodeError("Extra data", end);
        }
        return result[0];
    }

    private int skipWhitespace(int idx) {
        while (idx < s.length && (s[idx] == ' ' || s[idx] == '\t' || s[idx] == '\n' || s[idx] == '\r')) {
            idx++;
        }
        return idx;
    }

    private boolean startsWith(int idx, String literal) {
        if (idx + literal.length() > s.length) {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            if (s[idx + i] != literal.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** scan_once_unicode: parses the value at idx into out[0] and returns the index after it. */
    private int scanOnce(int idx, Object[] out) throws StopIteration, DecodeError {
        if (idx >= s.length) {
            throw new StopIteration(idx);
        }
        switch (s[idx]) {
            case '"' -> {
                return scanString(idx + 1, out);
            }
            case '{' -> {
                return parseObject(idx + 1, out);
            }
            case '[' -> {
                return parseArray(idx + 1, out);
            }
            case 'n' -> {
                if (startsWith(idx, "null")) {
                    out[0] = null;
                    return idx + 4;
                }
            }
            case 't' -> {
                if (startsWith(idx, "true")) {
                    out[0] = Boolean.TRUE;
                    return idx + 4;
                }
            }
            case 'f' -> {
                if (startsWith(idx, "false")) {
                    out[0] = Boolean.FALSE;
                    return idx + 5;
                }
            }
            case 'N' -> {
                if (startsWith(idx, "NaN")) {
                    out[0] = Double.NaN;
                    return idx + 3;
                }
            }
            case 'I' -> {
                if (startsWith(idx, "Infinity")) {
                    out[0] = Double.POSITIVE_INFINITY;
                    return idx + 8;
                }
            }
            case '-' -> {
                if (startsWith(idx, "-Infinity")) {
                    out[0] = Double.NEGATIVE_INFINITY;
                    return idx + 9;
                }
            }
            default -> {
            }
        }
        return matchNumber(idx, out);
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private int matchNumber(int start, Object[] out) throws StopIteration {
        int idx = start;
        if (idx < s.length && s[idx] == '-') {
            idx++;
            if (idx >= s.length) {
                throw new StopIteration(start);
            }
        }
        if (idx < s.length && s[idx] >= '1' && s[idx] <= '9') {
            idx++;
            while (idx < s.length && isDigit(s[idx])) {
                idx++;
            }
        } else if (idx < s.length && s[idx] == '0') {
            idx++;
        } else {
            throw new StopIteration(start);
        }
        boolean isFloat = false;
        if (idx < s.length - 1 && s[idx] == '.' && isDigit(s[idx + 1])) {
            isFloat = true;
            idx += 2;
            while (idx < s.length && isDigit(s[idx])) {
                idx++;
            }
        }
        if (idx < s.length - 1 && (s[idx] == 'e' || s[idx] == 'E')) {
            int expStart = idx;
            idx++;
            if (idx < s.length - 1 && (s[idx] == '-' || s[idx] == '+')) {
                idx++;
            }
            while (idx < s.length && isDigit(s[idx])) {
                idx++;
            }
            if (isDigit(s[idx - 1])) {
                isFloat = true;
            } else {
                idx = expStart;
            }
        }
        String text = new String(s, start, idx - start);
        out[0] = isFloat ? Double.valueOf(text) : new BigInteger(text);
        return idx;
    }

    /** scanstring_unicode: end is the index just after the opening quote. */
    private int scanString(int end, Object[] out) throws DecodeError {
        int begin = end - 1;
        StringBuilder chunk = new StringBuilder();
        int next = end;
        while (true) {
            int c = 0;
            for (; next < s.length; next++) {
                c = s[next];
                if (c == '"' || c == '\\') {
                    break;
                } else if (c <= 0x1f) {
                    throw new DecodeError("Invalid control character at", next);
                }
                chunk.appendCodePoint(c);
            }
            if (next >= s.length) {
                throw new DecodeError("Unterminated string starting at", begin);
            }
            next++;
            if (c == '"') {
                out[0] = chunk.toString();
                return next;
            }
            if (next == s.length) {
                throw new DecodeError("Unterminated string starting at", begin);
            }
            c = s[next];
            if (c != 'u') {
                next++;
                switch (c) {
                    case '"', '\\', '/' -> chunk.appendCodePoint(c);
                    case 'b' -> chunk.append('\b');
                    case 'f' -> chunk.append('\f');
                    case 'n' -> chunk.append('\n');
                    case 'r' -> chunk.append('\r');
                    case 't' -> chunk.append('\t');
                    default -> throw new DecodeError("Invalid \\escape", next - 2);
                }
            } else {
                next++;
                int value = decodeHex4(next);
                next += 4;
                // Surrogate pair
                if (value >= 0xd800 && value <= 0xdbff && next + 1 < s.length && s[next] == '\\'
                        && s[next + 1] == 'u') {
                    int low = decodeHex4(next + 2);
                    if (low >= 0xdc00 && low <= 0xdfff) {
                        value = 0x10000 + (((value - 0xd800) << 10) | (low - 0xdc00));
                        next += 6;
                    }
                }
                if (value >= 0xd800 && value <= 0xdfff) {
                    chunk.append((char) value);
                } else {
                    chunk.appendCodePoint(value);
                }
            }
        }
    }

    /** Four hex digits starting at next (just after the 'u'); errors point at the 'u'. */
    private int decodeHex4(int next) throws DecodeError {
        int end = next + 4;
        if (end >= s.length) {
            throw new DecodeError("Invalid \\uXXXX escape", next - 1);
        }
        int value = 0;
        for (int i = next; i < end; i++) {
            int digit = Character.digit(s[i], 16);
            if (digit < 0 || s[i] > 0x7f) {
                throw new DecodeError("Invalid \\uXXXX escape", end - 5);
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private int parseObject(int idx, Object[] out) throws StopIteration, DecodeError {
        Map<String, Object> result = new LinkedHashMap<>();
        idx = skipWhitespace(idx);
        if (idx >= s.length || s[idx] != '}') {
            while (true) {
                if (idx >= s.length || s[idx] != '"') {
                    throw new DecodeError("Expecting property name enclosed in double quotes", idx);
                }
                Object[] key = new Object[1];
                idx = scanString(idx + 1, key);
                idx = skipWhitespace(idx);
                if (idx >= s.length || s[idx] != ':') {
                    throw new DecodeError("Expecting ':' delimiter", idx);
                }
                idx = skipWhitespace(idx + 1);
                Object[] value = new Object[1];
                idx = scanOnce(idx, value);
                result.put((String) key[0], value[0]);
                idx = skipWhitespace(idx);
                if (idx < s.length && s[idx] == '}') {
                    break;
                }
                if (idx >= s.length || s[idx] != ',') {
                    throw new DecodeError("Expecting ',' delimiter", idx);
                }
                int comma = idx;
                idx = skipWhitespace(idx + 1);
                if (idx < s.length && s[idx] == '}') {
                    throw new DecodeError("Illegal trailing comma before end of object", comma);
                }
            }
        }
        out[0] = result;
        return idx + 1;
    }

    private int parseArray(int idx, Object[] out) throws StopIteration, DecodeError {
        List<Object> result = new ArrayList<>();
        idx = skipWhitespace(idx);
        if (idx >= s.length || s[idx] != ']') {
            while (true) {
                Object[] value = new Object[1];
                idx = scanOnce(idx, value);
                result.add(value[0]);
                idx = skipWhitespace(idx);
                if (idx < s.length && s[idx] == ']') {
                    break;
                }
                if (idx >= s.length || s[idx] != ',') {
                    throw new DecodeError("Expecting ',' delimiter", idx);
                }
                int comma = idx;
                idx = skipWhitespace(idx + 1);
                if (idx < s.length && s[idx] == ']') {
                    throw new DecodeError("Illegal trailing comma before end of array", comma);
                }
            }
        }
        out[0] = result;
        return idx + 1;
    }
}
