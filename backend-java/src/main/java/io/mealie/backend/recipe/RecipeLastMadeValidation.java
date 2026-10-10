package io.mealie.backend.recipe;

import io.mealie.backend.config.MealieSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecipeLastMadeValidation {
    private final RecipeCreateValidation json;
    private final String trace;
    public RecipeLastMadeValidation(RecipeCreateValidation json, MealieSettings settings) {
        this.json = json;
        var source = settings.baseDir().resolve("mealie/routes/recipe/recipe_crud_routes.py");
        int line = 737;
        try {
            var lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("@router.patch(\"/{slug}/last-made\")")) {
                line = i + 1;
                break;
            }
        } catch (IOException ignored) { /* Current baseline fallback. */ }
        trace = "  File \"" + source + "\", line " + line + ", in update_last_made   PATCH /api/recipes/{slug}/last-made";
    }
    RecipeLastMadeBody parse(HttpServletRequest request) throws IOException {
        return new RecipeLastMadeBody(json.parse(request, trace));
    }
    OffsetDateTime timestamp(RecipeLastMadeBody input) {
        var body = input.body();
        if (body.missing()) throw json.invalid("missing", List.of("body"), "Field required", null, null, trace);
        if (!(body.value() instanceof Map<?, ?> values)) throw json.invalid("model_attributes_type", List.of("body"),
                "Input should be a valid dictionary or object to extract fields from", body.value(), null, trace);
        if (!values.containsKey("timestamp")) throw json.invalid("missing", List.of("body", "timestamp"), "Field required", values, null, trace);
        Object value = values.get("timestamp");
        try { return RecipeLastMadeTimestamp.parse(value); }
        catch (RecipeLastMadeTimestamp.Invalid error) {
            String message = error.error == null ? "Input should be a valid datetime" : "Input should be a valid datetime"
                    + (error.type.equals("datetime_from_date_parsing") ? " or date" : "") + ", " + error.error;
            throw json.invalid(error.type, List.of("body", "timestamp"), message, value,
                    error.error == null ? null : Map.of("error", error.error), trace);
        }
    }
}
