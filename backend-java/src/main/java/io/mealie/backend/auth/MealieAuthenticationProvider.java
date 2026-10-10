package io.mealie.backend.auth;

import io.mealie.backend.web.ApiException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/** Adapts Mealie's existing JWT and long-lived-token rules to Spring Security. */
@Component
public class MealieAuthenticationProvider implements AuthenticationProvider {

    private final AuthService authService;

    public MealieAuthenticationProvider(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        try {
            AuthUser user = authService.authenticate((String) authentication.getCredentials());
            return MealieAuthenticationToken.authenticated(user);
        } catch (ApiException e) {
            throw new MealieAuthenticationException(e);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return MealieAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
