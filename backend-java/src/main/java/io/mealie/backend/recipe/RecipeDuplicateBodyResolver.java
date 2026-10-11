package io.mealie.backend.recipe;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
@Component
public class RecipeDuplicateBodyResolver implements HandlerMethodArgumentResolver {
    private final RecipeDuplicateValidation validation;
    public RecipeDuplicateBodyResolver(RecipeDuplicateValidation validation) { this.validation = validation; }
    @Override public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == RecipeDuplicateBody.class; }
    @Override public RecipeDuplicateBody resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binder) throws Exception {
        return validation.parse(request.getNativeRequest(HttpServletRequest.class));
    }
}
