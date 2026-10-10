package io.mealie.backend.compat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * How Python renders the UTC datetimes Mealie stores. Both forms drop the fraction when it is zero and otherwise
 * print microseconds.
 */
public final class PyDateTime {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private PyDateTime() {
    }

    /** Pydantic's JSON form of an aware UTC datetime: {@code 2026-10-09T21:57:01.319345Z}. */
    public static String pydantic(OffsetDateTime value) {
        return value == null ? null : local(value) + "Z";
    }

    /** {@code datetime.isoformat()} of an aware UTC datetime: {@code 2026-10-09T21:57:01.319345+00:00}. */
    public static String isoformat(OffsetDateTime value) {
        return value == null ? null : local(value) + "+00:00";
    }

    public static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String local(OffsetDateTime value) {
        OffsetDateTime utc = value.withOffsetSameInstant(ZoneOffset.UTC);
        int micros = utc.getNano() / 1000;
        return micros == 0 ? SECONDS.format(utc) : MICROS.format(utc.withNano(micros * 1000));
    }
}
