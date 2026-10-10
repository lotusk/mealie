package io.mealie.backend.auth;

import io.mealie.backend.web.ApiException;
import org.springframework.security.core.AuthenticationException;

/** Carries Mealie's exact API error through Spring Security's authentication contract. */
final class MealieAuthenticationException extends AuthenticationException {

    private final ApiException apiException;

    MealieAuthenticationException(ApiException apiException) {
        super(apiException.detail(), apiException);
        this.apiException = apiException;
    }

    ApiException apiException() {
        return apiException;
    }
}
