package io.mealie.backend.recipe;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.config.MealieEnv;
import io.mealie.backend.web.ApiException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Sends only committed recipe event metadata; Python never receives a creation request or writes its rows. */
@Component
public class RecipeCreatedPublisher {
    private static final Logger log = LoggerFactory.getLogger(RecipeCreatedPublisher.class);
    private final ObjectMapper json;
    private final String key;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RecipeCreatedPublisher(ObjectMapper json, MealieEnv env) {
        this.json = json;
        key = env.get("RECIPE_EVENT_ADAPTER_KEY", "");
        endpoint = URI.create(env.get("RECIPE_EVENT_ADAPTER_URL", "http://localhost:9000/internal/recipe-created"));
    }

    void requireConfigured() {
        if (key.getBytes(StandardCharsets.UTF_8).length < 32 || !java.util.Set.of("http", "https").contains(endpoint.getScheme())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Recipe event adapter is not configured", java.util.Map.of());
        }
    }

    void publish(UUID recipeId, AuthUser user, String locale, String integrationId) {
        UUID eventId = UUID.randomUUID();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", eventId.toString());
        payload.put("timestamp", Instant.now().toString());
        payload.put("recipeId", recipeId.toString());
        payload.put("userId", user.id().toString());
        payload.put("groupId", user.groupId().toString());
        payload.put("householdId", user.householdId().toString());
        payload.put("locale", locale);
        payload.put("integrationId", integrationId);
        byte[] body = json.writeValueAsBytes(payload);
        // Match FastAPI BackgroundTasks: notification delivery is best effort after creation and cannot roll it back.
        executor.submit(() -> {
            try {
                String timestamp = Long.toString(Instant.now().getEpochSecond());
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("X-Mealie-Event-Time", timestamp)
                        .header("X-Mealie-Event-Signature", signature(key, timestamp, body))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 204) {
                    log.error("RECIPE_CREATED_EVENT_FAILED event={} recipe={} status={}", eventId, recipeId, response.statusCode());
                } else {
                    log.info("RECIPE_CREATED_EVENT_ACCEPTED event={} recipe={}", eventId, recipeId);
                }
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                log.error("RECIPE_CREATED_EVENT_FAILED event={} recipe={} error={}", eventId, recipeId, failure.getClass().getSimpleName());
            }
        });
    }

    static String signature(String key, String timestamp, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @PreDestroy void shutdown() { executor.close(); }
}
