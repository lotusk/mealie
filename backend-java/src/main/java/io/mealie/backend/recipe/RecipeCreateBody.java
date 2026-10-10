package io.mealie.backend.recipe;

/** Parsing happens before auth, while field validation happens after auth, as in FastAPI. */
public record RecipeCreateBody(Object value, boolean missing) { }
