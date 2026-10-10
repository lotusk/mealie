package io.mealie.backend.web.validation;

import io.mealie.backend.compat.PyValidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects validation errors for one request in the order FastAPI reports them (path, query, body), then throws them
 * together, the way FastAPI validates every parameter before rejecting the request.
 */
public final class ValidationErrors {

    /** A value that can't be validated: returns the result or throws pydantic's error. */
    @FunctionalInterface
    public interface Validator<I, T> {
        T validate(I input) throws PyValidate.Invalid;
    }

    private final List<ValidationError> errors = new ArrayList<>();

    /** Validates one value at {@code loc}; on failure records the error and returns null. */
    public <I, T> T check(List<Object> loc, I input, Validator<I, T> validator) {
        try {
            return validator.validate(input);
        } catch (PyValidate.Invalid e) {
            errors.add(new ValidationError(e.type(), loc, e.msg(), input, e.ctx()));
            return null;
        }
    }

    public <T> T path(String name, String input, Validator<String, T> validator) {
        return check(List.of("path", name), input, validator);
    }

    /** A query parameter with a default, used when the parameter is absent. */
    public <T> T query(String name, String input, Validator<String, T> validator, T fallback) {
        return input == null ? fallback : check(List.of("query", name), input, validator);
    }

    /**
     * The request body as a pydantic model's input: a JSON object. Records {@code missing} for an absent (or JSON
     * null) body and {@code model_attributes_type} for anything else, and returns null in both cases.
     */
    public Map<String, Object> bodyObject(PyRequestBody body) {
        if (body.isMissing()) {
            errors.add(new ValidationError("missing", List.of("body"), "Field required", null, null));
            return null;
        }
        if (body.value() instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) map;
            return fields;
        }
        errors.add(new ValidationError("model_attributes_type", List.of("body"),
                "Input should be a valid dictionary or object to extract fields from", body.value(), null));
        return null;
    }

    /**
     * A required model field. Mealie models accept the camelCase alias or the snake_case field name
     * ({@code populate_by_name=True}). A missing field is reported under the alias, an invalid one under the key
     * the client sent.
     */
    public <T> T bodyField(Map<String, Object> fields, String alias, String name, Validator<Object, T> validator) {
        if (fields == null) {
            return null;
        }
        String key = fields.containsKey(alias) ? alias : fields.containsKey(name) ? name : null;
        if (key == null) {
            errors.add(new ValidationError("missing", List.of("body", alias), "Field required",
                    new LinkedHashMap<>(fields), null));
            return null;
        }
        return check(List.of("body", key), fields.get(key), validator);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
    }
}
