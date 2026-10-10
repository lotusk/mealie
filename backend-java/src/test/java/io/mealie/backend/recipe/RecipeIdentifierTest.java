package io.mealie.backend.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecipeIdentifierTest {
    @ParameterizedTest
    @ValueSource(strings = {"7d7baaa9-d335-4f71-8b41-fbca4bf3bc1a", "7D7BAAA9D3354F718B41FBCA4BF3BC1A",
            "urn:uuid:7d7baaa9-d335-4f71-8b41-fbca4bf3bc1a", "{7d7baaa9-d335-4f71-8b41-fbca4bf3bc1a}"})
    void pythonUuidFormsSelectId(String value) {
        assertEquals(UUID.fromString("7d7baaa9-d335-4f71-8b41-fbca4bf3bc1a"), RecipeIdentifier.uuid(value).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipe-name", "1-1-1-1-1", "not-a-uuid", ""})
    void slugsAndShortUuidGroupsRemainSlugs(String value) {
        assertTrue(RecipeIdentifier.uuid(value).isEmpty());
    }
}
