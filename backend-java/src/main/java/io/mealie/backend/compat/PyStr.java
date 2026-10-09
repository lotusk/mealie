package io.mealie.backend.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Python {@code str} methods whose notion of whitespace or case differs from Java's. */
public final class PyStr {

    private PyStr() {
    }

    /**
     * {@code str.isspace()} for one code point. Java's Character.isWhitespace() leaves out the no-break spaces and
     * U+0085, which Python treats as whitespace.
     */
    public static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
    }

    /** {@code str.strip()}. */
    public static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isSpace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    /** {@code str.split()} with no separator: runs of whitespace, no empty strings. */
    public static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (isSpace(cp)) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.appendCodePoint(cp);
            }
        });
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** {@code str.lower()}. Locale.ROOT gives the language-independent mappings Python uses. */
    public static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
