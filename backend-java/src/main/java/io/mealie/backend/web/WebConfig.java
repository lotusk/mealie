package io.mealie.backend.web;

import io.mealie.backend.auth.AuthUserArgumentResolver;
import io.mealie.backend.web.validation.PyRequestBodyArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final AuthUserArgumentResolver authUserArgumentResolver;
    private final PyRequestBodyArgumentResolver pyRequestBodyArgumentResolver;
    private final JavaBackendAccessInterceptor accessInterceptor;

    public WebConfig(AuthUserArgumentResolver authUserArgumentResolver,
            PyRequestBodyArgumentResolver pyRequestBodyArgumentResolver,
            JavaBackendAccessInterceptor accessInterceptor) {
        this.authUserArgumentResolver = authUserArgumentResolver;
        this.pyRequestBodyArgumentResolver = pyRequestBodyArgumentResolver;
        this.accessInterceptor = accessInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authUserArgumentResolver);
        resolvers.add(pyRequestBodyArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessInterceptor).addPathPatterns("/**");
    }
}
