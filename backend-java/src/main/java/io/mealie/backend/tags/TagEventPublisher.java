package io.mealie.backend.tags;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.mealie.backend.auth.AuthTokens;
import io.mealie.backend.auth.JwtSecret;
import io.mealie.backend.config.MealieEnv;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Uses Python's existing translated webhook/Apprise dispatch until that shared service is migrated. */
@Component
public class TagEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(TagEventPublisher.class);
    private final JwtSecret secret;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public TagEventPublisher(JwtSecret secret, MealieEnv env) {
        this.secret = secret;
        endpoint = URI.create(env.get("PYTHON_API_URL", "http://localhost:9000").replaceAll("/$", "") + "/api/internal/java/tag-events");
    }
    public void publish(String operation, Map<String, Object> tag, HttpServletRequest request) {
        try {
            String signature = JWT.create().withAudience("mealie-java-tag-event").withExpiresAt(Instant.now().plusSeconds(30))
                    .withClaim("operation", operation).withClaim("id", tag.get("id").toString())
                    .withClaim("groupId", tag.get("groupId").toString()).withClaim("name", tag.get("name").toString())
                    .withClaim("slug", tag.get("slug").toString()).sign(Algorithm.HMAC256(secret.current().orElseThrow()));
            HttpRequest.Builder outbound = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + AuthTokens.extract(request).orElseThrow())
                    .header("X-Mealie-Tag-Event", signature).POST(HttpRequest.BodyPublishers.noBody());
            String language = request.getHeader("Accept-Language");
            if (language != null) outbound.header("Accept-Language", language);
            var response = client.send(outbound.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204) log.error("Python tag event dispatch returned HTTP {}", response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Tag event dispatch interrupted", e);
        } catch (Exception e) {
            // Mutations have committed; notification failure must not turn a successful save into an API failure.
            log.error("Tag event dispatch failed", e);
        }
    }
}
