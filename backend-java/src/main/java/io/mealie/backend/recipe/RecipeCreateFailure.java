package io.mealie.backend.recipe;

import java.util.LinkedHashMap;
import java.util.Map;

/** Route-specific FastAPI-compatible errors, including its debug validation envelope. */
final class RecipeCreateFailure extends RuntimeException {
    final int status;
    final Object body;
    RecipeCreateFailure(int status, Object body) { this.status = status; this.body = body; }

    static RecipeCreateFailure error(int status, String message, String exception) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("message", message);
        detail.put("error", true);
        detail.put("exception", exception);
        return new RecipeCreateFailure(status, Map.of("detail", detail));
    }
}
