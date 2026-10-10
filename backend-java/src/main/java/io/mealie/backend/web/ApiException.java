package io.mealie.backend.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** The equivalent of FastAPI's HTTPException; rendered as {@code {"detail": ...}} by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, String> headers;
    private final Object body;

    public ApiException(HttpStatus status, String detail, Map<String, String> headers) {
        super(detail);
        this.status = status;
        this.headers = Map.copyOf(headers);
        this.body = detail;
    }

    /** An HTTPException whose detail is an object, e.g. Mealie's ErrorResponse.respond(). */
    public ApiException(HttpStatus status, Map<String, ?> detail) {
        super(String.valueOf(detail));
        this.status = status;
        this.headers = Map.of();
        this.body = detail;
    }

    /**
     * {@code ErrorResponse.respond(message, exception)} from mealie/schema/response/responses.py:
     * {@code {"message": ..., "error": true, "exception": ...}}.
     */
    public static ApiException errorResponse(HttpStatus status, String message, String exception) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("message", message);
        detail.put("error", true);
        detail.put("exception", exception);
        return new ApiException(status, detail);
    }

    /** FastAPI's default detail is the reason phrase, e.g. {@code HTTPException(401)} gives "Unauthorized". */
    public ApiException(HttpStatus status) {
        this(status, status.getReasonPhrase(), Map.of());
    }

    public HttpStatus status() {
        return status;
    }

    public String detail() {
        return getMessage();
    }

    /** The value of the response's {@code detail} key. */
    public Object body() {
        return body;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
