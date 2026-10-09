package io.mealie.backend.tags;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Starlette redirects trailing slashes with 307, preserving the method and query string. */
@Component
public class TagSlashFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/organizers/tags/") && path.endsWith("/")) {
            String url = request.getRequestURL().toString();
            response.setStatus(307);
            response.setHeader("Location", url.substring(0, url.length() - 1)
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
            return;
        }
        chain.doFilter(request, response);
    }
}
