package io.mealie.backend.recipe;

import static org.assertj.core.api.Assertions.*;
import io.mealie.backend.config.MealieSettings;
import io.mealie.backend.db.DbEngine;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RecipeDuplicateCompatibilityTest {
    private MealieSettings settings(java.nio.file.Path dir) {
        return new MealieSettings(false, true, dir, dir, DbEngine.SQLITE);
    }
    @Test void nameIsOptionalNullableAndEmptyButNumbersAreRejected() throws Exception {
        var dir = Files.createTempDirectory("mealie-duplicate-validation-");
        var validation = new RecipeDuplicateValidation(new RecipeCreateValidation(settings(dir)), settings(dir));
        assertThat(validation.name(new RecipeDuplicateBody(new RecipeCreateBody(Map.of(), false)))).isNull();
        assertThat(validation.name(new RecipeDuplicateBody(new RecipeCreateBody(Map.of("name", ""), false)))).isEmpty();
        assertThatThrownBy(() -> validation.name(new RecipeDuplicateBody(new RecipeCreateBody(Map.of("name", 12), false))))
                .isInstanceOfSatisfying(RecipeCreateFailure.class, failure -> assertThat(failure.status).isEqualTo(422));
    }
    @Test void malformedJsonUsesDuplicateRouteTraceAndMissingBodyIsRejected() throws Exception {
        var dir = Files.createTempDirectory("mealie-duplicate-validation-");
        var validation = new RecipeDuplicateValidation(new RecipeCreateValidation(settings(dir)), settings(dir));
        var request = new MockHttpServletRequest(); request.setContentType("application/json"); request.setContent("{\"name\":".getBytes());
        assertThatThrownBy(() -> validation.parse(request)).isInstanceOfSatisfying(RecipeCreateFailure.class,
                failure -> assertThat(failure.body.toString()).contains("duplicate_one", "POST /api/recipes/{slug}/duplicate"));
        assertThatThrownBy(() -> validation.name(new RecipeDuplicateBody(new RecipeCreateBody(null, true))))
                .isInstanceOfSatisfying(RecipeCreateFailure.class, failure -> assertThat(failure.status).isEqualTo(422));
    }
    @Test void filesAreIndependentAndLinksAreDereferenced() throws Exception {
        var dir = Files.createTempDirectory("mealie-duplicate-files-"); var source = UUID.randomUUID(); var target = UUID.randomUUID();
        var files = new RecipeDuplicateFiles(settings(dir));
        var root = dir.resolve("recipes").resolve(source.toString()); Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets/original.txt"), "source");
        Files.createSymbolicLink(root.resolve("assets/link.txt"), root.resolve("assets/original.txt"));
        files.copy(source, target, "source", "copy");
        var copied = dir.resolve("recipes").resolve(target.toString()).resolve("assets/link.txt");
        assertThat(Files.isSymbolicLink(copied)).isFalse(); assertThat(Files.readString(copied)).isEqualTo("source");
        Files.writeString(copied, "changed"); assertThat(Files.readString(root.resolve("assets/original.txt"))).isEqualTo("source");
    }
    @Test void fileFailureRetainsOtherCopiedFilesAndDoesNotThrow() throws Exception {
        var dir = Files.createTempDirectory("mealie-duplicate-file-failure-"); var source = UUID.randomUUID(); var target = UUID.randomUUID();
        var files = new RecipeDuplicateFiles(settings(dir)); var root = dir.resolve("recipes").resolve(source.toString());
        Files.createDirectories(root.resolve("assets")); Files.writeString(root.resolve("assets/safe.txt"), "retained");
        Files.createSymbolicLink(root.resolve("assets/broken.txt"), root.resolve("missing"));
        assertThatCode(() -> files.copy(source, target, "source", "copy")).doesNotThrowAnyException();
        assertThat(Files.readString(dir.resolve("recipes").resolve(target.toString()).resolve("assets/safe.txt"))).isEqualTo("retained");
    }
    @Test void blockedDestinationDirectoryDoesNotPreventSiblingCopies() throws Exception {
        var dir = Files.createTempDirectory("mealie-duplicate-directory-failure-"); var source = UUID.randomUUID(); var target = UUID.randomUUID();
        var files = new RecipeDuplicateFiles(settings(dir)); var root = dir.resolve("recipes").resolve(source.toString());
        var destination = dir.resolve("recipes").resolve(target.toString());
        Files.createDirectories(root.resolve("assets/nested")); Files.writeString(root.resolve("assets/nested/blocked.txt"), "blocked");
        Files.writeString(root.resolve("assets/sibling.txt"), "sibling"); Files.createDirectories(destination.resolve("assets"));
        Files.writeString(destination.resolve("assets/nested"), "existing obstruction");
        assertThatCode(() -> files.copy(source, target, "source", "copy")).doesNotThrowAnyException();
        assertThat(Files.readString(destination.resolve("assets/sibling.txt"))).isEqualTo("sibling");
    }

}
