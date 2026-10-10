package io.mealie.backend.recipe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Python's default legacy python-slugify pipeline, plus SQLAlchemy's distinct search normalization. */
final class RecipeSlug {
    private static final String PUNCTUATION = "!#$%&()*+,-./:;<=>?@[\\]^_`{|}~";
    private static final String[] TRANSLITERATION;
    private static final Map<String, String> ENTITIES;
    static {
        try (var table = RecipeSlug.class.getResourceAsStream("/recipe-list/text-unidecode/data.bin");
                var entities = RecipeSlug.class.getResourceAsStream("/recipe-create/html-entities.json")) {
            if (table == null || entities == null) throw new IllegalStateException("Missing slug compatibility data");
            TRANSLITERATION = new String(table.readAllBytes(), StandardCharsets.UTF_8).split("\u0000", -1);
            ENTITIES = JsonMapper.builder().build().readValue(entities, new TypeReference<Map<String, String>>() { });
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private RecipeSlug() { }

    static String transliterate(String value) {
        var result = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (cp == 0) result.append('\0');
            else if (cp <= TRANSLITERATION.length) result.append(TRANSLITERATION[cp - 1]);
        });
        return result.toString();
    }

    static String create(String name) {
        String value = name.replaceAll("'+", "-");
        value = transliterate(Normalizer.normalize(value, Normalizer.Form.NFKD));
        value = replaceEntities(value);
        value = Normalizer.normalize(value, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replaceAll("'+", "").replaceAll("(?<=\\d),(?=\\d)", "")
                .replaceAll("[^-a-zA-Z0-9]+", "-").replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (value.isEmpty()) throw RecipeCreateFailure.error(400, "Unable to generate recipe slug", null);
        return value.length() > 250 ? value.substring(0, 250) : value;
    }

    static String normalize(String value) {
        String text = transliterate(value);
        var result = new StringBuilder();
        text.codePoints().forEach(cp -> result.appendCodePoint(PUNCTUATION.indexOf(cp) >= 0 ? ' ' : cp));
        text = result.toString().toLowerCase(Locale.ROOT).strip();
        return text.length() > 255 ? text.substring(0, 255) : text;
    }

    private static String replaceEntities(String text) {
        text = Pattern.compile("&([a-zA-Z0-9]+);").matcher(text).replaceAll(m ->
                java.util.regex.Matcher.quoteReplacement(ENTITIES.getOrDefault(m.group(1), m.group())));
        text = numeric(text, "&#(\\d+);", 10);
        // The installed legacy version accepts lowercase x only (the modern path accepts X).
        return numeric(text, "&#x([\\da-fA-F]+);", 16);
    }

    private static String numeric(String text, String pattern, int radix) {
        var matcher = Pattern.compile(pattern).matcher(text);
        try {
            // Legacy slugify wraps each whole substitution in one try: any invalid chr leaves that stage unchanged.
            return matcher.replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                    new String(Character.toChars(Integer.parseInt(m.group(1), radix)))));
        } catch (IllegalArgumentException e) { return text; }
    }
}
