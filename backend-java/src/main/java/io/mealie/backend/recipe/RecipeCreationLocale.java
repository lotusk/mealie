package io.mealie.backend.recipe;

import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Unmodified default text from Python's locale data; Python uses an exact locale key or en-US fallback. */
@Component
public class RecipeCreationLocale {
    private final Map<String, Defaults> locales;
    public record Defaults(String ingredientNote, String instructionText) { }

    public RecipeCreationLocale(ObjectMapper mapper) throws IOException {
        try (var stream = getClass().getResourceAsStream("/recipe-create/defaults.json")) {
            if (stream == null) throw new IllegalStateException("Missing recipe creation translations");
            locales = mapper.readValue(stream, new TypeReference<Map<String, Defaults>>() { });
        }
    }

    Defaults defaults(String locale) {
        return locales.getOrDefault(locale, locales.get("en-US"));
    }
}
