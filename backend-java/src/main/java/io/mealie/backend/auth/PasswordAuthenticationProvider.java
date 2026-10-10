package io.mealie.backend.auth;

import io.mealie.backend.web.ApiException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/** Performs local password login through Spring Security's AuthenticationManager. */
@Component
public class PasswordAuthenticationProvider implements AuthenticationProvider {

    private final PasswordLoginService service;

    public PasswordAuthenticationProvider(PasswordLoginService service) {
        this.service = service;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        PasswordCredentialsAuthentication credentials = (PasswordCredentialsAuthentication) authentication;
        try {
            PasswordLoginService.LoginResult result = service.login(
                    credentials.getPrincipal(), credentials.getCredentials(), credentials.rememberMe());
            return PasswordCredentialsAuthentication.authenticated(
                    credentials.getPrincipal(), credentials.rememberMe(), result);
        } catch (ApiException e) {
            throw new MealieAuthenticationException(e);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PasswordCredentialsAuthentication.class.isAssignableFrom(authentication);
    }
}
