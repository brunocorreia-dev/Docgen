package com.docgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void scansDeterministicallyRedactsSecretsAndSkipsSensitiveFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/app"));
        Files.writeString(tempDir.resolve("src/main/java/app/App.java"), "class App { String token = \"" + "ghp_" + "abcdefghijklmnopqrstuvwxyz123456" + "\"; }");
        Files.writeString(tempDir.resolve(".env"), "PASSWORD=do-not-read");
        Files.writeString(tempDir.resolve("README.md"), "# Existing");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(2, context.files().size());
        String joined = context.files().toString();
        assertFalse(joined.contains("ghp_" + "abcdefghijklmnopqrstuvwxyz123456"));
        assertFalse(joined.contains("do-not-read"));
        assertTrue(joined.contains("README.md"));
        assertEquals(context.files().stream().map(f -> f.relativePath().toString()).sorted().toList(),
                context.files().stream().map(f -> f.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().stream().anyMatch(item -> item.startsWith(".env (sensitive file)")));
    }

    @Test
    void docgenignoreExcludesFilesWithoutLeakingThemIntoThePrompt() throws Exception {
        Files.createDirectories(tempDir.resolve("docs"));
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve(".docgenignore"), "\uFEFFdocs/\n*.sql\n");
        Files.writeString(tempDir.resolve("docs/internal.md"), "internal notes");
        Files.writeString(tempDir.resolve("schema.sql"), "CREATE TABLE users;");
        Files.writeString(tempDir.resolve("src/App.java"), "class App {}");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("src/App.java"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
        assertTrue(context.excludedFiles().contains("docs (excluded by .docgenignore)"));
        assertTrue(context.excludedFiles().contains("schema.sql (excluded by .docgenignore)"));
        // Ignored entries must stay out of everything a prompt is built from.
        assertTrue(context.skippedFiles().isEmpty());
        assertFalse(context.fileTree().contains("internal.md"));
        assertFalse(context.fileTree().contains("schema.sql"));
    }

    @Test
    void docgenignoreMatchesBareNamesAtAnyDepth() throws Exception {
        Files.createDirectories(tempDir.resolve("a/b"));
        Files.writeString(tempDir.resolve(".docgenignore"), "notes.md\n");
        Files.writeString(tempDir.resolve("a/b/notes.md"), "deep");
        Files.writeString(tempDir.resolve("notes.md"), "shallow");
        Files.writeString(tempDir.resolve("keep.md"), "keep");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.md"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
    }

    @Test
    void docgenignoreRejectsNegationPatterns() throws Exception {
        Files.writeString(tempDir.resolve(".docgenignore"), "!keep-this.md\n");
        Files.writeString(tempDir.resolve("keep-this.md"), "content");

        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));
        assertTrue(error.getMessage().contains("negation"));
    }

    @Test
    void gitignoreExcludesAtAnyDepthHonorsNegationAndKeepsAnchoredRulesAtRoot() throws Exception {
        Files.createDirectories(tempDir.resolve("generated"));
        Files.createDirectories(tempDir.resolve("excluded-parent"));
        Files.createDirectories(tempDir.resolve("nested"));
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve(".gitignore"),
                "generated/\nexcluded-parent/\n!excluded-parent/reincluded.md\n*.local.md\n!keep.local.md\n/private.md\n!.env\n");
        Files.writeString(tempDir.resolve("generated/output.md"), "generated");
        Files.writeString(tempDir.resolve("excluded-parent/reincluded.md"), "parent remains excluded");
        Files.writeString(tempDir.resolve("nested/drop.local.md"), "ignored");
        Files.writeString(tempDir.resolve("keep.local.md"), "kept by negation");
        Files.writeString(tempDir.resolve("private.md"), "root only");
        Files.writeString(tempDir.resolve("nested/private.md"), "nested file");
        Files.writeString(tempDir.resolve("src/App.java"), "class App {}");
        Files.writeString(tempDir.resolve(".env"), "PASSWORD=still-sensitive");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.local.md", "nested/private.md", "src/App.java"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
        assertTrue(context.excludedFiles().contains("generated (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("excluded-parent (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("nested/drop.local.md (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("private.md (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains(".env (sensitive file)"),
                ".gitignore negation must not bypass built-in sensitive paths");
    }

    @Test
    void directoryNegationDoesNotCancelAChildExclusion() throws Exception {
        Files.createDirectories(tempDir.resolve("foo/sub"));
        Files.writeString(tempDir.resolve(".gitignore"), "foo/*\n!foo/\n");
        Files.writeString(tempDir.resolve("foo/a.md"), "must remain local");
        Files.writeString(tempDir.resolve("foo/sub/a.md"), "must remain local");
        Files.writeString(tempDir.resolve("keep.md"), "kept");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.md"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().contains("foo/a.md (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("foo/sub (excluded by .gitignore)"));
    }

    @Test
    void matchingDirectoryNegationCanReopenTheDirectoryForOtherwiseUnmatchedChildren() throws Exception {
        Files.createDirectories(tempDir.resolve("foo/sub"));
        Files.writeString(tempDir.resolve(".gitignore"), "foo/\n!foo/\n");
        Files.writeString(tempDir.resolve("foo/a.md"), "included");
        Files.writeString(tempDir.resolve("foo/sub/a.md"), "included");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("foo/a.md", "foo/sub/a.md"),
                context.files().stream()
                        .map(file -> file.relativePath().toString().replace('\\', '/'))
                        .toList());
    }

    @Test
    void excludesSensitiveNamesInEveryPathComponent() throws Exception {
        Files.createDirectories(tempDir.resolve("config/credentials"));
        Files.createDirectories(tempDir.resolve("keys"));
        Files.createDirectories(tempDir.resolve(".claude"));
        Files.createDirectories(tempDir.resolve("claude"));
        Files.createDirectories(tempDir.resolve("config"));
        Files.writeString(tempDir.resolve("config/credentials/app.yml"), "password: outside-redaction-layer");
        Files.writeString(tempDir.resolve("keys/notes.md"), "private key notes");
        Files.writeString(tempDir.resolve(".claude/settings.json"), "{\"token\":\"local-agent-token\"}");
        Files.writeString(tempDir.resolve("claude/claude_settings.json"), "{\"token\":\"local-agent-token\"}");
        Files.writeString(tempDir.resolve("config/service-account-prod.json"), "{\"private_key\":\"key\"}");
        Files.writeString(tempDir.resolve("credentials-prod.yml"), "token: prod");
        Files.writeString(tempDir.resolve(".env.production"), "PASSWORD=prod");
        Files.writeString(tempDir.resolve("credentials.example.yml"), "token: placeholder");
        Files.writeString(tempDir.resolve(".env.example"), "PASSWORD=placeholder");
        Files.writeString(tempDir.resolve(".environment-example.yml"), "PASSWORD=placeholder");
        Files.writeString(tempDir.resolve("api-secret.example.yml"), "token: placeholder");
        Files.writeString(tempDir.resolve("service-account.example.json"), "{\"private_key\":\"placeholder\"}");
        Files.writeString(tempDir.resolve("docgen-readme.prompt.txt"), "previous prompt snapshot");
        Files.writeString(tempDir.resolve("id_rsa.backup.txt"), "private key backup");
        Files.writeString(tempDir.resolve("signing-key.p8"), "private signing key");
        Files.writeString(tempDir.resolve("README.md"), "safe");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("README.md"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
        assertTrue(context.excludedFiles().contains("config/credentials (sensitive path)"));
        assertTrue(context.excludedFiles().contains("keys (sensitive path)"));
        assertTrue(context.excludedFiles().contains(".claude (sensitive path)"));
        assertTrue(context.excludedFiles().contains("claude (sensitive path)"));
        assertTrue(context.excludedFiles().contains("config/service-account-prod.json (sensitive file)"));
        assertTrue(context.excludedFiles().contains("credentials-prod.yml (sensitive file)"));
        assertTrue(context.excludedFiles().contains("credentials.example.yml (sensitive file)"));
        assertTrue(context.excludedFiles().contains(".env.production (sensitive file)"));
        assertTrue(context.excludedFiles().contains(".env.example (sensitive file)"));
        assertTrue(context.excludedFiles().contains(".environment-example.yml (sensitive file)"));
        assertTrue(context.excludedFiles().contains("api-secret.example.yml (sensitive file)"));
        assertTrue(context.excludedFiles().contains("service-account.example.json (sensitive file)"));
        assertTrue(context.excludedFiles().contains("docgen-readme.prompt.txt (prompt preview)"));
        assertTrue(context.excludedFiles().contains("id_rsa.backup.txt (sensitive file)"));
        assertTrue(context.excludedFiles().contains("signing-key.p8 (sensitive file)"));
    }

    @Test
    void neverFollowsFileDirectoryRootOrAncestorSymlinks() throws Exception {
        Path repository = Files.createDirectory(tempDir.resolve("repository"));
        Path outsideFile = tempDir.resolve("outside.md");
        Path outsideDirectory = Files.createDirectory(tempDir.resolve("outside-directory"));
        Files.writeString(outsideFile, "EXTERNAL_FILE_SECRET");
        Files.writeString(outsideDirectory.resolve("nested.md"), "EXTERNAL_DIRECTORY_SECRET");
        Files.writeString(repository.resolve("README.md"), "safe");

        try {
            Files.createSymbolicLink(repository.resolve("linked.md"), outsideFile);
            Files.createSymbolicLink(repository.resolve("linked-directory"), outsideDirectory);
            Files.createSymbolicLink(tempDir.resolve("repository-link"), repository);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are unavailable: " + e.getMessage());
        }

        RepoContext context = new RepoScanner().scan(repository);

        assertEquals(List.of("README.md"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
        String allContent = context.files().toString();
        assertFalse(allContent.contains("EXTERNAL_FILE_SECRET"));
        assertFalse(allContent.contains("EXTERNAL_DIRECTORY_SECRET"));
        assertTrue(context.excludedFiles().contains("linked.md (symbolic link)"));
        assertTrue(context.excludedFiles().contains("linked-directory (symbolic link)"));
        assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir.resolve("repository-link")));

        Path realParent = Files.createDirectory(tempDir.resolve("real-parent"));
        Path nestedRepository = Files.createDirectory(realParent.resolve("nested-repository"));
        Files.writeString(nestedRepository.resolve("README.md"), "safe");
        Files.createSymbolicLink(tempDir.resolve("parent-link"), realParent);
        assertThrows(IOException.class,
                () -> new RepoScanner().scan(tempDir.resolve("parent-link/nested-repository")));
    }

    @Test
    void ancestorSwapAfterDiscoveryCannotRedirectAReadOutsideTheRoot() throws Exception {
        Path repository = Files.createDirectory(tempDir.resolve("repository"));
        Path victim = Files.createDirectory(repository.resolve("victim"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(victim.resolve("data.txt"), "safe snapshot content");
        Files.writeString(outside.resolve("data.txt"), "EXTERNAL_SECRET_MUST_NOT_BE_READ");

        try {
            Path probe = tempDir.resolve("symlink-probe");
            Files.createSymbolicLink(probe, outside);
            Files.delete(probe);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are unavailable: " + e.getMessage());
        }

        RepoScanner scanner = new RepoScanner(RepoScanner.Limits.defaults(), () -> {
            Files.move(victim, repository.resolve("victim-original"));
            Files.createSymbolicLink(repository.resolve("victim"), outside);
        });

        IOException error = assertThrows(IOException.class, () -> scanner.scan(repository));

        assertTrue(error.getMessage().contains("unsafe ancestor"));
        assertFalse(error.toString().contains("EXTERNAL_SECRET_MUST_NOT_BE_READ"));
    }

    @Test
    void rejectsSymlinkedIgnoreFilesInsteadOfSilentlyIgnoringThem() throws Exception {
        Path outsideIgnore = tempDir.resolve("outside-ignore");
        Files.writeString(outsideIgnore, "README.md\n");
        Path gitRepository = Files.createDirectory(tempDir.resolve("git-repository"));
        Path docgenRepository = Files.createDirectory(tempDir.resolve("docgen-repository"));
        Path nestedRepository = Files.createDirectory(tempDir.resolve("nested-repository"));
        Files.createDirectory(nestedRepository.resolve("module"));
        Files.writeString(gitRepository.resolve("README.md"), "safe");
        Files.writeString(docgenRepository.resolve("README.md"), "safe");

        try {
            Files.createSymbolicLink(gitRepository.resolve(".gitignore"), outsideIgnore);
            Files.createSymbolicLink(docgenRepository.resolve(".docgenignore"), outsideIgnore);
            Files.createSymbolicLink(nestedRepository.resolve("module/.gitignore"), outsideIgnore);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are unavailable: " + e.getMessage());
        }

        IOException gitError = assertThrows(IOException.class, () -> new RepoScanner().scan(gitRepository));
        IOException docgenError = assertThrows(IOException.class, () -> new RepoScanner().scan(docgenRepository));
        IOException nestedError = assertThrows(IOException.class, () -> new RepoScanner().scan(nestedRepository));
        assertTrue(gitError.getMessage().contains("unsafe ignore file"));
        assertTrue(docgenError.getMessage().contains("unsafe ignore file"));
        assertTrue(nestedError.getMessage().contains("unsafe ignore file"));
    }

    @Test
    void rejectsNonRegularIgnoreFiles() throws Exception {
        Path gitRepository = Files.createDirectory(tempDir.resolve("git-repository"));
        Path docgenRepository = Files.createDirectory(tempDir.resolve("docgen-repository"));
        Path nestedRepository = Files.createDirectory(tempDir.resolve("nested-repository"));
        Files.createDirectory(gitRepository.resolve(".gitignore"));
        Files.createDirectory(docgenRepository.resolve(".docgenignore"));
        Files.createDirectories(nestedRepository.resolve("module/.gitignore"));

        IOException gitError = assertThrows(IOException.class, () -> new RepoScanner().scan(gitRepository));
        IOException docgenError = assertThrows(IOException.class, () -> new RepoScanner().scan(docgenRepository));
        IOException nestedError = assertThrows(IOException.class, () -> new RepoScanner().scan(nestedRepository));
        assertTrue(gitError.getMessage().contains("unsafe ignore file"));
        assertTrue(docgenError.getMessage().contains("unsafe ignore file"));
        assertTrue(nestedError.getMessage().contains("unsafe ignore file"));
    }

    @Test
    void failsClosedWhenFilesystemDoesNotSupportSecureDirectoryStreams() throws Exception {
        Path archive = tempDir.resolve("repository.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path repository = fileSystem.getPath("/");
            Files.writeString(repository.resolve("README.md"), "safe");

            IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(repository));

            assertTrue(error.getMessage().contains("SecureDirectoryStream"));
        }
    }

    @Test
    void loadsNestedGitignoresWithCorrectScopeBomAndZeroDirectoryDoubleStar() throws Exception {
        Files.createDirectories(tempDir.resolve("module/deep"));
        Files.createDirectories(tempDir.resolve("module/branch/x"));
        Files.createDirectories(tempDir.resolve("module/blocked"));
        Files.createDirectories(tempDir.resolve("sibling"));
        Files.writeString(tempDir.resolve(".gitignore"), "\uFEFF/root-only.md\n");
        Files.writeString(tempDir.resolve("module/.gitignore"), """
                local.md
                /anchored.md
                /**/zero.md
                branch/**/leaf.md
                blocked/
                !blocked/reincluded.md
                """);
        Files.writeString(tempDir.resolve("root-only.md"), "ignored at root");
        Files.writeString(tempDir.resolve("module/root-only.md"), "root rule is anchored");
        Files.writeString(tempDir.resolve("local.md"), "nested rule must not escape its scope");
        Files.writeString(tempDir.resolve("module/local.md"), "ignored shallow");
        Files.writeString(tempDir.resolve("module/deep/local.md"), "ignored deep");
        Files.writeString(tempDir.resolve("module/anchored.md"), "ignored at nested root");
        Files.writeString(tempDir.resolve("module/deep/anchored.md"), "nested anchored rule does not match");
        Files.writeString(tempDir.resolve("module/zero.md"), "zero directories");
        Files.writeString(tempDir.resolve("module/deep/zero.md"), "one directory");
        Files.writeString(tempDir.resolve("module/branch/leaf.md"), "zero middle directories");
        Files.writeString(tempDir.resolve("module/branch/x/leaf.md"), "one middle directory");
        Files.writeString(tempDir.resolve("module/blocked/reincluded.md"), "parent remains ignored");
        Files.writeString(tempDir.resolve("sibling/local.md"), "outside nested scope");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("local.md", "module/deep/anchored.md", "module/root-only.md", "sibling/local.md"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
        assertTrue(context.excludedFiles().contains("root-only.md (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("module/blocked (excluded by .gitignore)"));
        assertFalse(context.files().toString().contains("zero directories"));
        assertFalse(context.files().toString().contains("zero middle directories"));
    }

    @Test
    void preservesSignificantLeadingSpacesInIgnorePatterns() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), " report.md\ntrailing\\ \n");
        Files.writeString(tempDir.resolve(" report.md"), "must stay local");
        Files.writeString(tempDir.resolve("trailing "), "must also stay local");
        Files.writeString(tempDir.resolve("report.md"), "ordinary file");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("report.md"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().contains(" report.md (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("trailing  (excluded by .gitignore)"));
    }

    @Test
    void rejectsIgnoreRuleExplosionBeforeRepositoryTraversal() throws Exception {
        StringBuilder rules = new StringBuilder();
        for (int index = 0; index < 513; index++) {
            rules.append("entry-").append(index).append(".md\n");
        }
        Files.writeString(tempDir.resolve(".gitignore"), rules);
        Files.writeString(tempDir.resolve("README.md"), "safe");

        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));

        assertTrue(error.getMessage().contains("rules"));
    }

    @Test
    void repeatedGitignoreRuleRetainsItsLastMatchPrecedence() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "report.md\n!report.md\nreport.md\n");
        Files.writeString(tempDir.resolve("report.md"), "must stay local");
        Files.writeString(tempDir.resolve("README.md"), "safe");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("README.md"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().contains("report.md (excluded by .gitignore)"));
    }

    @Test
    void deterministicIgnoreMatcherSupportsWildcardsClassesAndRecursiveSegments() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.createDirectories(tempDir.resolve("reports"));
        Files.createDirectories(tempDir.resolve("assets/deep"));
        Files.writeString(tempDir.resolve(".gitignore"), """
                src/?ain/[A-C]pp.java
                reports/[!0-9].md
                assets/**/cache?.bin
                """);
        Files.writeString(tempDir.resolve("src/main/App.java"), "ignored by range");
        Files.writeString(tempDir.resolve("src/main/Dpp.java"), "kept outside range");
        Files.writeString(tempDir.resolve("reports/a.md"), "ignored by negated class");
        Files.writeString(tempDir.resolve("reports/7.md"), "kept by negated class");
        Files.writeString(tempDir.resolve("assets/cache1.bin"), "ignored with zero middle directories");
        Files.writeString(tempDir.resolve("assets/deep/cache2.bin"), "ignored recursively");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("reports/7.md", "src/main/Dpp.java"),
                context.files().stream().map(file -> file.relativePath().toString().replace('\\', '/')).toList());
    }

    @Test
    void trailingDoubleStarAndDirectoryNegationsCannotReincludeTheirOwnTarget() throws Exception {
        Files.createDirectories(tempDir.resolve("abc"));
        Files.writeString(tempDir.resolve(".gitignore"), """
                abc/
                !abc/**
                report.md
                !report.md/
                """);
        Files.writeString(tempDir.resolve("abc/confidential.md"), "must stay excluded with its parent");
        Files.writeString(tempDir.resolve("report.md"), "a directory-only negation must not match this file");
        Files.writeString(tempDir.resolve("keep.md"), "kept");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.md"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().contains("abc (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("report.md (excluded by .gitignore)"));
    }

    @Test
    void wholeSegmentsWithThreeOrMoreStarsRemainRecursive() throws Exception {
        Files.createDirectories(tempDir.resolve("star/a/x/y"));
        Files.createDirectories(tempDir.resolve("four/a/x/y"));
        Files.writeString(tempDir.resolve(".gitignore"), """
                star/a/***/b.md
                four/a/****/b.md
                """);
        Files.writeString(tempDir.resolve("star/a/x/y/b.md"), "ignored by triple star");
        Files.writeString(tempDir.resolve("four/a/x/y/b.md"), "ignored by quadruple star");
        Files.writeString(tempDir.resolve("keep.md"), "kept");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.md"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
    }

    @Test
    void gitignoreQuestionMarkUsesUtf8ByteSemantics() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "confidential-????.txt\n");
        Files.writeString(tempDir.resolve("confidential-💣.txt"), "must stay excluded");
        Files.writeString(tempDir.resolve("keep.txt"), "kept");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.txt"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().contains("confidential-💣.txt (excluded by .gitignore)"));
    }

    @Test
    void unsupportedPosixIgnoreClassFailsClosed() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "report-[[:digit:]].md\n");
        Files.writeString(tempDir.resolve("report-7.md"), "must not slip through an unsupported matcher");

        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));

        assertTrue(error.getMessage().contains("invalid pattern"));
    }

    @Test
    void repeatedLeadingOrTrailingSlashesInIgnoreRulesFailClosed() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "bar.md\n!//bar.md\n");
        Files.writeString(tempDir.resolve("bar.md"), "must not be re-included by slash normalization");

        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));

        assertTrue(error.getMessage().contains("invalid pattern"));
    }

    @Test
    void conservativelyEvaluatesCaseSensitiveAndCoreIgnoreCaseRules() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), """
                case.md
                report.md
                !REPORT.md
                file-[A-C].md
                LEDGER.TXT
                ![L]edger.txt
                MANUAL.TXT
                !\\Manual.txt
                """);
        Files.writeString(tempDir.resolve("Case.md"), "excluded in core.ignoreCase mode");
        Files.writeString(tempDir.resolve("report.md"), "must remain excluded in case-sensitive mode");
        Files.writeString(tempDir.resolve("REPORT.md"), "included in both ordered interpretations");
        Files.writeString(tempDir.resolve("file-b.md"), "case-folded class match");
        Files.writeString(tempDir.resolve("ledger.txt"), "singleton class must not over-fold a negation");
        Files.writeString(tempDir.resolve("manual.txt"), "escaped literal must remain exact in a negation");
        Files.writeString(tempDir.resolve("keep.md"), "kept");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("REPORT.md", "keep.md"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().contains("Case.md (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("report.md (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("file-b.md (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("ledger.txt (excluded by .gitignore)"));
        assertTrue(context.excludedFiles().contains("manual.txt (excluded by .gitignore)"));
    }

    @Test
    void ambiguouslyCasedIgnoreFilenameFailsClosed() throws Exception {
        Files.writeString(tempDir.resolve(".GITIGNORE"), "private.md\n");
        Files.writeString(tempDir.resolve("private.md"), "must not be scanned ambiguously");

        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));

        assertTrue(error.getMessage().contains("ambiguously cased ignore file"));
    }

    @Test
    void decomposedUnicodePathsAndIgnorePatternsFailClosed() throws Exception {
        String decomposed = "cafe\u0301.md";
        Files.writeString(tempDir.resolve(decomposed), "must remain local");
        Files.writeString(tempDir.resolve("keep.md"), "kept");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.md"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
        assertTrue(context.excludedFiles().contains(decomposed + " (unsafe or overlong path)"));

        Files.writeString(tempDir.resolve(".gitignore"), decomposed + "\n");
        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));
        assertTrue(error.getMessage().contains("non-NFC Unicode patterns"));
    }

    @Test
    void nulInIgnoreFileFailsClosedInsteadOfUsingDifferentGitSemantics() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "report.md\0junk\n");
        Files.writeString(tempDir.resolve("report.md"), "must remain local");

        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));

        assertTrue(error.getMessage().contains("NUL byte"));
    }

    @Test
    void bareCarriageReturnInIgnoreFileFailsClosed() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "report.md\n!report.md\r!other.md\n");
        Files.writeString(tempDir.resolve("report.md"), "must remain local");

        IOException error = assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir));

        assertTrue(error.getMessage().contains("bare carriage return"));
    }

    @Test
    void promptMetadataRejectsUnicodeFormattingAndLineSeparatorCharacters() throws Exception {
        String bidiName = "safe\u202Egnp.md";
        String lineSeparatorName = "safe\u2028forged.md";
        Files.writeString(tempDir.resolve(bidiName), "must remain local");
        Files.writeString(tempDir.resolve(lineSeparatorName), "must remain local");
        Files.writeString(tempDir.resolve("keep.md"), "kept");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertEquals(List.of("keep.md"),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
        assertEquals(2, context.excludedFiles().stream()
                .filter(item -> item.contains("unsafe or overlong path"))
                .count());
    }

    @Test
    void adversarialIgnoreGlobHasBoundedMatchingTime() throws Exception {
        String adversarialPattern = "*a".repeat(100) + "b.md";
        String nonMatchingName = "a".repeat(100) + ".md";
        Files.writeString(tempDir.resolve(".gitignore"), adversarialPattern + "\n");
        Files.writeString(tempDir.resolve(nonMatchingName), "kept");

        RepoContext context = assertTimeout(Duration.ofSeconds(3), () -> new RepoScanner().scan(tempDir));

        assertEquals(List.of(nonMatchingName),
                context.files().stream().map(file -> file.relativePath().toString()).toList());
    }

    @Test
    void manyAdversarialIgnoreRulesAndFilesHaveBoundedMatchingTime() throws Exception {
        String adversarialPattern = "*" + "a".repeat(120) + "b.md";
        Files.writeString(tempDir.resolve(".gitignore"), (adversarialPattern + "\n").repeat(100));
        for (int index = 0; index < 100; index++) {
            Files.writeString(tempDir.resolve("a".repeat(120) + String.format("%04d.md", index)), "kept");
        }

        RepoContext context = assertTimeout(Duration.ofSeconds(5), () -> new RepoScanner().scan(tempDir));

        assertEquals(100, context.files().size());
    }

    @Test
    void aggregateIgnoreMatchingWorkFailsClosed() throws Exception {
        String adversarialPattern = "*" + "a".repeat(120) + "b.md";
        Files.writeString(tempDir.resolve(".gitignore"), (adversarialPattern + "\n").repeat(400));
        for (int index = 0; index < 100; index++) {
            Files.writeString(tempDir.resolve("a".repeat(120) + String.format("%04d.md", index)), "kept");
        }

        IOException error = assertTimeout(Duration.ofSeconds(5),
                () -> assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir)));

        assertTrue(error.getMessage().contains("work safety limit"));
    }

    @Test
    void deeplySegmentedIgnoreRulesFailBeforeAllocatingExcessiveMatcherTables() throws Exception {
        String deeplySegmented = String.join("/", java.util.Collections.nCopies(256, "z"));
        Files.writeString(tempDir.resolve(".gitignore"), (deeplySegmented + "\n").repeat(17));
        Files.writeString(tempDir.resolve("keep.md"), "kept");

        IOException error = assertTimeout(Duration.ofSeconds(3),
                () -> assertThrows(IOException.class, () -> new RepoScanner().scan(tempDir)));

        assertTrue(error.getMessage().contains("segment matchers"));
    }

    @Test
    void countsAllTruncationMarkersInsideTheContentBudget() throws Exception {
        String almostTwoHundred = "x\n".repeat(95);
        for (int i = 0; i < 3; i++) {
            Files.writeString(tempDir.resolve("file-" + i + ".txt"), almostTwoHundred);
        }
        Files.writeString(tempDir.resolve("file-3.txt"), "y\n".repeat(50));

        RepoScanner scanner = new RepoScanner(new RepoScanner.Limits(32, 16, 1_000, 1_000, 200, 650));
        RepoContext context = scanner.scan(tempDir);

        assertEquals(650, context.totalCharacters());
        assertEquals(context.totalCharacters(),
                context.files().stream().mapToInt(file -> file.content().length()).sum());
        assertTrue(context.files().stream().anyMatch(file ->
                file.content().contains("[TRUNCATED: repository content limit reached]")));
    }

    @Test
    void fileTruncationMarkerFitsInsidePerFileLimit() throws Exception {
        Files.writeString(tempDir.resolve("large.txt"), "x\n".repeat(125));

        RepoScanner scanner = new RepoScanner(new RepoScanner.Limits(16, 8, 1_000, 1_000, 200, 1_000));
        RepoContext context = scanner.scan(tempDir);

        RepoContext.ScannedFile file = context.files().getFirst();
        assertEquals(200, file.content().length());
        assertTrue(file.content().endsWith("[TRUNCATED: file content limit reached]"));
        assertEquals(200, context.totalCharacters());
    }

    @Test
    void redactsARecognizedTokenBeforeApplyingTheFileCharacterLimit() throws Exception {
        String token = "gsk_" + "abcdefghijklmnopqrstuvwxyz123456";
        String content = "a".repeat(182) + " " + token;
        Files.writeString(tempDir.resolve("boundary.txt"), content);

        RepoScanner scanner = new RepoScanner(new RepoScanner.Limits(16, 8, 1_000, 1_000, 200, 1_000));
        RepoContext context = scanner.scan(tempDir);

        RepoContext.ScannedFile file = context.files().getFirst();
        assertEquals(200, file.content().length());
        assertTrue(file.content().endsWith("[REDACTED_SECRET]"));
        assertFalse(file.content().contains("gsk_"));
        assertFalse(file.truncated(), "redaction shrinks the full input to the configured limit");
    }

    @Test
    void boundsFileCountTreeAndSkippedMetadata() throws Exception {
        String prefix = "p".repeat(40);
        for (int i = 0; i < 20; i++) {
            Files.writeString(tempDir.resolve(prefix + String.format("%04d.md", i)), "x");
        }

        RepoScanner scanner = new RepoScanner(new RepoScanner.Limits(64, 8, 200, 250, 1_000, 2_000));
        RepoContext context = scanner.scan(tempDir);

        assertEquals(8, context.files().size());
        assertTrue(context.fileTree().length() <= 200);
        assertTrue(context.fileTree().contains("file tree metadata limit reached"));
        assertTrue(context.skippedFiles().size() <= 256);
        assertTrue(context.skippedFiles().stream().anyMatch(item -> item.contains("additional entries omitted")));
        assertTrue(context.skippedFiles().stream().mapToInt(item -> item.length() + 3).sum() <= 250);
    }

    @Test
    void rejectsOverlongPromptPathsWithoutTraversingThem() throws Exception {
        String first = "a".repeat(180);
        String second = "b".repeat(180);
        String third = "c".repeat(180);
        Path deep = tempDir.resolve(first).resolve(second).resolve(third);
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("leak.md"), "must not be scanned");

        RepoContext context = new RepoScanner().scan(tempDir);

        assertTrue(context.files().isEmpty());
        assertTrue(context.excludedFiles().stream().anyMatch(item -> item.contains("unsafe or overlong path")));
        assertTrue(context.excludedFiles().stream().allMatch(item -> item.length() <= 672));
    }

    @Test
    void failsDeterministicallyWhenRepositoryEntryLimitIsExceeded() throws Exception {
        for (int i = 0; i < 33; i++) {
            Files.createFile(tempDir.resolve(String.format("entry-%04d.bin", i)));
        }

        RepoScanner scanner = new RepoScanner(new RepoScanner.Limits(32, 32, 1_000, 1_000, 1_000, 2_000));
        IOException error = assertThrows(IOException.class, () -> scanner.scan(tempDir));

        assertTrue(error.getMessage().contains("entry count exceeds"));
    }

    @Test
    void extensionAllowlistNarrowsSelectionButCannotReincludeSensitiveFiles() throws Exception {
        Files.writeString(tempDir.resolve("App.java"), "class App {}");
        Files.writeString(tempDir.resolve("notes.md"), "notes");
        Files.writeString(tempDir.resolve("server.pem"), "-----BEGIN PRIVATE KEY-----x-----END PRIVATE KEY-----");
        Files.writeString(tempDir.resolve(".env"), "SECRET=1");

        RepoScanner.Options options = RepoScanner.Options.withAllowedExtensions(List.of("java", ".PEM", "env"));
        RepoContext context = new RepoScanner().scan(tempDir, options);

        assertEquals(List.of("App.java"),
                context.files().stream().map(f -> f.relativePath().toString().replace('\\', '/')).toList());
        assertTrue(context.excludedFiles().contains("notes.md (not in --include-ext allowlist)"));
        assertTrue(context.excludedFiles().contains("server.pem (sensitive file)"));
        assertTrue(context.excludedFiles().contains(".env (sensitive file)"));
    }
}
