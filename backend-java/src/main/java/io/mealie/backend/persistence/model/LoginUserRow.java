package io.mealie.backend.persistence.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Fields needed for Mealie's password login and lockout rules. */
public record LoginUserRow(UUID id, String email, String password, String authMethod,
        Integer loginAttempts, OffsetDateTime lockedAt) {
}
