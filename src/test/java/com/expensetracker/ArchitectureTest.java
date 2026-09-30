package com.expensetracker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the dependency direction of the whole tree by reading import blocks. Matches
 * text, not meaning: a fully-qualified {@code java.sql.Connection} with no import line
 * slips past. Good enough to catch the leak that actually happens - an IDE auto-import.
 */
class ArchitectureTest {

    /** Resolved against the working directory, which Maven sets to the project root. */
    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void onlyStoreImportsJavaSql() throws IOException {
        assertNoImport("import java.sql", "/store/");
    }

    @Test
    void onlyUiImportsJavaFx() throws IOException {
        assertNoImport("import javafx.", "/ui/");
    }

    private void assertNoImport(String forbidden, String allowedPathFragment)
            throws IOException {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            List<String> offenders = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    // Normalise Windows separators so the fragment matches everywhere.
                    .filter(p -> !p.toString().replace('\\', '/').contains(allowedPathFragment))
                    .filter(p -> readSafely(p).contains(forbidden))
                    .map(Path::toString)
                    .sorted()
                    .toList();

            assertTrue(offenders.isEmpty(),
                    () -> forbidden + " is only allowed under " + allowedPathFragment
                            + ", but found in:\n  " + String.join("\n  ", offenders));
        }
    }

    /** Lambdas cannot throw checked exceptions, so the IOException is rewrapped. */
    private static String readSafely(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
