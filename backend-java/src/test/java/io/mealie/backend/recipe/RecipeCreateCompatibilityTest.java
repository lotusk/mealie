package io.mealie.backend.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

/** Expectations captured from the current Python endpoint, not from Java's own implementation. */
class RecipeCreateCompatibilityTest {
    @Test void slugifyPreservesLegacyEntitiesQuotesAndNumericCommas() {
        assertThat(RecipeSlug.create("Café 重庆 & Soup")).isEqualTo("cafe-zhong-qing-soup");
        assertThat(RecipeSlug.create("Cr&egrave;me &#38; &#x41; &apos; Pie")).isEqualTo("cre-me-a-apos-pie");
        assertThat(RecipeSlug.create("Chef's \"Soup\" 1,000")).isEqualTo("chef-s-soup-1000");
        assertThat(RecipeSlug.create("A".repeat(500))).isEqualTo("a".repeat(250));
    }

    @Test void punctuationOnlyNamesHaveTheCapturedFastApiError() {
        assertThatThrownBy(() -> RecipeSlug.create("---")).isInstanceOfSatisfying(RecipeCreateFailure.class, error -> {
            assertThat(error.status).isEqualTo(400);
            assertThat(error.body).isEqualTo(Map.of("detail", new java.util.LinkedHashMap<>() {{
                put("message", "Unable to generate recipe slug"); put("error", true); put("exception", null);
            }}));
        });
    }

    @Test void normalizationTransliteratesBeforeReplacingPunctuation() {
        assertThat(RecipeSlug.normalize("Café — SOUP")).isEqualTo("cafe    soup");
        assertThat(RecipeSlug.normalize("A".repeat(500))).hasSize(255);
    }

    @Test void jsonHasPython314TrailingCommaOffsets() {
        assertThatThrownBy(() -> new PythonJson("{\"name\":\"x\",}").parse())
                .isInstanceOfSatisfying(PythonJson.Failure.class, error -> {
                    assertThat(error.position).isEqualTo(11);
                    assertThat(error.getMessage()).isEqualTo("Illegal trailing comma before end of object");
                });
        assertThatThrownBy(() -> new PythonJson("[1,]").parse())
                .isInstanceOfSatisfying(PythonJson.Failure.class, error -> {
                    assertThat(error.position).isEqualTo(2);
                    assertThat(error.getMessage()).isEqualTo("Illegal trailing comma before end of array");
                });
    }

    @Test void jsonPreservesDuplicateKeyAndArbitrarySizeIntegerBehavior() {
        assertThat(new PythonJson("{\"name\":1,\"name\":\"last\"}").parse()).isEqualTo(Map.of("name", "last"));
        assertThat(new PythonJson("123456789012345678901234567890").parse())
                .isEqualTo(new BigInteger("123456789012345678901234567890"));
    }

    @Test void floatingValidationValuesMatchPythonFormattingAndSignedZero() {
        assertThat(RecipeCreateValidation.repr(new PythonJson("1e20").parse())).isEqualTo("1e+20");
        assertThat(RecipeCreateValidation.repr(new PythonJson("1e-5").parse())).isEqualTo("1e-05");
        assertThat(RecipeCreateValidation.repr(new PythonJson("1e7").parse())).isEqualTo("10000000.0");
        assertThat(RecipeCreateValidation.repr(new PythonJson("-0.0").parse())).isEqualTo("-0.0");
    }

    @Test void sqlClassificationIncludesSqliteWithoutSqlstateAndPostgresIntegrityCodes() {
        assertThat(RecipeCreateService.integrity(new UncategorizedSQLException("insert", "", new SQLException("unique", null, 19)))).isTrue();
        assertThat(RecipeCreateService.integrity(new UncategorizedSQLException("insert", "", new SQLException("unique", "23505")))).isTrue();
        assertThat(RecipeCreateService.integrity(new UncategorizedSQLException("insert", "", new SQLException("busy", null, 5)))).isFalse();
    }

    @Test void signatureMatchesIndependentPythonHmacReference() {
        assertThat(RecipeCreatedPublisher.signature("01234567890123456789012345678901", "1700000000", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo("ecda773e6e53778bc980ea70b65b2e515c321182e61f5b116b8d8f3e49d2a854");
    }
}
