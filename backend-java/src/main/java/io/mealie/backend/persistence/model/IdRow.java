package io.mealie.backend.persistence.model;

import java.util.UUID;

/** A single id column, read with the engine-independent UUID type handler. */
public record IdRow(UUID id) {
}
