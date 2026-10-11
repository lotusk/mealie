package io.mealie.backend.recipe;
import io.mealie.backend.config.MealieSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecipeDuplicateValidation {
    private final RecipeCreateValidation validation;
    private final String trace;
    public RecipeDuplicateValidation(RecipeCreateValidation validation, MealieSettings settings) {
        this.validation = validation;
        var source = settings.baseDir().resolve("mealie/routes/recipe/recipe_crud_routes.py");
        int line = 619;
        try {
            var lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("@router.post(\"/{slug}/duplicate\"")) { line = i + 1; break; }
        } catch (IOException ignored) { }
        trace = "  File \"" + source + "\", line " + line + ", in duplicate_one   POST /api/recipes/{slug}/duplicate";
    }
    RecipeDuplicateBody parse(HttpServletRequest request) throws IOException { return new RecipeDuplicateBody(validation.parse(request, trace)); }
    String name(RecipeDuplicateBody parsed) {
        var body = parsed.body();
        if (body.missing()) throw validation.invalid("missing", List.of("body"), "Field required", null, null, trace);
        if (!(body.value() instanceof Map<?, ?> values)) throw validation.invalid("model_attributes_type", List.of("body"),
                "Input should be a valid dictionary or object to extract fields from", body.value(), null, trace);
        Object name = values.get("name");
        if (name != null && !(name instanceof String)) throw validation.invalid("string_type", List.of("body", "name"),
                "Input should be a valid string", name, null, trace);
        return (String) name;
    }
}
