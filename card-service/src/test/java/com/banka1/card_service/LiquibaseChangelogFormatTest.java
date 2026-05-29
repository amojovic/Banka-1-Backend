package com.banka1.card_service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LiquibaseChangelogFormatTest {

    /**
     * Liquibase formatted-SQL changelogs were moved into the {@code db/changelog/card}
     * subdirectory during the service consolidation. This test scans every formatted
     * SQL changelog there.
     */
    private static final Path CHANGELOG_DIR =
            Path.of("src", "main", "resources", "db", "changelog", "card");

    @Test
    void formattedSqlChangesetsMustIncludeAuthorAndId() throws IOException {
        try (Stream<Path> sqlFiles = Files.list(CHANGELOG_DIR)) {
            List<Path> changelogs = sqlFiles
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList();

            assertTrue(!changelogs.isEmpty(),
                    () -> "Expected at least one formatted SQL changelog in " + CHANGELOG_DIR);

            for (Path changelog : changelogs) {
                assertChangesetHeaders(changelog);
            }
        }
    }

    private void assertChangesetHeaders(Path changelog) throws IOException {
        for (String line : Files.readAllLines(changelog)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-- changeset ")) {
                String remainder = trimmed.substring("-- changeset ".length()).trim();
                // The changeset header is "author:id" optionally followed by
                // whitespace-separated attributes such as "context:dev".
                String authorAndId = remainder.split("\\s+", 2)[0];
                assertTrue(
                        authorAndId.matches("[A-Za-z0-9_-]+:[A-Za-z0-9_-]+"),
                        () -> "Invalid Liquibase changeset header in "
                                + changelog.getFileName() + ": " + trimmed);
            }
        }
    }
}
