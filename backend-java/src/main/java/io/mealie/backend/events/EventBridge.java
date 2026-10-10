package io.mealie.backend.events;

import io.mealie.backend.auth.JwtSecret;
import io.mealie.backend.config.MealieEnv;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes Mealie events (the notifications users subscribe to under Household > Notifiers) through the Python
 * backend, which owns the event bus and its Apprise integration. Python exposes {@code POST /api/internal/events}
 * (mealie/routes/internal/controller_events.py) for this; requests are signed with a key derived from the secret
 * both backends share, and the gateway refuses that path from outside.
 *
 * <p>Like Python's BackgroundTasks, delivery happens after the response is decided, and a failure is only logged:
 * the request that caused the event has already succeeded.
 */
@Component
public class EventBridge {

    private static final Logger log = LoggerFactory.getLogger(EventBridge.class);

    /** Must match INTERNAL_EVENTS_KEY_CONTEXT in mealie/routes/internal/controller_events.py. */
    static final String KEY_CONTEXT = "mealie-internal-events-v1";
    public static final String SIGNATURE_HEADER = "X-Mealie-Signature";

    /** A notification message, rendered and translated by Python: {@code t(key, **params)}. */
    public record Message(String key, Map<String, String> params, String urlType, String urlSlug) {
    }

    /** One event: what EventBusService.dispatch() takes, with the message still to be rendered. */
    public record Event(
            String integrationId,
            UUID groupId,
            UUID householdId,
            String eventType,
            Map<String, Object> documentData,
            Message message) {
    }

    private final JwtSecret secret;
    private final URI endpoint;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            // uvicorn rejects the h2c upgrade the client would otherwise attempt.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EventBridge(JwtSecret secret, MealieEnv env) {
        this.secret = secret;
        String base = env.get("MEALIE_PYTHON_URL", "http://127.0.0.1:" + env.get("API_PORT", "9000"));
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/api/internal/events");
    }

    /** Queues the event; {@code acceptLanguage} is the original request's header, which selects the language. */
    public void publish(Event event, String acceptLanguage) {
        byte[] body = json.writeValueAsBytes(payload(event));
        executor.execute(() -> send(body, acceptLanguage, event.eventType()));
    }

    private Map<String, Object> payload(Event event) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("key", event.message().key());
        message.put("params", event.message().params());
        if (event.message().urlType() != null) {
            message.put("url", Map.of("type", event.message().urlType(), "slug", event.message().urlSlug()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issuedAt", Instant.now().toString());
        payload.put("integrationId", event.integrationId());
        payload.put("groupId", event.groupId());
        payload.put("householdId", event.householdId());
        payload.put("eventType", event.eventType());
        payload.put("documentData", event.documentData());
        payload.put("message", message);
        return payload;
    }

    private void send(byte[] body, String acceptLanguage, String eventType) {
        try {
            String key = secret.current().orElseThrow(() -> new IllegalStateException("no secret available"));
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header(SIGNATURE_HEADER, sign(key, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (acceptLanguage != null && !acceptLanguage.isBlank()) {
                request.header("Accept-Language", acceptLanguage);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Python rejected event {}: HTTP {} {}", eventType, response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not deliver event {} to {}: {}", eventType, endpoint, e.toString());
        }
    }

    /** hex(HMAC-SHA256(HMAC-SHA256(secret, KEY_CONTEXT), body)). */
    static String sign(String secret, byte[] body) throws GeneralSecurityException {
        byte[] key = hmac(secret.getBytes(StandardCharsets.UTF_8), KEY_CONTEXT.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hmac(key, body));
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
