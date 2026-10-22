package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AuthorizedRepositoryRegistryTest {
    @TempDir
    Path tempDirectory;

    @Test
    void registersOnlyOpaqueIdsMappedToExactAbsoluteDirectories() {
        AuthorizedRepositoryRegistry registry = registry(Map.of("repo_01", tempDirectory.toString()));

        assertEquals(tempDirectory, registry.find("repo_01").orElseThrow());
    }

    @Test
    void rejectsInvalidIdsAndRelativeOrNonNormalizedPaths() {
        assertThrows(IllegalStateException.class,
                () -> registry(Map.of("../escape", tempDirectory.toString())));
        assertThrows(IllegalStateException.class,
                () -> registry(Map.of("repo", "relative/path")));
        assertThrows(IllegalStateException.class,
                () -> registry(Map.of("repo", tempDirectory.resolve("child/..").toString())));
    }

    @Test
    void rejectsSymlinksInsideAnAuthorizedPath() throws Exception {
        Path real = Files.createDirectory(tempDirectory.resolve("real"));
        Path link = tempDirectory.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            assumeTrue(false, "Symbolic links are unavailable: " + failure.getMessage());
        }

        assertThrows(IllegalStateException.class, () -> registry(Map.of("repo", link.toString())));
    }

    private static AuthorizedRepositoryRegistry registry(Map<String, String> repositories) {
        DocGenApiProperties properties = new DocGenApiProperties(
                new DocGenApiProperties.Api("analyst", "long-test-password"),
                repositories
        );
        return new AuthorizedRepositoryRegistry(properties);
    }
}
