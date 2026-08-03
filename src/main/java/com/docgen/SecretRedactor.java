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
    private static final int MAX_KEY_MARKER_CHARACTERS = 128;
    private static final Pattern PRIVATE_KEY_BEGIN = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----");
    private static final Pattern PRIVATE_KEY_END = Pattern.compile(
            "-----END [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----");
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
            Pattern.compile("\\b(?:hvs|hvb)\\.[A-Za-z0-9_\\-]{20,}\\b"),                         // HashiCorp Vault
            Pattern.compile("\\batlasv1\\.[A-Za-z0-9_\\-]{20,}\\b"),                              // Terraform Cloud
            Pattern.compile("\\b(?:ddapi|ddapp)_[A-Za-z0-9_\\-]{20,}\\b"),                        // Datadog
            Pattern.compile("\\bsntrys_[A-Za-z0-9_\\-]{20,}\\b"),                                // Sentry
            Pattern.compile("\\bops_[A-Za-z0-9_\\-]{20,}\\b"),                                   // 1Password service account
            Pattern.compile("\\bmfa\\.[A-Za-z0-9_\\-]{20,}\\b"),                                // Discord MFA token
            Pattern.compile("\\bAGE-SECRET-KEY-1[A-Z0-9]{30,}\\b"),                               // age identity
            Pattern.compile("(?i)\\bAccountKey=[A-Za-z0-9+/=]{40,}"),                            // Azure storage connection string
            Pattern.compile("(?i)([?&]sig=)[A-Za-z0-9%+/=]{20,}"),                               // Azure SAS signature
            // Credentials embedded in URLs: scheme://user:password@host
            Pattern.compile("(://[^/\\s:@]{1,64}:)[^@/\\s]{1,256}(?=@)"),
            // Authorization headers
            Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/\\-]{16,}=*"),
            Pattern.compile("(?i)\\b(basic\\s+)[A-Za-z0-9+/=]{16,}"),
            // Secret-bearing command-line flags and simple XML elements. These
            // require explicit credential names to avoid redacting ordinary flags
            // or arbitrary XML content.
            Pattern.compile("(?i)(--?(?:api[_-]?key|secret|token|passwd|password|passphrase|credential|auth[_-]?token|access[_-]?key|private[_-]?key|client[_-]?secret)(?:=|\\s+))(?:(['\"])[^'\"]{1,512}\\2|[^'\"\\s]{1,512})"),
            Pattern.compile("(?i)(<\\s*(?:api[_-]?key|secret|token|passwd|password|passphrase|credential|auth[_-]?token|access[_-]?key|private[_-]?key|client[_-]?secret)\\b[^><\\r\\n]{0,512}>)[^<]{1,2048}(?=</\\s*(?:api[_-]?key|secret|token|passwd|password|passphrase|credential|auth[_-]?token|access[_-]?key|private[_-]?key|client[_-]?secret)\\s*>)"),
            // Generic key/value assignments whose key names a credential, with or
            // without quotes, in code, properties, YAML and JSON
            Pattern.compile("(?i)((?<![A-Za-z0-9_.\\-])[A-Za-z0-9_.\\-]{0,64}(?:api[_-]?key|apikey|secret|token|passwd|password|passphrase|credential|authorization|auth[_-]?token|access[_-]?key|private[_-]?key|client[_-]?secret|signing[_-]?key|connection[_-]?string)[A-Za-z0-9_.\\-]{0,64}['\"]?\\s*[:=]\\s*)(?:(['\"])[^'\"]{1,512}\\2|[^'\"\\s#]{1,512})")
    );

    private SecretRedactor() {
    }

    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String redacted = redactJsonWebTokens(redactPrivateKeyBlocks(input));
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

    /**
     * Redacts complete and unterminated private-key blocks in one forward pass.
     * A single unbounded reluctant regex retries from every BEGIN marker when no
     * END marker exists and is quadratic on adversarial repository content.
     */
    private static String redactPrivateKeyBlocks(String input) {
        StringBuilder output = null;
        int searchCursor = 0;
        int copyCursor = 0;
        while (searchCursor < input.length()) {
            int begin = input.indexOf("-----BEGIN ", searchCursor);
            if (begin < 0) {
                break;
            }
            int headerEnd = input.indexOf("-----", begin + "-----BEGIN ".length());
            if (headerEnd < 0 || headerEnd + 5 - begin > MAX_KEY_MARKER_CHARACTERS) {
                searchCursor = begin + 1;
                continue;
            }
            headerEnd += 5;
            if (!PRIVATE_KEY_BEGIN.matcher(input.substring(begin, headerEnd)).matches()) {
                searchCursor = begin + 1;
                continue;
            }

            if (output == null) {
                output = new StringBuilder(input.length());
            }
            output.append(input, copyCursor, begin).append(REDACTED);

            int search = headerEnd;
            int footerEnd = -1;
            while (search < input.length()) {
                int footer = input.indexOf("-----END ", search);
                if (footer < 0) {
                    break;
                }
                int markerEnd = input.indexOf("-----", footer + "-----END ".length());
                if (markerEnd < 0) {
                    break;
                }
                markerEnd += 5;
                if (markerEnd - footer <= MAX_KEY_MARKER_CHARACTERS
                        && PRIVATE_KEY_END.matcher(input.substring(footer, markerEnd)).matches()) {
                    footerEnd = markerEnd;
                    break;
                }
                search = footer + 1;
            }
            if (footerEnd < 0) {
                return output.toString();
            }
            copyCursor = footerEnd;
            searchCursor = footerEnd;
        }
        if (output == null) {
            return input;
        }
        output.append(input, copyCursor, input.length());
        return output.toString();
    }

    /** Redacts three-segment JWTs without retrying a long segment at each eyJ. */
    private static String redactJsonWebTokens(String input) {
        StringBuilder output = null;
        int searchCursor = 0;
        int copyCursor = 0;
        while (searchCursor < input.length()) {
            int start = input.indexOf("eyJ", searchCursor);
            if (start < 0) {
                break;
            }
            if (start > 0 && isWordCharacter(input.charAt(start - 1))) {
                searchCursor = start + 3;
                continue;
            }

            int firstEnd = base64UrlEnd(input, start);
            if (firstEnd - start < 13 || firstEnd >= input.length() || input.charAt(firstEnd) != '.') {
                searchCursor = Math.max(start + 3, firstEnd);
                continue;
            }
            int secondStart = firstEnd + 1;
            if (!input.startsWith("eyJ", secondStart)) {
                searchCursor = secondStart;
                continue;
            }
            int secondEnd = base64UrlEnd(input, secondStart);
            if (secondEnd - secondStart < 13 || secondEnd >= input.length() || input.charAt(secondEnd) != '.') {
                searchCursor = Math.max(secondStart + 3, secondEnd);
                continue;
            }
            int thirdStart = secondEnd + 1;
            int thirdEnd = base64UrlEnd(input, thirdStart);
            if (thirdEnd - thirdStart < 10) {
                searchCursor = Math.max(thirdStart, thirdEnd);
                continue;
            }

            if (output == null) {
                output = new StringBuilder(input.length());
            }
            output.append(input, copyCursor, start).append(REDACTED);
            copyCursor = thirdEnd;
            searchCursor = thirdEnd;
        }
        if (output == null) {
            return input;
        }
        output.append(input, copyCursor, input.length());
        return output.toString();
    }

    private static int base64UrlEnd(String value, int start) {
        int index = start;
        while (index < value.length()) {
            char character = value.charAt(index);
            if (!isAsciiLetterOrDigit(character) && character != '_' && character != '-') {
                break;
            }
            index++;
        }
        return index;
    }

    private static boolean isAsciiLetterOrDigit(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9';
    }

    private static boolean isWordCharacter(char value) {
        return value == '_' || Character.isLetterOrDigit(value);
    }
}
