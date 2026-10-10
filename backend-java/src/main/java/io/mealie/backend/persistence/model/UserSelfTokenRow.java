package io.mealie.backend.persistence.model;

import java.time.OffsetDateTime;

/** Public metadata only; the long-lived token's secret is never selected. */
public record UserSelfTokenRow(Integer id, String name, OffsetDateTime createdAt) {
}
