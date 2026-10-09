package io.mealie.backend.compat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code slugify(text)} from python-slugify with its default arguments (the frozen "legacy" pipeline in
 * slugify/_legacy.py), which is how Mealie derives slugs for tags, categories, tools and recipes. A slug Java computes
 * must equal the one Python computes for the same name, because both backends read and write the same rows.
 *
 * <p>Transliteration uses text-unidecode's own table ({@code compat/text_unidecode.bin}, copied unchanged from
 * text-unidecode 1.3, which is licensed under the Artistic License or GPLv2+), so non-ASCII names produce the same
 * ASCII as in Python.
 */
public final class PySlugify {

    private static final Pattern CHAR_ENTITY = Pattern.compile("&([A-Za-z0-9]+);");
    private static final Pattern DECIMAL = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX = Pattern.compile("&#x([\\da-fA-F]+);");
    private static final Pattern QUOTES = Pattern.compile("'+");
    /** Python's \d is Unicode-aware. */
    private static final Pattern NUMBERS = Pattern.compile("(?U)(?<=\\d),(?=\\d)");
    private static final Pattern DISALLOWED = Pattern.compile("[^-a-zA-Z0-9]+");
    private static final Pattern DUPLICATE_DASH = Pattern.compile("-{2,}");

    private static final String[] UNIDECODE = loadUnidecode();
    private static final Map<String, Integer> ENTITIES = loadEntities();

    private PySlugify() {
    }

    public static String slugify(String text) {
        text = QUOTES.matcher(text).replaceAll("-");
        text = transliterate(Normalizer.normalize(text, Normalizer.Form.NFKD));
        text = decodeEntities(text);
        text = Normalizer.normalize(text, Normalizer.Form.NFKD);
        text = PyStr.lower(text);
        text = QUOTES.matcher(text).replaceAll("");
        text = NUMBERS.matcher(text).replaceAll("");
        text = DISALLOWED.matcher(text).replaceAll("-");
        text = DUPLICATE_DASH.matcher(text).replaceAll("-");
        return stripDashes(text);
    }

    /** text_unidecode.unidecode(); ASCII input is returned unchanged, as python-slugify short-circuits it. */
    static String transliterate(String text) {
        if (text.chars().allMatch(c -> c < 0x80)) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (cp == 0) {
                out.append('\0');
            } else if (cp - 1 < UNIDECODE.length) {
                out.append(UNIDECODE[cp - 1]);
            }
        });
        return out.toString();
    }

    private static String decodeEntities(String text) {
        text = replace(CHAR_ENTITY, text, m -> {
            Integer cp = ENTITIES.get(m.group(1));
            return cp == null ? m.group() : Character.toString(cp);
        });
        // Python substitutes all references of a kind or, if any is out of range, none of them.
        String decimal = replaceNumeric(DECIMAL, text, 10);
        text = decimal != null ? decimal : text;
        String hex = replaceNumeric(HEX, text, 16);
        return hex != null ? hex : text;
    }

    private static String replaceNumeric(Pattern pattern, String text, int radix) {
        try {
            return replace(pattern, text, m -> {
                long cp = Long.parseLong(m.group(1).length() > 12 ? "99999999999" : m.group(1), radix);
                if (cp > Character.MAX_CODE_POINT) {
                    throw new IllegalArgumentException();
                }
                return cp >= 0xd800 && cp <= 0xdfff ? String.valueOf((char) cp) : Character.toString((int) cp);
            });
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replace(Pattern pattern, String text, Replacer replacer) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String stripDashes(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '-') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '-') {
            end--;
        }
        return text.substring(start, end);
    }

    private static String[] loadUnidecode() {
        try (InputStream in = PySlugify.class.getResourceAsStream("/compat/text_unidecode.bin")) {
            if (in == null) {
                throw new IllegalStateException("compat/text_unidecode.bin is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\0", -1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Integer> loadEntities() {
        Properties properties = new Properties();
        try (InputStream in = PySlugify.class.getResourceAsStream("/compat/html_entities.properties")) {
            if (in == null) {
                throw new IllegalStateException("compat/html_entities.properties is missing");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Integer> entities = new HashMap<>();
        properties.forEach((name, cp) -> entities.put((String) name, Integer.parseInt((String) cp)));
        return Map.copyOf(entities);
    }
}
