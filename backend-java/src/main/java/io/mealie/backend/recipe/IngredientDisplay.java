package io.mealie.backend.recipe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Matches RecipeIngredientBase's computed display; display is not stored in the shared schema. */
final class IngredientDisplay {
    private static final Set<String> NEVER_PLURAL = Set.of("ja-JP", "ko-KR", "tr-TR", "vi-VN", "zh-CN", "zh-TW");
    private static final Set<String> ALWAYS_PLURAL = Set.of("af-ZA", "ar-SA", "bg-BG", "ca-ES", "cs-CZ", "da-DK",
            "de-DE", "el-GR", "es-ES", "et-EE", "fi-FI", "fr-BE", "fr-CA", "fr-FR", "gl-ES", "he-IL", "hr-HR",
            "hu-HU", "is-IS", "it-IT", "lt-LT", "lv-LV", "nl-NL", "no-NO", "pl-PL", "pt-BR", "pt-PT", "ro-RO",
            "ru-RU", "sk-SK", "sl-SI", "sr-SP", "sv-SE", "uk-UA");

    private IngredientDisplay() { }

    static Double round(Number quantity) {
        return quantity == null ? null : new BigDecimal(quantity.doubleValue()).setScale(3, RoundingMode.HALF_EVEN).doubleValue();
    }

    static String format(Double quantity, Map<String, Object> unit, Map<String, Object> food, String note, String locale) {
        double q = quantity == null ? 0 : quantity;
        var parts = new ArrayList<String>();
        if (q != 0) parts.add(unit != null && !Boolean.TRUE.equals(unit.get("fraction")) ? decimal(q) : fraction(q));
        if (q != 0 && unit != null) {
            String value = "";
            if (Boolean.TRUE.equals(unit.get("useAbbreviation"))) {
                value = q > 1 ? fallback(unit, "pluralAbbreviation", "abbreviation") : string(unit, "abbreviation");
            }
            if (value.isEmpty()) value = q > 1 ? fallback(unit, "pluralName", "name") : string(unit, "name");
            parts.add(value);
        }
        if (food != null) {
            boolean plural = !(q != 0 && q <= 1) && !NEVER_PLURAL.contains(locale)
                    && (ALWAYS_PLURAL.contains(locale) || !(q != 0 && unit != null));
            parts.add(plural ? fallback(food, "pluralName", "name") : string(food, "name"));
        }
        if (note != null && !note.isEmpty()) parts.add(note);
        return String.join(" ", parts).strip();
    }

    private static String string(Map<String, Object> values, String key) {
        return values.get(key) == null ? "" : values.get(key).toString();
    }

    private static String fallback(Map<String, Object> values, String plural, String singular) {
        String value = string(values, plural);
        return value.isEmpty() ? string(values, singular) : value;
    }

    private static String decimal(double q) {
        return BigDecimal.valueOf(q).stripTrailingZeros().toPlainString();
    }

    private static String fraction(double q) {
        long numerator = 0;
        int denominator = 1;
        double distance = Double.POSITIVE_INFINITY;
        for (int d = 1; d <= 32; d++) {
            long n = Math.round(q * d);
            double error = Math.abs(q - (double) n / d);
            if (error < distance) { numerator = n; denominator = d; distance = error; }
        }
        if (denominator == 1) return Long.toString(numerator);
        long whole = numerator > denominator ? numerator / denominator : 0;
        long remainder = whole > 0 ? numerator % denominator : numerator;
        return (whole > 0 ? whole + " " : "") + digits(remainder, "⁰¹²³⁴⁵⁶⁷⁸⁹") + "/" + digits(denominator, "₀₁₂₃₄₅₆₇₈₉");
    }

    private static String digits(long value, String alphabet) {
        var text = new StringBuilder();
        for (char digit : Long.toString(value).toCharArray()) text.append(digit == '-' ? '-' : alphabet.charAt(digit - '0'));
        return text.toString();
    }
}
