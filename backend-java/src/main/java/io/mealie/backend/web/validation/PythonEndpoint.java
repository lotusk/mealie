package io.mealie.backend.web.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The Python route a migrated controller method replaces. When PRODUCTION is false (or TESTING is true), Mealie's
 * debug handler puts the endpoint's source location into every 422 message ({@code File "...", line N, in fn}), so
 * Java needs it to produce the same message. {@code file} is relative to the repository root.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PythonEndpoint {

    String file();

    /** The first line of the function's source, decorators included (what inspect.getsourcelines() reports). */
    int line();

    String function();
}
