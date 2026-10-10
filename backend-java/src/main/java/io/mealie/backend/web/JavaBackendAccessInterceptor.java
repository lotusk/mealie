package io.mealie.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Logs a stable marker for every request that reaches a Java controller. */
@Component
public class JavaBackendAccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JavaBackendAccessInterceptor.class);
    private static final DateTimeFormatter ACCESS_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;

    public JavaBackendAccessInterceptor() {
        this(Clock.systemDefaultZone());
    }

    JavaBackendAccessInterceptor(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod) {
            log.info("{}", accessLog(request));
        }
        return true;
    }

    String accessLog(HttpServletRequest request) {
        return "JAVA_BACKEND_ACCESS time=%s method=%s path=%s".formatted(
                LocalTime.now(clock).format(ACCESS_TIME), request.getMethod(), safePath(request.getRequestURI()));
    }

    private static String safePath(String path) {
        return path.replace('\r', '_').replace('\n', '_');
    }
}
