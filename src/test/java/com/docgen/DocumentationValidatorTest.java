package com.docgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentationValidatorTest {
    @Test
    void acceptsAndNormalizesAnUnfencedMarkdownDocument() throws Exception {
        String validated = DocumentationValidator.validate("\r\n# Project\r\n\r\nText.\r\n", "README.md");

        assertEquals("# Project\n\nText.\n", validated);
    }

    @Test
    void rejectsPreambleAndWholeDocumentCodeFence() {
        IOException preamble = assertThrows(IOException.class,
                () -> DocumentationValidator.validate("Here is the document:\n# Project", "README.md"));
        IOException fence = assertThrows(IOException.class,
                () -> DocumentationValidator.validate("```markdown\n# Project\n```", "README.md"));

        assertTrue(preamble.getMessage().contains("Markdown"));
        assertTrue(fence.getMessage().contains("Markdown"));
    }

    @Test
    void rejectsUnsafeControlsAndInvalidUnicode() {
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n\u001b[31mred", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n\ud800", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n\u202Etxt", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\nzero\u200Bwidth", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\ntag\uDB40\uDC01character", "README.md"));
    }

    @Test
    void rejectsActiveMarkdownButAllowsSafeLinksAndLiteralCode() throws Exception {
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n<script>alert(1)</script>", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n<<img src=x>", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n![badge](https://tracker.example/pixel)", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n[x](jav&#x61;script:alert)", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n[id]: data:text/html,payload", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n\\`<img src=x>\\`", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n``` bad`\n<img src=x>\n```", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate(
                        "# Project\n[outer [inner]](javascript:alert(1))", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n<https://example.com>", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n[Open](vscode://file/project)", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate(
                        "# Project\n[x](" + "&Tab;".repeat(52) + "javascript:alert(1))", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate(
                        "# Project\n[x](web&plus;evil:payload)", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate(
                        "# Project\n[x](foo&period;bar:payload)", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n<!-- generated -->", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n<!DOCTYPE html>", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate(
                        "# Project\n<img src=https://evil.example/pixel title=\"`\">`", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate(
                        "# Project\n<img src=https://evil.example/pixel title=\"<\">", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate(
                        "# Project\n<img src=https://evil.example/pixel\n title=\"`\">`", "README.md"));
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate(
                        "# Project\n<img src=https://evil.example/pixel\n```text title=\">\n```", "README.md"));

        String safe = "# Project\n\nConfiguration file: config.yml. Java script: optional. "
                + "Use `List<String>` and [Site](https://example.com) or [local](docs/setup.md).\n\n"
                + "```html\n<div>literal</div>\n```";
        assertEquals(safe + "\n", DocumentationValidator.validate(safe, "README.md"));
        assertEquals("# Project\n[guide]: README\nNext: value\n",
                DocumentationValidator.validate("# Project\n[guide]: README\nNext: value", "README.md"));
    }

    @Test
    void handlesLargeAdversarialMarkdownWithoutBacktracking() {
        String manyUnclosedTags = "# Project\n" + "<a".repeat(500_000);
        String manyUnclosedLabels = "# Project\n" + "[".repeat(1_000_000);
        String manyInlineSpans = "# Project\n" + "`a`".repeat(100_000);
        String manyShortLines = "# Project\n" + "x\n".repeat(900_000);

        assertEquals(manyUnclosedTags + "\n",
                assertDoesNotThrow(() -> DocumentationValidator.validate(manyUnclosedTags, "README.md")));
        assertEquals(manyUnclosedLabels + "\n",
                assertDoesNotThrow(() -> DocumentationValidator.validate(manyUnclosedLabels, "README.md")));
        assertEquals(manyInlineSpans + "\n", assertTimeout(Duration.ofSeconds(3),
                () -> DocumentationValidator.validate(manyInlineSpans, "README.md")));
        assertEquals(manyShortLines, assertTimeout(Duration.ofSeconds(3),
                () -> DocumentationValidator.validate(manyShortLines, "README.md")));
    }

    @Test
    void rejectsUnclosedFencedCodeBlock() {
        assertThrows(IOException.class,
                () -> DocumentationValidator.validate("# Project\n```java\nclass App {}", "README.md"));
    }

    @Test
    void redactsSecretsAgainBeforeGeneratedDocumentationIsAccepted() throws Exception {
        String token = "gsk_abcdefghijklmnopqrstuvwxyz123456";

        String validated = DocumentationValidator.redactAndValidate(
                "# Project\n\nGenerated value: " + token, "README.md");

        assertTrue(validated.contains("[REDACTED_SECRET]"));
        assertTrue(!validated.contains(token));
    }
}
