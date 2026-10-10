package io.mealie.backend.recipe;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** ISO, named-month, slash, compact, RFC and partial date inputs accepted by Python query filters. */
final class RecipeFilterDate {
    private RecipeFilterDate() { }

    static OffsetDateTime parse(String raw) {
        String value = raw.strip().replaceAll("(?i)(?<=\\d)(st|nd|rd|th)\\b", "")
                .replaceAll("(?i)\\b(?:of|at|on|and|ad)\\b", " ").replaceAll("\\s+", " ").strip();
        var compact = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})[Tt](\\d{2})(\\d{2})(\\d{2})?(.*)$").matcher(value);
        if (compact.matches()) value = compact.group(1) + "-" + compact.group(2) + "-" + compact.group(3)
                + "T" + compact.group(4) + ":" + compact.group(5)
                + (compact.group(6) == null ? "" : ":" + compact.group(6)) + compact.group(7);
        String iso = value.replace(' ', 'T');
        if (iso.matches(".*[+-]\\d{2}$")) iso += ":00";
        try { return OffsetDateTime.parse(iso); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(iso).atOffset(ZoneOffset.UTC); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME); } catch (DateTimeParseException ignored) { }
        var units = Pattern.compile("(?i)(?<![\\d.])(?:(\\d+(?:[.,]\\d+)?)h)?(?:\\s*(\\d+(?:[.,]\\d+)?)m)?(?:\\s*(\\d+(?:[.,]\\d+)?)s)?$").matcher(value);
        if (units.find() && (units.group(1) != null || units.group(2) != null || units.group(3) != null)) {
            LocalDate date = units.start() == 0 ? LocalDate.now() : dateOnly(value.substring(0, units.start()).strip());
            try {
                java.math.BigDecimal seconds = java.math.BigDecimal.ZERO;
                for (int i = 1; i <= 3; i++) if (units.group(i) != null)
                    seconds = seconds.add(new java.math.BigDecimal(units.group(i).replace(',', '.'))
                            .multiply(java.math.BigDecimal.valueOf(i == 1 ? 3600 : i == 2 ? 60 : 1)));
                long micros = seconds.movePointRight(6).setScale(0, java.math.RoundingMode.DOWN).longValueExact();
                return OffsetDateTime.of(date, LocalTime.ofNanoOfDay(Math.multiplyExact(micros, 1000)), ZoneOffset.UTC);
            } catch (ArithmeticException | java.time.DateTimeException error) { throw invalid(raw); }
        }
        var time = Pattern.compile("(?i)(\\d{1,2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?|\\d{1,2}(?=\\s*(?:AM|PM)))\\s*(AM|PM)?\\s*((?-i:(?:UTC|GMT)[+-]\\d{1,2}(?::?\\d{2})?|Z|z|UTC|GMT|[A-Z]{2,5}|[+-]\\d{2}(?::?\\d{2})?))?$").matcher(value);
        if (time.find()) {
            String datePart = value.substring(0, time.start()).strip().replaceFirst("(?i)(?:\\s+at|T)$", "").strip();
            LocalDate date = datePart.isEmpty() ? LocalDate.now() : dateOnly(datePart);
            String clock = time.group(1).replace(',', '.');
            if (!clock.contains(":")) clock += ":00";
            if (clock.split(":")[0].length() == 1) clock = "0" + clock;
            LocalTime local = LocalTime.parse(clock);
            if (time.group(2) != null) {
                if (local.getHour() > 12) throw invalid(raw);
                local = local.withHour(local.getHour() % 12 + (time.group(2).equalsIgnoreCase("PM") ? 12 : 0));
            }
            String offset = time.group(3);
            ZoneOffset zone = ZoneOffset.UTC;
            if (offset != null) {
                String numeric = offset.toUpperCase(Locale.ROOT);
                if (numeric.startsWith("UTC") || numeric.startsWith("GMT")) {
                    numeric = numeric.substring(3);
                    if (!numeric.isEmpty()) numeric = (numeric.charAt(0) == '+' ? "-" : "+") + numeric.substring(1);
                }
                // dateutil ignores unknown abbreviations (e.g. PST) without a tzinfos mapping.
                if (numeric.startsWith("+") || numeric.startsWith("-")) {
                    if (numeric.length() == 2) numeric = numeric.charAt(0) + "0" + numeric.substring(1);
                    zone = ZoneOffset.of(numeric.length() == 3 ? numeric + ":00" : numeric);
                }
            }
            return OffsetDateTime.of(date, local, zone);
        }
        return dateOnly(value).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static LocalDate dateOnly(String value) {
        for (java.time.DayOfWeek weekday : java.time.DayOfWeek.values()) {
            String name = weekday.name();
            if (value.equalsIgnoreCase(name) || value.equalsIgnoreCase(name.substring(0, 3)))
                return LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(weekday));
        }
        value = value.replaceFirst("(?i)^(mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?),?\\s+", "");
        for (String format : List.of("uuuu-MM-dd", "uuuu-M-d", "uuuu/M/d", "M/d/uuuu", "M-d-uuuu", "M.d.uuuu",
                "uuuuMMdd", "MMMM d, uuuu", "MMM d, uuuu", "MMMM d uuuu", "MMM d uuuu", "d MMMM uuuu", "d MMM uuuu",
                "d-MMM-uuuu", "d-MMMM-uuuu", "MMMM-d-uuuu")) {
            try { return LocalDate.parse(value, formatter(format)); } catch (DateTimeParseException ignored) { }
        }
        LocalDate today = LocalDate.now();
        for (String format : List.of("uuuu-MM", "uuuu/M", "MMMM uuuu", "MMM uuuu", "uuuu MMMM", "uuuu MMM")) {
            try {
                YearMonth month = YearMonth.parse(value, formatter(format));
                return month.atDay(Math.min(today.getDayOfMonth(), month.lengthOfMonth()));
            } catch (DateTimeParseException ignored) { }
        }
        if (value.matches("\\d{4}")) {
            YearMonth month = YearMonth.of(Integer.parseInt(value), today.getMonthValue());
            return month.atDay(Math.min(today.getDayOfMonth(), month.lengthOfMonth()));
        }
        return flexibleDate(value, today);
    }

    private static LocalDate flexibleDate(String value, LocalDate today) {
        String[] tokens = value.strip().split("[\\s,./-]+");
        if (tokens.length > 3 || tokens.length == 0) throw invalid(value);
        int monthIndex = -1, month = 0;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].toLowerCase(Locale.ROOT);
            for (java.time.Month candidate : java.time.Month.values()) {
                String name = candidate.name().toLowerCase(Locale.ROOT);
                if (token.equals(name) || token.equals(name.substring(0, 3)) || (candidate == java.time.Month.SEPTEMBER && token.equals("sept"))) {
                    if (monthIndex >= 0) throw invalid(value);
                    monthIndex = i;
                    month = candidate.getValue();
                }
            }
            if (i != monthIndex && !token.matches("\\d{1,4}")) throw invalid(value);
        }
        int year = today.getYear(), day = today.getDayOfMonth();
        boolean defaultDay = true;
        try {
            if (monthIndex >= 0) {
                var numbers = new java.util.ArrayList<String>();
                for (int i = 0; i < tokens.length; i++) if (i != monthIndex) numbers.add(tokens[i]);
                if (numbers.size() == 1) {
                    int number = Integer.parseInt(numbers.getFirst());
                    if (numbers.getFirst().length() > 2 || number > 31) year = year(number, today);
                    else { day = number; defaultDay = false; }
                } else if (numbers.size() == 2) {
                    int first = Integer.parseInt(numbers.get(0)), second = Integer.parseInt(numbers.get(1));
                    if (numbers.get(0).length() > 2 || first > 31) { year = year(first, today); day = second; }
                    else { day = first; year = year(second, today); }
                    defaultDay = false;
                }
            } else if (tokens.length == 1) {
                int number = Integer.parseInt(tokens[0]);
                month = today.getMonthValue();
                if (tokens[0].length() > 2 || number > 31) year = year(number, today);
                else { day = number; defaultDay = false; }
            } else {
                int first = Integer.parseInt(tokens[0]), second = Integer.parseInt(tokens[1]);
                if (tokens.length == 2) {
                    if (tokens[0].length() > 2 || first > 31) { year = year(first, today); month = second; }
                    else if (first > 12) { day = first; month = second; defaultDay = false; }
                    else { month = first; day = second; defaultDay = false; }
                } else {
                    int third = Integer.parseInt(tokens[2]);
                    if (tokens[0].length() > 2 || first > 31) { year = year(first, today); month = second; day = third; }
                    else { year = year(third, today); month = first > 12 ? second : first; day = first > 12 ? first : second; }
                    defaultDay = false;
                }
            }
            YearMonth ym = YearMonth.of(year, month);
            return ym.atDay(defaultDay ? Math.min(day, ym.lengthOfMonth()) : day);
        } catch (java.time.DateTimeException | NumberFormatException error) { throw invalid(value); }
    }

    private static int year(int number, LocalDate today) {
        if (number >= 100) return number;
        int result = today.getYear() / 100 * 100 + number;
        if (result >= today.getYear() + 50) result -= 100;
        else if (result < today.getYear() - 50) result += 100;
        return result;
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }
    private static DateTimeParseException invalid(String value) { return new DateTimeParseException("Unknown date", value, 0); }
}
