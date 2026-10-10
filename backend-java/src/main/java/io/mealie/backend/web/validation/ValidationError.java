package io.mealie.backend.web.validation;

import java.util.List;
import java.util.Map;

/**
 * One pydantic validation error as FastAPI reports it: {@code type}, {@code loc} (e.g. {@code ["body", "name"]}),
 * {@code msg}, {@code input} and, for some types, {@code ctx}.
 */
public record ValidationError(String type, List<Object> loc, String msg, Object input, Map<String, Object> ctx) {
}
