package io.mealie.backend.recipe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/** Pydantic's lax datetime inputs, reduced to the UTC microseconds used by SQLAlchemy NaiveDateTime. */
final class RecipeLastMadeTimestamp {
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    private static final Pattern CLOCK = Pattern.compile("[Tt _]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]+))?)?([Zz]|[+-][0-9]{2}:?[0-9]{2})?");
    private static final String EXTRA = "unexpected extra characters at the end of the input";
    private RecipeLastMadeTimestamp() { }

    static OffsetDateTime parse(Object value) {
        if (value instanceof Number number) return epoch(number, false);
        if (!(value instanceof String text)) throw new Invalid("datetime_type", null);
        if (NUMBER.matcher(text).matches()) {
            try { return epoch(new BigDecimal(text), true); }
            catch (Invalid error) {
                // Invalid numeric strings fall back to Pydantic's date parser; numeric JSON values do not.
                if (!error.error.startsWith("dates ")) throw error;
            }
        }
        if (text.length() < 10) throw dateError("input is too short");
        int year = digits(text, 0, 4, "invalid character in year");
        if (text.charAt(4) != '-' || text.charAt(7) != '-') throw dateError("invalid date separator, expected `-`");
        int month = digits(text, 5, 7, "invalid character in month");
        int day = digits(text, 8, 10, "invalid character in day");
        if (month < 1 || month > 12) throw dateError("month value is outside expected range of 1-12");
        LocalDate date;
        try { date = LocalDate.of(year, month, day); }
        catch (java.time.DateTimeException error) { throw dateError("day value is outside expected range"); }
        if (year == 0) throw new Invalid("datetime_parsing", "year 0 is out of range");
        if (text.length() == 10) return date.atStartOfDay().atOffset(ZoneOffset.UTC);
        var clock = CLOCK.matcher(text.substring(10));
        if (!clock.matches()) throw dateError(EXTRA);
        int hour = Integer.parseInt(clock.group(1)), minute = Integer.parseInt(clock.group(2));
        int second = clock.group(3) == null ? 0 : Integer.parseInt(clock.group(3));
        String fraction = clock.group(4);
        int micros = fraction == null ? 0 : Integer.parseInt((fraction + "000000").substring(0, 6));
        int offset = 0;
        String zone = clock.group(5);
        if (zone != null && !zone.equalsIgnoreCase("Z")) {
            String compact = zone.replace(":", "");
            int hours = Integer.parseInt(compact.substring(1, 3)), minutes = Integer.parseInt(compact.substring(3));
            if (hours > 23 || minutes > 59) throw dateError(EXTRA);
            offset = (hours * 3600 + minutes * 60) * (compact.charAt(0) == '-' ? -1 : 1);
        }
        try {
            // Python accepts offsets beyond Java ZoneOffset's +/-18 hours, so subtract them before attaching UTC.
            return date.atTime(LocalTime.of(hour, minute, second, micros * 1000)).minusSeconds(offset).atOffset(ZoneOffset.UTC);
        } catch (java.time.DateTimeException error) { throw dateError(EXTRA); }
    }

    private static OffsetDateTime epoch(Number input, boolean string) {
        if (input instanceof Double number && Double.isNaN(number)) throw new Invalid("datetime_parsing", "NaN values not permitted");
        BigDecimal number;
        try { number = new BigDecimal(input.toString()); }
        catch (NumberFormatException error) { throw range(input.doubleValue() < 0); }
        boolean milliseconds = number.abs().compareTo(BigDecimal.valueOf(20_000_000_000L)) > 0;
        BigDecimal seconds;
        if (input instanceof Double && !string) {
            // Match speedate's float path, including its historical negative-fraction behavior.
            double raw = input.doubleValue();
            long whole = (long) raw;
            if (Math.abs(raw) > 1e17) throw range(raw < 0);
            long micros = Math.round(Math.abs(raw % 1) * (milliseconds ? 1000 : 1_000_000));
            seconds = BigDecimal.valueOf(whole);
            if (seconds.abs().compareTo(BigDecimal.valueOf(20_000_000_000L)) > 0) seconds = seconds.movePointLeft(3);
            if (raw < 0 && micros != 0) seconds = seconds.subtract(BigDecimal.valueOf(milliseconds ? .001 : 1));
            seconds = seconds.add(BigDecimal.valueOf(micros, 6));
        } else {
            seconds = milliseconds ? number.movePointLeft(3) : number;
        }
        try {
            long micros = seconds.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
            var result = OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000),
                    Math.floorMod(micros, 1_000_000) * 1000), ZoneOffset.UTC);
            if (result.getYear() < 0 || result.getYear() > 9999) throw range(number.signum() < 0);
            if (result.getYear() == 0) throw new Invalid("datetime_parsing", "year 0 is out of range");
            return result;
        } catch (ArithmeticException | java.time.DateTimeException error) { throw range(number.signum() < 0); }
    }

    private static int digits(String text, int begin, int end, String error) {
        String value = text.substring(begin, end);
        if (!value.matches("[0-9]+")) throw dateError(error);
        return Integer.parseInt(value);
    }
    private static Invalid dateError(String error) { return new Invalid("datetime_from_date_parsing", error); }
    private static Invalid range(boolean negative) { return new Invalid("datetime_parsing", negative
            ? "dates before 0000 are not supported as unix timestamps" : "dates after 9999 are not supported as unix timestamps"); }
    static final class Invalid extends RuntimeException {
        final String type;
        final String error;
        Invalid(String type, String error) { this.type = type; this.error = error; }
    }
}
