package io.mealie.backend.web.validation;

/**
 * A request body as FastAPI hands it to pydantic: the decoded JSON value when the content type is JSON, otherwise
 * the raw bytes; {@code missing} when there is no body (or it is JSON {@code null}).
 *
 * <p>Declare it as the first controller parameter, before {@code AuthUser}: FastAPI parses the body before it
 * resolves dependencies, so malformed JSON is a 422 even without credentials.
 */
public record PyRequestBody(boolean isMissing, Object value) {

    static final PyRequestBody MISSING = new PyRequestBody(true, null);

    static PyRequestBody of(Object value) {
        return value == null ? MISSING : new PyRequestBody(false, value);
    }
}
