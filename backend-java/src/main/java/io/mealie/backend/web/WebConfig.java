package io.mealie.backend.web;

import io.mealie.backend.auth.AuthUserArgumentResolver;
import io.mealie.backend.recipe.RecipeCreateBodyResolver;
import io.mealie.backend.recipe.RecipeLastMadeBodyResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final AuthUserArgumentResolver authUserArgumentResolver;
    private final JavaBackendAccessInterceptor accessInterceptor;
    private final RecipeCreateBodyResolver recipeCreateBodyResolver;

    private final RecipeLastMadeBodyResolver recipeLastMadeBodyResolver;

    public WebConfig(AuthUserArgumentResolver authUserArgumentResolver,
            JavaBackendAccessInterceptor accessInterceptor, RecipeCreateBodyResolver recipeCreateBodyResolver,
            RecipeLastMadeBodyResolver recipeLastMadeBodyResolver) {
        this.authUserArgumentResolver = authUserArgumentResolver;
        this.accessInterceptor = accessInterceptor;
        this.recipeCreateBodyResolver = recipeCreateBodyResolver;
        this.recipeLastMadeBodyResolver = recipeLastMadeBodyResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(recipeCreateBodyResolver);
        resolvers.add(recipeLastMadeBodyResolver);
        resolvers.add(authUserArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessInterceptor).addPathPatterns("/**");
    }
}
