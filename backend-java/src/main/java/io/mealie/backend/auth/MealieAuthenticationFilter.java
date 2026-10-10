package io.mealie.backend.auth;

import io.mealie.backend.web.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates Mealie bearer/cookie tokens once and publishes the user through Spring Security. */
public class MealieAuthenticationFilter extends OncePerRequestFilter {

    private static final String FAILURE_ATTRIBUTE = MealieAuthenticationFilter.class.getName() + ".failure";

    private final AuthenticationManager authenticationManager;

    public MealieAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthTokens.extract(request).ifPresent(token -> authenticate(request, token));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String rawToken) {
        try {
            Authentication authentication = authenticationManager
                    .authenticate(MealieAuthenticationToken.unauthenticated(rawToken));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (MealieAuthenticationException e) {
            request.setAttribute(FAILURE_ATTRIBUTE, e.apiException());
        } catch (DataAccessException e) {
            request.setAttribute(FAILURE_ATTRIBUTE, e);
        }
    }

    public static RuntimeException failure(HttpServletRequest request) {
        Object failure = request == null ? null : request.getAttribute(FAILURE_ATTRIBUTE);
        return failure instanceof RuntimeException runtimeException ? runtimeException : null;
    }
}
