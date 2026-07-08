package com.docgen;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Best-effort, pattern-based secret redaction. False positives are acceptable
 * (they only redact more); false negatives are possible for proprietary token
 * formats, which is why redaction is one layer among several (file exclusion,
 * .docgenignore, --dry-run, --preview-prompt). See SECURITY.md for the limits.
 */
public final class SecretRedactor {
    private static final String REDACTED = "[REDACTED_SECRET]";
    private static final List<Pattern> TOKEN_PATTERNS = List.of(
            // Provider-prefixed API tokens (cloud / SaaS)
            Pattern.compile("\\bgsk_[A-Za-z0-9_\\-]{20,}\\b"),                                   // Groq
            Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{20,}\\b"),                                    // OpenAI, Anthropic (sk-ant-...)
            Pattern.compile("\\bghp_[A-Za-z0-9_]{20,}\\b"),                                      // GitHub classic PAT
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),                               // GitHub fine-grained PAT
            Pattern.compile("\\bgh[ousr]_[A-Za-z0-9_]{20,}\\b"),                                 // GitHub OAuth/app tokens
            Pattern.compile("\\bglpat-[A-Za-z0-9_\\-]{20,}\\b"),                                 // GitLab PAT
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),                                           // AWS access key id
            Pattern.compile("\\bASIA[0-9A-Z]{16}\\b"),                                           // AWS temporary access key id
            Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{35}\\b"),                                    // Google API key
            Pattern.compile("\\bya29\\.[A-Za-z0-9_\\-]{20,}\\b"),                                // Google OAuth access token
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9\\-]{10,}\\b"),                             // Slack tokens
            Pattern.compile("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{10,}\\b"),                       // Stripe secret/restricted keys
            Pattern.compile("\\bwhsec_[A-Za-z0-9]{16,}\\b"),                                     // Stripe webhook secret
            Pattern.compile("\\bSG\\.[A-Za-z0-9_\\-]{16,}\\.[A-Za-z0-9_\\-]{16,}\\b"),           // SendGrid
            Pattern.compile("\\bSK[0-9a-f]{32}\\b"),                                             // Twilio API key
            Pattern.compile("\\bnpm_[A-Za-z0-9]{20,}\\b"),                                       // npm token
            Pattern.compile("\\bpypi-[A-Za-z0-9_\\-]{20,}\\b"),                                  // PyPI token
            Pattern.compile("\\bhf_[A-Za-z0-9]{20,}\\b"),                                        // Hugging Face token
            Pattern.compile("\\bdop_v1_[0-9a-f]{32,}\\b"),                                       // DigitalOcean PAT
            Pattern.compile("\\bshpat_[0-9a-fA-F]{32,}\\b"),                                     // Shopify admin token
            Pattern.compile("(?i)\\bAccountKey=[A-Za-z0-9+/=]{40,}"),                            // Azure storage connection string
            Pattern.compile("(?i)([?&]sig=)[A-Za-z0-9%+/=]{20,}"),                               // Azure SAS signature
            Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{10,}\\.eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\b"), // JWT
            // Credentials embedded in URLs: scheme://user:password@host
            Pattern.compile("(://[^/\\s:@]{1,64}:)[^@/\\s]{1,256}(?=@)"),
            // Authorization headers
            Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/\\-]{16,}=*"),
            Pattern.compile("(?i)\\b(basic\\s+)[A-Za-z0-9+/=]{16,}"),
            // Private key blocks (PEM, OpenSSH, PGP)
            Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----"),
            // Generic key/value assignments whose key names a credential, with or
            // without quotes, in code, properties, YAML and JSON
            Pattern.compile("(?i)([A-Za-z0-9_.\\-]*(?:api[_-]?key|apikey|secret|token|passwd|password|credential|authorization|auth[_-]?token|access[_-]?key|private[_-]?key)[A-Za-z0-9_.\\-]*['\"]?\\s*[:=]\\s*)(?:(['\"])[^'\"]{1,512}\\2|[^'\"\\s#]{1,512})")
    );

    private SecretRedactor() {
    }

    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String redacted = input;
        for (Pattern pattern : TOKEN_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(match -> {
                if (match.groupCount() >= 1 && match.group(1) != null) {
                    String quote = match.groupCount() >= 2 && match.group(2) != null ? match.group(2) : "";
                    return match.group(1) + quote + REDACTED + quote;
                }
                return REDACTED;
            });
        }
        return redacted;
    }
}
