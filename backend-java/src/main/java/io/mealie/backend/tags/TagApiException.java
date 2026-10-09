package io.mealie.backend.tags;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

final class TagApiException extends RuntimeException {
    final HttpStatus status;
    final Object detail;
    TagApiException(HttpStatus status, Object detail) { this.status = status; this.detail = detail; }

    @org.springframework.core.annotation.Order(0)
    @RestControllerAdvice(assignableTypes = TagController.class)
    static class Handler {
        @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
        ResponseEntity<Object> invalidBody(org.springframework.http.converter.HttpMessageNotReadableException error) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", java.util.List.of(Map.of(
                    "type", "json_invalid", "loc", java.util.List.of("body"), "msg", "Invalid request body"))));
        }

        @ExceptionHandler(TagApiException.class)
        ResponseEntity<Object> handle(TagApiException error) {
            return ResponseEntity.status(error.status).body(Map.of("detail", error.detail));
        }
    }
}
