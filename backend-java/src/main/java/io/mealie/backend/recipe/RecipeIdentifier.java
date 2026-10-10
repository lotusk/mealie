package io.mealie.backend.recipe;

import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Python's uuid.UUID(string) selects the ID lookup; anything else remains a case-sensitive slug. */
final class RecipeIdentifier {
    private RecipeIdentifier() { }

    static Optional<UUID> uuid(String value) {
        String hex = value.replace("urn:", "").replace("uuid:", "").replace("-", "");
        while (hex.startsWith("{")) hex = hex.substring(1);
        while (hex.endsWith("}")) hex = hex.substring(0, hex.length() - 1);
        if (hex.length() != 32) return Optional.empty();
        try {
            return Optional.of(new UUID(HexFormat.fromHexDigitsToLong(hex, 0, 16),
                    HexFormat.fromHexDigitsToLong(hex, 16, 32)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
