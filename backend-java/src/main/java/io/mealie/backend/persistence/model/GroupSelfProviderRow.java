package io.mealie.backend.persistence.model;

import java.util.UUID;

/** Only public provider summary fields are selected; API keys never leave the database query. */
public record GroupSelfProviderRow(UUID id, String name) {
}
