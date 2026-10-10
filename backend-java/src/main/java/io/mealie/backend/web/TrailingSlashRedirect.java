package io.mealie.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Starlette's {@code redirect_slashes}: when nothing matches a path that ends in '/', but a route (for any method)
 * matches it without the slashes, the answer is a 307 to that path rather than a 404.
 */
@Component
public class TrailingSlashRedirect {

    private final ObjectProvider<RequestMappingHandlerMapping> mapping;

    public TrailingSlashRedirect(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping) {
        this.mapping = mapping;
    }

    /** The absolute URL to redirect to, built like Starlette's URL(scope=...): request scheme and Host header. */
    public Optional<String> target(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.endsWith("/") || path.equals("/")) {
            return Optional.empty();
        }
        String stripped = path.replaceAll("/+$", "");
        PathContainer container = PathContainer.parsePath(stripped);
        boolean matches = mapping.getObject().getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(condition -> condition != null)
                .flatMap(condition -> condition.getPatterns().stream())
                .anyMatch((PathPattern pattern) -> pattern.matches(container));
        if (!matches) {
            return Optional.empty();
        }
        String host = request.getHeader("Host");
        if (host == null) {
            int port = request.getServerPort();
            boolean defaultPort = port == 80 && request.getScheme().equals("http")
                    || port == 443 && request.getScheme().equals("https");
            host = request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        String query = request.getQueryString();
        return Optional.of(request.getScheme() + "://" + host + stripped + (query != null ? "?" + query : ""));
    }
}
