package io.mealie.backend.query;

import io.mealie.backend.compat.PyStr;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code humps.decamelize()} (pyhumps), which Python applies to every attribute name in a query filter or order. */
final class Humps {

    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern ACRONYM = Pattern.compile("([A-Z\\p{Nd}]+)(?=[A-Z\\p{Nd}]|$)");
    private static final Pattern SPLIT = Pattern.compile("([\\-_]*[A-Z][^A-Z]*[\\-_]*)");

    private Humps() {
    }

    static String decamelize(String value) {
        String s = WHITESPACE.matcher(value).replaceAll("");
        if (isUpper(s) || isNumeric(s)) {
            return value;
        }
        Matcher acronyms = ACRONYM.matcher(s);
        StringBuilder fixed = new StringBuilder();
        while (acronyms.find()) {
            acronyms.appendReplacement(fixed, Matcher.quoteReplacement(title(acronyms.group())));
        }
        acronyms.appendTail(fixed);

        List<String> parts = new ArrayList<>();
        Matcher words = SPLIT.matcher(fixed);
        int last = 0;
        while (words.find()) {
            parts.add(fixed.substring(last, words.start()));
            parts.add(words.group());
            last = words.end();
        }
        parts.add(fixed.substring(last));
        return PyStr.lower(String.join("_", parts.stream().filter(p -> !p.isEmpty()).toList()));
    }

    /** {@code str.isupper()}: at least one cased character, and no lowercase ones. */
    private static boolean isUpper(String s) {
        boolean cased = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLowerCase(cp) || Character.isTitleCase(cp)) {
                return false;
            }
            cased |= Character.isUpperCase(cp);
            i += Character.charCount(cp);
        }
        return cased;
    }

    /** {@code str.isnumeric()}. */
    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        return s.codePoints().allMatch(cp -> switch (Character.getType(cp)) {
            case Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
            default -> false;
        });
    }

    /** {@code str.title()}: upper-case a character that follows an uncased one, lower-case the rest. */
    private static String title(String s) {
        StringBuilder out = new StringBuilder();
        boolean previousCased = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            boolean cased = Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp);
            out.appendCodePoint(previousCased ? Character.toLowerCase(cp) : Character.toTitleCase(cp));
            previousCased = cased;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
