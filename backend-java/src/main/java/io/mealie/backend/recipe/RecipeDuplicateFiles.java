package io.mealie.backend.recipe;
import io.mealie.backend.config.MealieSettings;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Python copytree(..., dirs_exist_ok=True): follow links, copy bytes/metadata, retain partial copies and log failures. */
@Component
public class RecipeDuplicateFiles {
    private final Path recipes;
    public RecipeDuplicateFiles(MealieSettings settings) { recipes = settings.dataDir().resolve("recipes"); }
    void copy(UUID sourceId, UUID targetId, String sourceSlug, String targetSlug) {
        var failures = new ArrayList<IOException>();
        Path source = recipes.resolve(sourceId.toString()), target = recipes.resolve(targetId.toString());
        try {
            // RecipeDataService creates these directories for both recipes, even when no source files exist.
            for (Path root : java.util.List.of(target, source)) {
                Files.createDirectories(root.resolve("images/timeline"));
                Files.createDirectories(root.resolve("assets"));
            }
            Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    try { Files.createDirectories(target.resolve(source.relativize(dir))); }
                    catch (IOException failure) { failures.add(failure); return FileVisitResult.SKIP_SUBTREE; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try { Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES); }
                    catch (IOException failure) { failures.add(failure); }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    failures.add(failure); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                    if (failure != null) failures.add(failure);
                    Path destination = target.resolve(source.relativize(dir));
                    try {
                        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(destination, Files.getPosixFilePermissions(dir));
                        Files.setLastModifiedTime(destination, Files.getLastModifiedTime(dir));
                    } catch (IOException error) { failures.add(error); }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) { failures.add(failure); }
        if (!failures.isEmpty()) org.slf4j.LoggerFactory.getLogger(RecipeDuplicateFiles.class).error(
                "Failed to copy assets from {} to {}: {}", sourceSlug, targetSlug, failures.stream().map(Throwable::toString).toList());
    }
}
