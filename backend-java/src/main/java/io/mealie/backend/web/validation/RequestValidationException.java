package io.mealie.backend.web.validation;

import java.util.List;

/** FastAPI's RequestValidationError: the request didn't match the endpoint's parameters. Rendered as a 422. */
public class RequestValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public RequestValidationException(List<ValidationError> errors) {
        super(errors.size() + " validation error(s)", null, false, false);
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }
}
