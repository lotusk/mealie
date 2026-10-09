package io.mealie.backend.tags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/** python-slugify's default pipeline, with the same text-unidecode 1.3 transliteration table. */
final class TagSlug {
    private static final String[] REPLACEMENTS = load();

    private static String[] load() {
        try (var stream = TagSlug.class.getResourceAsStream("/text-unidecode/data.bin")) {
            if (stream == null) throw new IllegalStateException("Missing transliteration table");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\u0000", -1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String slugify(String name) {
        String normalized = Normalizer.normalize(name.replaceAll("'+", "-"), Normalizer.Form.NFKD);
        StringBuilder ascii = new StringBuilder();
        normalized.codePoints().forEach(cp -> {
            if (cp == 0) ascii.append('\0');
            else if (cp <= REPLACEMENTS.length) ascii.append(REPLACEMENTS[cp - 1]);
        });
        return Normalizer.normalize(HtmlUtils.htmlUnescape(ascii.toString()), Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT).replaceAll("'+", "").replaceAll("(?<=\\d),(?=\\d)", "")
                .replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }
}
