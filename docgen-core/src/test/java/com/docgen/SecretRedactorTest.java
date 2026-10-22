package com.docgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {
    @Test
    void redactsKnownTokenFormatsAndAssignments() {
        String groq = "gsk_" + "abcdefghijklmnopqrstuvwxyz123456";
        String aws = "AKIA" + "1234567890ABCDEF";
        String input = "GROQ=" + groq + "\npassword = super-secret\nAWS=" + aws;

        String redacted = SecretRedactor.redact(input);

        assertFalse(redacted.contains(groq));
        assertFalse(redacted.contains("super-secret"));
        assertFalse(redacted.contains(aws));
        assertTrue(redacted.contains("[REDACTED_SECRET]"));
    }

    @ParameterizedTest
    @MethodSource("commonTokenParts")
    void redactsCommonCloudAndSaasTokens(String prefix, String suffix) {
        String token = prefix + suffix;
        String redacted = SecretRedactor.redact("value: " + token + " trailing");

        assertFalse(redacted.contains(token), "token should be redacted: " + prefix + "...");
        assertTrue(redacted.contains("[REDACTED_SECRET]"));
    }

    static Stream<Arguments> commonTokenParts() {
        return Stream.of(
                Arguments.of("sk-proj-", "abcdefghijklmnopqrstuvwxyz12"),
                Arguments.of("sk-ant-api03-", "abcdefghijklmnopqrstuv"),
                Arguments.of("ghp_", "16C7e42F292c6912E7710c838347Ae178B4a"),
                Arguments.of("github_pat_", "11ABCDEFG0abcdefghijklmnopqrstuvwxyz"),
                Arguments.of("gho_", "16C7e42F292c6912E7710c838347Ae178B4a"),
                Arguments.of("glpat-", "abcdefghij1234567890"),
                Arguments.of("ASIA", "XYZ1234567890ABC"),
                Arguments.of("AIza", "SyA1234567890abcdefghijklmnopqrstuv"),
                Arguments.of("ya29.", "a0AfH6SMBx3jklmnopqrstuvwxyz1234"),
                Arguments.of("xox", "b-2012521-99999999999-abcdefghijklmnop"),
                Arguments.of("sk_", "live_4eC39HqLyjWDarjtT1zdp7dc"),
                Arguments.of("wh", "sec_abcdefghijklmnopqrstuvwx"),
                Arguments.of("S", "G.abcdefghijklmnop-.qrstuvwxyz123456-9"),
                Arguments.of("S", "K0123456789abcdef0123456789abcdef"),
                Arguments.of("npm_", "abcdefghijklmnopqrstuvwxyz123456"),
                Arguments.of("pypi-", "AgEIcHlwaS5vcmcCJGFiY2RlZmdo"),
                Arguments.of("hf_", "abcdefghijklmnopqrstuvwxyz"),
                Arguments.of("dop_v1_", "0123456789abcdef0123456789abcdef01234567"),
                Arguments.of("sh", "pat_0123456789abcdef0123456789abcdef"),
                Arguments.of("hvs.", "abcdefghijklmnopqrstuvwx"),
                Arguments.of("atlasv1.", "abcdefghijklmnopqrstuvwx"),
                Arguments.of("ddapi_", "abcdefghijklmnopqrstuvwx"),
                Arguments.of("sntrys_", "abcdefghijklmnopqrstuvwx"),
                Arguments.of("ops_", "abcdefghijklmnopqrstuvwx"),
                Arguments.of("mfa.", "abcdefghijklmnopqrstuvwx"),
                Arguments.of("AGE-SECRET-KEY-1", "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"),
                Arguments.of("eyJhbGciOiJIUzI1NiJ9.", "eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop")
        );
    }

    @Test
    void redactsAzureConnectionStringAndSasSignature() {
        String connection = "DefaultEndpointsProtocol=https;AccountName=acc;AccountKey=abcdefghijklmnopqrstuvwxyzABCDEF0123456789+/==;EndpointSuffix=core.windows.net";
        String sas = "https://acc.blob.core.windows.net/c/b.txt?sv=2024-01-01&sig=abcdefghijklmnopqrstuvwx1234";

        assertFalse(SecretRedactor.redact(connection).contains("abcdefghijklmnopqrstuvwxyzABCDEF0123456789+/=="));
        assertFalse(SecretRedactor.redact(sas).contains("sig=abcdefghijklmnopqrstuvwx1234"));
    }

    @Test
    void redactsUrlCredentialsKeepingStructure() {
        String redacted = SecretRedactor.redact("db=postgres://admin:hunter2secret@db.example.com:5432/app");

        assertFalse(redacted.contains("hunter2secret"));
        assertTrue(redacted.contains("postgres://admin:[REDACTED_SECRET]@db.example.com"));
    }

    @Test
    void redactsAuthorizationHeaders() {
        String bearer = SecretRedactor.redact("Authorization: Bearer abcDEF123456789012345.token-value");
        String basic = SecretRedactor.redact("Authorization: Basic dXNlcjpzZWNyZXQtcGFzcw==");

        assertFalse(bearer.contains("abcDEF123456789012345.token-value"));
        assertFalse(basic.contains("dXNlcjpzZWNyZXQtcGFzcw=="));
    }

    @Test
    void redactsPrivateKeyBlocksIncludingOpenSshAndPgp() {
        String input = """
                -----BEGIN OPENSSH PRIVATE KEY-----
                b3BlbnNzaC1rZXktdjEAAAAA
                -----END OPENSSH PRIVATE KEY-----
                -----BEGIN PGP PRIVATE KEY BLOCK-----
                lQdGBGXxyz
                -----END PGP PRIVATE KEY BLOCK-----
                """;

        String redacted = SecretRedactor.redact(input);

        assertFalse(redacted.contains("b3BlbnNzaC1rZXktdjEAAAAA"));
        assertFalse(redacted.contains("lQdGBGXxyz"));
    }

    @Test
    void redactsUnterminatedPrivateKeyBlockWithoutRegexBacktracking() {
        String adversarial = "prefix\n" + "-----BEGIN PRIVATE KEY-----\n".repeat(10_000)
                + "secret material without a footer";

        String redacted = SecretRedactor.redact(adversarial);

        assertEquals("prefix\n[REDACTED_SECRET]", redacted);
    }

    @Test
    void handlesManyMalformedCredentialTagsWithoutUnboundedAttributeScanning() {
        String adversarial = "<password ".repeat(10_000);

        assertEquals(adversarial, SecretRedactor.redact(adversarial));
    }

    @Test
    void handlesManyJwtPrefixesWithoutQuadraticRegexRetries() {
        String adversarial = "-eyJ".repeat(10_000);

        assertEquals(adversarial, SecretRedactor.redact(adversarial));
    }

    @Test
    void redactsQuotedJsonAssignmentsAndValuesWithSpaces() {
        String input = "{\"client_secret\": \"s3cr3t value with spaces\", \"aws_secret_access_key\": \"wJalrXUtnFEMI/K7MDENG\"}";

        String redacted = SecretRedactor.redact(input);

        assertFalse(redacted.contains("s3cr3t value with spaces"));
        assertFalse(redacted.contains("wJalrXUtnFEMI/K7MDENG"));
        assertTrue(redacted.contains("\"client_secret\": \"[REDACTED_SECRET]\""));
    }

    @Test
    void redactsCredentialCommandLineArgumentsWithoutTouchingOrdinaryFlags() {
        String input = "deploy --verbose --password hunter2 --api-key=\"key value\" --region local";

        String redacted = SecretRedactor.redact(input);

        assertFalse(redacted.contains("hunter2"));
        assertFalse(redacted.contains("key value"));
        assertTrue(redacted.contains("--password [REDACTED_SECRET]"));
        assertTrue(redacted.contains("--api-key=\"[REDACTED_SECRET]\""));
        assertTrue(redacted.contains("--verbose"));
        assertTrue(redacted.contains("--region local"));
    }

    @Test
    void redactsSimpleCredentialXmlElementsAndPreservesMarkup() {
        String input = "<config><username>admin</username><password>value with spaces</password><tokenizer>word</tokenizer></config>";

        String redacted = SecretRedactor.redact(input);

        assertFalse(redacted.contains("value with spaces"));
        assertTrue(redacted.contains("<password>[REDACTED_SECRET]</password>"));
        assertTrue(redacted.contains("<username>admin</username>"));
        assertTrue(redacted.contains("<tokenizer>word</tokenizer>"));
    }

    @Test
    void leavesOrdinaryCodeUntouched() {
        String input = "public static void main(String[] args) { tokenizer.encode(text); } // password policy requires 12 chars";

        assertEquals(input, SecretRedactor.redact(input));
    }
}
