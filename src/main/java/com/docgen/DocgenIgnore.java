package com.docgen;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Exclusion rules loaded from a {@code .docgenignore} file at the repository root.
 * Supported syntax (a documented subset of gitignore):
 * blank lines and lines starting with {@code #} are ignored; a pattern without
 * {@code /} matches at any depth; a pattern with {@code /} is relative to the
 * repository root; a trailing {@code /} matches a directory and everything under
 * it; {@code *}, {@code ?} and {@code **} follow glob semantics. Negation
 * ({@code !pattern}) is not supported and rejected so nobody relies on it.
 */
final class DocgenIgnore {
    private static final DocgenIgnore EMPTY = new DocgenIgnore(List.of());

    private final List<PathMatcher> matchers;

    private DocgenIgnore(List<PathMatcher> matchers) {
        this.matchers = List.copyOf(matchers);
    }

    static DocgenIgnore load(Path root) throws IOException {
        Path file = root.resolve(".docgenignore");
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        List<PathMatcher> matchers = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("!")) {
                throw new IOException(".docgenignore line " + (i + 1) + ": negation patterns ('!') are not supported.");
            }
            try {
                compileVariants(line, matchers);
            } catch (RuntimeException e) {
                throw new IOException(".docgenignore line " + (i + 1) + ": invalid pattern '" + line + "'.", e);
            }
        }
        return new DocgenIgnore(matchers);
    }

    private static void compileVariants(String rawPattern, List<PathMatcher> matchers) {
        String pattern = rawPattern;
        while (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }
        while (pattern.endsWith("/")) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        if (pattern.isEmpty()) {
            return;
        }
        List<String> globs = new ArrayList<>();
        globs.add(pattern);
        globs.add(pattern + "/**");
        if (!pattern.contains("/")) {
            globs.add("**/" + pattern);
            globs.add("**/" + pattern + "/**");
        }
        for (String glob : globs) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        }
    }

    boolean excludes(Path relativePath) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relativePath)) {
                return true;
            }
        }
        return false;
    }
}
