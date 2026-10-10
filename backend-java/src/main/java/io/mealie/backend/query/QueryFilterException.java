package io.mealie.backend.query;

import io.mealie.backend.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A ValueError from Python's query filter or order-by handling, which Python answers with a 400 and its message. */
public class QueryFilterException extends ApiException {

    public QueryFilterException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail, Map.of());
    }
}
