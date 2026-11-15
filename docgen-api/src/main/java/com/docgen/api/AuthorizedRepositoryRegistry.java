package com.docgen.api;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
final class AuthorizedRepositoryRegistry {
    static final Pattern REPOSITORY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final int MAX_REPOSITORIES = 128;

    private final Map<String, Path> repositories;

    AuthorizedRepositoryRegistry(DocGenApiProperties properties) {
        if (properties.repositories().size() > MAX_REPOSITORIES) {
            throw new IllegalStateException("Too many authorized repositories are configured");
        }
        Map<String, Path> validated = new LinkedHashMap<>();
        properties.repositories().forEach((id, configuredPath) ->
                validated.put(validateId(id), validatePath(configuredPath)));
        repositories = Map.copyOf(validated);
    }

    Optional<Path> find(String repositoryId) {
        return Optional.ofNullable(repositories.get(repositoryId));
    }

    private static String validateId(String id) {
        if (id == null || !REPOSITORY_ID.matcher(id).matches()) {
            throw new IllegalStateException("Invalid authorized repository identifier");
        }
        return id;
    }

    private static Path validatePath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("Authorized repository path is required");
        }
        Path path;
        try {
            path = Path.of(configuredPath);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Authorized repository path is invalid", invalid);
        }
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalStateException("Authorized repository paths must be absolute and normalized");
        }
        verifyExistingDirectories(path);
        return path;
    }

    private static void verifyExistingDirectories(Path path) {
        Path current = path.getRoot();
        try {
            for (Path component : path) {
                current = current.resolve(component);
                BasicFileAttributes attributes = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                    throw new IllegalStateException(
                            "Authorized repository paths cannot contain symbolic links or non-directories");
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Authorized repository path cannot be verified", failure);
        }
    }
}
