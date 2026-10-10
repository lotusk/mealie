package io.mealie.backend.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class JavaBackendAccessInterceptorTest {

    @Test
    void allowsControllerRequestsToContinue() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-10T01:02:03Z"), ZoneOffset.UTC);
        JavaBackendAccessInterceptor interceptor = new JavaBackendAccessInterceptor(clock);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/app/about");
        HandlerMethod handler = new HandlerMethod(this, getClass().getDeclaredMethod("controllerMethod"));

        assertEquals("JAVA_BACKEND_ACCESS time=01:02:03 method=GET path=/api/app/about",
                interceptor.accessLog(request));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler));
    }

    @SuppressWarnings("unused")
    private void controllerMethod() {
    }
}
