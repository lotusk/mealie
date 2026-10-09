package io.mealie.backend.web;

import io.mealie.backend.compat.PyRepr;
import io.mealie.backend.config.MealieSettings;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.RequestValidationException;
import io.mealie.backend.web.validation.ValidationError;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Renders errors with the same body shape as the Python backend so the diff test can compare them. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final MealieSettings settings;
    private final TrailingSlashRedirect trailingSlashRedirect;

    public ApiExceptionHandler(MealieSettings settings, TrailingSlashRedirect trailingSlashRedirect) {
        this.settings = settings;
        this.trailingSlashRedirect = trailingSlashRedirect;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        e.headers().forEach(response::header);
        return response.body(Map.of("detail", e.body()));
    }

    /**
     * A 422 in one of two shapes, like Python: FastAPI's default {@code {"detail": [errors]}} in production, and
     * Mealie's debug handler (mealie/routes/handlers.py) otherwise, whose message is
     * {@code str(RequestValidationError)} flattened onto one line.
     */
    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<Object> handleValidation(RequestValidationException e, HandlerMethod handler,
            HttpServletRequest request) {
        if (settings.production() && !settings.testing()) {
            List<Map<String, Object>> detail = new ArrayList<>();
            for (ValidationError error : e.errors()) {
                detail.add(errorDict(error, error.loc(), jsonInput(error.input())));
            }
            return ResponseEntity.unprocessableContent().body(Map.of("detail", detail));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status_code", 422);
        body.put("message", debugMessage(e.errors(), handler.getMethodAnnotation(PythonEndpoint.class), request));
        body.put("data", null);
        return ResponseEntity.unprocessableContent().body(body);
    }

    /** Starlette answers any unhandled exception with a plain-text 500. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<String> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.internalServerError()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body("Internal Server Error");
    }

    private String debugMessage(List<ValidationError> errors, PythonEndpoint endpoint, HttpServletRequest request) {
        StringBuilder message = new StringBuilder()
                .append(errors.size()).append(" validation error").append(errors.size() == 1 ? "" : "s").append(":\n");
        for (ValidationError error : errors) {
            Object loc = new PyRepr.Tuple(error.loc());
            message.append("  ").append(PyRepr.repr(errorDict(error, loc, error.input()))).append('\n');
        }
        if (endpoint != null) {
            String file = settings.baseDir().resolve(endpoint.file()).toString();
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            message.append("\n  File \"").append(file).append("\", line ").append(endpoint.line())
                    .append(", in ").append(endpoint.function())
                    .append("\n    ").append(request.getMethod()).append(' ').append(pattern);
        }
        return message.toString().stripTrailing().replace("\n", " ").replace("   ", " ");
    }

    private static Map<String, Object> errorDict(ValidationError error, Object loc, Object input) {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("type", error.type());
        dict.put("loc", loc);
        dict.put("msg", error.msg());
        dict.put("input", input);
        if (error.ctx() != null) {
            dict.put("ctx", error.ctx());
        }
        return dict;
    }

    /** jsonable_encoder() turns a bytes input into text. */
    private static Object jsonInput(Object input) {
        return input instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : input;
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return redirectOrNotFound(request).orElseGet(() -> super.handleNoHandlerFoundException(ex, headers, status,
                request));
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return redirectOrNotFound(request).orElseGet(() -> super.handleNoResourceFoundException(ex, headers, status,
                request));
    }

    private Optional<ResponseEntity<Object>> redirectOrNotFound(WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        return trailingSlashRedirect.target(servletRequest)
                .map(location -> ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                        .header(HttpHeaders.LOCATION, location).build());
    }

    /** Spring MVC's own errors (unknown path, wrong method, ...); Starlette answers these with the reason phrase. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
            WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String detail = status != null ? status.getReasonPhrase() : String.valueOf(statusCode.value());
        return new ResponseEntity<>(Map.of("detail", detail), headers, statusCode);
    }
}
