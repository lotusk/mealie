package io.mealie.backend.auth;

import io.mealie.backend.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller require auth by declaring an {@link AuthUser} parameter, the way Python routes declare
 * {@code Depends(get_current_user)}. Authentication itself is performed by Spring Security; this resolver only
 * exposes its principal to migrated controllers and preserves Python's 401 response shape.
 */
@Component
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AuthUser.class;
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthUser user) {
            return user;
        }

        RuntimeException failure = MealieAuthenticationFilter.failure(request);
        if (failure instanceof ApiException apiException) {
            throw apiException;
        }
        throw AuthService.credentialsException();
    }
}
