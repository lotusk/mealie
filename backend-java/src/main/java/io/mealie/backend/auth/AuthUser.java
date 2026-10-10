package io.mealie.backend.auth;

import java.util.UUID;

/**
 * The authenticated user, as resolved from a Mealie JWT. Declare it as a controller parameter to require auth.
 *
 * <p>{@code integrationId} is the token's {@code integration_id} claim ({@code "generic"} when absent), which Python
 * reads with get_integration_id() and attaches to the events a request publishes.
 */
public record AuthUser(
        UUID id,
        String username,
        UUID groupId,
        UUID householdId,
        boolean admin,
        boolean canOrganize,
        String integrationId) {

    /** DEFAULT_INTEGRATION_ID in mealie/schema/user/user.py. */
    public static final String DEFAULT_INTEGRATION_ID = "generic";

    public AuthUser withIntegrationId(String value) {
        return new AuthUser(id, username, groupId, householdId, admin, canOrganize, value);
    }
}
