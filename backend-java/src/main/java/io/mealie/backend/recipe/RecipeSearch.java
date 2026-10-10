package io.mealie.backend.recipe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Exact text-unidecode 1.3 table, punctuation handling and quoted-phrase tokenization used by Python. */
final class RecipeSearch {
    private static final String PUNCTUATION = "!#$%&()*+,-./:;<=>?@[\\]^_`{|}~";
    private static final Pattern QUOTED = Pattern.compile("([\"'])(?:(?=(\\\\?))\\2.)*?\\1");
    private static final String[] REPLACEMENTS;
    static {
        try (var source = RecipeSearch.class.getResourceAsStream("/recipe-list/text-unidecode/data.bin")) {
            if (source == null) throw new IllegalStateException("Missing search transliteration table");
            REPLACEMENTS = new String(source.readAllBytes(), StandardCharsets.UTF_8).split("\u0000", -1);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    static String normalize(String value) {
        value = punctuation(value);
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (cp == 0) result.append('\0');
            else if (cp <= REPLACEMENTS.length) result.append(REPLACEMENTS[cp - 1]);
        });
        return result.toString().toLowerCase(java.util.Locale.ROOT).strip();
    }

    static boolean quoted(String value) { return QUOTED.matcher(value.strip()).find(); }

    static List<String> tokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        var matcher = QUOTED.matcher(normalized);
        while (matcher.find()) tokens.add(matcher.group().substring(1, matcher.group().length() - 1).strip());
        String rest = punctuation(matcher.replaceAll(""));
        for (String word : rest.split("\\s+")) if (!word.isEmpty()) tokens.add(word);
        return tokens;
    }

    private static String punctuation(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(cp -> result.appendCodePoint(PUNCTUATION.indexOf(cp) >= 0 ? ' ' : cp));
        return result.toString();
    }
}
