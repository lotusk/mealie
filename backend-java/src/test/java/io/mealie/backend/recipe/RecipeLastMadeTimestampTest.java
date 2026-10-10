package io.mealie.backend.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** Independent expected values captured from Python/Pydantic on the experiment's original baseline. */
class RecipeLastMadeTimestampTest {
    private void parses(Object input, String expected) {
        assertThat(RecipeLastMadeTimestamp.parse(input)).isEqualTo(OffsetDateTime.parse(expected));
    }
    @Test void normalizesNaiveDatesAndOffsetsWithoutUsingTheHostTimezone() {
        parses("2026-10-09", "2026-10-09T00:00:00Z");
        parses("2026-10-09T12:34:56", "2026-10-09T12:34:56Z");
        parses("2026-10-09T12:34:56+13:00", "2026-10-08T23:34:56Z");
        parses("2026-10-09T12:34:56+23:59", "2026-10-08T12:35:56Z");
        parses("2026-10-09_12:34:56-0530", "2026-10-09T18:04:56Z");
    }
    @Test void truncatesIsoFractionToPythonMicrosecondsButRoundsNumericStringEpochs() {
        parses("2026-10-09t12:34:56.123456789z", "2026-10-09T12:34:56.123456Z");
        parses(".5", "1970-01-01T00:00:00.5Z");
        parses("1.", "1970-01-01T00:00:01Z");
        parses("20000000000.1", "1970-08-20T11:33:20.000100Z");
        parses("1791549296.1234567", "2026-10-09T12:34:56.123457Z");
        parses("2026-10-09 12:34", "2026-10-09T12:34:00Z");
        parses("2026-10-09T12:34:56,123", "2026-10-09T12:34:56.123Z");
    }
    @Test void preservesSecondsMillisecondsAndTheCapturedNegativeFloatBehavior() {
        parses(1791549296L, "2026-10-09T12:34:56Z");
        parses(1791549296123L, "2026-10-09T12:34:56.123Z");
        parses(-1.25, "1969-12-31T23:59:58.250000Z");
        parses("-1.25", "1969-12-31T23:59:58.750000Z");
        parses(-20000000001.25, "1969-05-14T12:26:39.998250Z");
        parses(20000000000.1, "2603-10-11T11:33:20.000100Z");
        parses(20000000000L, "2603-10-11T11:33:20Z");
        parses(20000000001L, "1970-08-20T11:33:20.001Z");
    }
    @Test void retainsOriginalValidationTypesAndReasons() {
        invalid("2026-02-30T00:00:00Z", "datetime_from_date_parsing", "day value is outside expected range");
        invalid("2026-10-09T24:00:00Z", "datetime_from_date_parsing", "unexpected extra characters at the end of the input");
        invalid("not-a-timestamp", "datetime_from_date_parsing", "invalid character in year");
        invalid("0000-01-01", "datetime_parsing", "year 0 is out of range");
        invalid(new BigInteger("1000000000000000000"), "datetime_parsing", "dates after 9999 are not supported as unix timestamps");
        invalid("123456789012345678901234567890", "datetime_from_date_parsing", "invalid date separator, expected `-`");
        invalid(true, "datetime_type", null);
        invalid(null, "datetime_type", null);
    }
    private void invalid(Object input, String type, String reason) {
        assertThatThrownBy(() -> RecipeLastMadeTimestamp.parse(input)).isInstanceOfSatisfying(RecipeLastMadeTimestamp.Invalid.class, error -> {
            assertThat(error.type).isEqualTo(type);
            assertThat(error.error).isEqualTo(reason);
        });
    }
}
