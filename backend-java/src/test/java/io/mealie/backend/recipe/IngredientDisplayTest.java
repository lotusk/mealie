package io.mealie.backend.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IngredientDisplayTest {
    static final Map<String, Object> CUP = Map.of("name", "cup", "pluralName", "cups", "fraction", true);
    static final Map<String, Object> EGG = Map.of("name", "egg", "pluralName", "eggs");

    @Test
    void quantityUsesPythonBinaryFloatRounding() {
        assertEquals(1.234, IngredientDisplay.round(1.2345));
        assertEquals(1.236, IngredientDisplay.round(1.2355));
        assertNull(IngredientDisplay.round(null));
    }

    @Test
    void mixedFractionAndEnglishFoodPluralRule() {
        assertEquals("2 ³/₄ cups egg whisked", IngredientDisplay.format(2.75, CUP, EGG, "whisked", "en-US"));
        assertEquals("¹/₃ cup egg", IngredientDisplay.format(0.333, CUP, EGG, "", "en-US"));
        assertEquals("2 eggs", IngredientDisplay.format(2.0, null, EGG, "", "en-US"));
    }

    @Test
    void localeChangesFoodPluralWithoutChangingUnit() {
        assertEquals("2 cups eggs", IngredientDisplay.format(2.0, CUP, EGG, "", "fr-FR"));
        assertEquals("2 cups egg", IngredientDisplay.format(2.0, CUP, EGG, "", "zh-CN"));
        assertEquals("2 cups egg", IngredientDisplay.format(2.0, CUP, EGG, "", "unknown"));
    }

    @Test
    void nullAndZeroQuantitiesKeepNotesAndOmitUnits() {
        assertEquals("to taste", IngredientDisplay.format(null, null, null, "to taste", "en-US"));
        assertEquals("eggs optional", IngredientDisplay.format(0.0, CUP, EGG, "optional", "en-US"));
    }

    @Test
    void decimalsAndAbbreviationsArePreserved() {
        var decimal = Map.<String, Object>of("name", "gram", "pluralName", "grams", "fraction", false,
                "useAbbreviation", true, "abbreviation", "g", "pluralAbbreviation", "");
        assertEquals("1.25 g egg", IngredientDisplay.format(1.25, decimal, EGG, "", "en-US"));
    }
}
