package io.mealie.backend.query;

import io.mealie.backend.compat.PyStr;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subset of {@code dateutil.parser.parse()} that query filters use in practice: ISO 8601 dates and datetimes
 * (what the frontend's filter builder and {@code $NOW} produce), with '-', '/' or no date separators, 'T' or a space
 * before the time, optional seconds and fraction, and an optional 'Z' / 'UTC' / numeric offset. dateutil accepts far
 * more free-form text; anything outside this subset is reported as an unknown format.
 */
final class PyDateParser {

    record Parsed(LocalDateTime local, ZoneOffset offset) {
    }

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})([-/]?)(\\d{1,2})\\2(\\d{1,2})"
                    + "(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?"
                    + "\\s*(Z|UTC|GMT|[+-]\\d{2}(?::?\\d{2})?)?)?");

    private PyDateParser() {
    }

    /** Null when the value isn't in a supported format or isn't a valid date. */
    static Parsed parse(String value) {
        Matcher m = ISO.matcher(PyStr.strip(value));
        if (!m.matches()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            if (m.group(5) == null) {
                return new Parsed(date.atStartOfDay(), null);
            }
            int nanos = 0;
            if (m.group(8) != null) {
                String fraction = (m.group(8) + "000000").substring(0, 6);
                nanos = Integer.parseInt(fraction) * 1000;
            }
            LocalDateTime local = date.atTime(Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                    m.group(7) == null ? 0 : Integer.parseInt(m.group(7)), nanos);
            return new Parsed(local, offset(m.group(9)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static ZoneOffset offset(String zone) {
        if (zone == null) {
            return null;
        }
        if (zone.equals("Z") || zone.equals("UTC") || zone.equals("GMT")) {
            return ZoneOffset.UTC;
        }
        String digits = zone.substring(1).replace(":", "");
        int hours = Integer.parseInt(digits.substring(0, 2));
        int minutes = digits.length() > 2 ? Integer.parseInt(digits.substring(2)) : 0;
        int sign = zone.charAt(0) == '-' ? -1 : 1;
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
    }
}
