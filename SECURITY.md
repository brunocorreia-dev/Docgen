# Security

## Data handling

DocGen scans repository text files and sends selected, redacted snippets to the configured model provider.

For each of the two generation requests, this data is sent to the selected provider:

- the redacted content of every included file (20,000 chars per file, 120,000 chars total, hard limits);
- redacted relative paths of included files (also as a bounded file tree);
- bounded, redacted names and reasons for files skipped by content limits;
- to Groq only: the request additionally carries the model name and your `GROQ_API_KEY` as the authorization header, to `https://api.groq.com`.

Groq may make at most three attempts for each prompt after rate-limit or server errors. Ollama requests go directly to IPv4 loopback at `http://127.0.0.1:11434`; JVM system proxies are disabled for that cleartext local client. Names of sensitive files, ignore-excluded files, unsupported files, and prompt-preview snapshots are never placed in prompts.

## Controls, in the order they apply

1. Filesystem confinement: the repository and every ancestor/file/ignore entry are opened through `SecureDirectoryStream` handles with `NOFOLLOW_LINKS`; there is no path-based fallback. Filesystems without this facility are rejected.
2. Bounded discovery: at most 4,096 entries, 512 attempted files, 64 levels and 512 prompt-visible path characters, with separate content/tree/metadata budgets.
3. Directory, preview and sensitive-path exclusions (`.git`, build outputs, agent folders, `docgen-*.prompt.txt`, `.env*`, key stores, private keys, `credentials*`, `*secret*`, service accounts and lock files).
4. Root/nested `.gitignore` plus root `.docgenignore`; unsafe, ambiguously cased, control-ambiguous, non-NFC, unsupported or oversized ignore rules fail closed. Ordered case-sensitive and ASCII-case-insensitive matching states are conservatively combined; directory negations do not override more-specific child exclusions, and non-NFC/decode-ambiguous pathnames are excluded. Parsing is bounded to 64 KiB per file, 512 ordered rules, 2,048 deterministic globs, 8,192 segment matchers, 250,000 NFA states and a global matching-work budget. `.docgenignore` negation is rejected.
5. Optional `--include-ext` allowlist, which can only narrow selection.
6. Secret redaction over the complete bounded source file before content truncation, and over every prompt-visible path/skip field.
7. Preflight: after scanning (except in `--dry-run`), the selected documentation or preview pair is checked before prompt construction or provider initialization. Missing/insecure output directories and overwrite conflicts fail at this point without a remote call.
8. Prompt boundary: content is wrapped as untrusted data, delimiter-lookalike tags are neutralized, and instructions are restated after the block.
9. Consent: Groq always requires `--allow-remote`. Interactive runs additionally require y/N confirmation; non-interactive runs must pass `--yes`. Neither flag alone authorizes a remote send.
10. Provider validation: non-success error bodies are discarded, response accumulation is bounded, and Groq/Ollama completion markers must indicate a complete result. Ollama's entire NDJSON response is capped at 16 MiB and received inside the timed HTTP exchange before parsing.
11. Output validation: generated text is redacted again, limited to 2,000,000 characters, checked for Unicode/control safety, required to start with an H1, and restricted to passive Markdown with web/relative links only.
12. Transactional publication: the writer repeats all preflight checks, stages both outputs through `CREATE_NEW` handle-relative channels, forces them to storage where supported, and only then publishes a rollback-aware pair. The output directory must already exist and be trusted/non-shared; POSIX group/world-writable directories and symlinked/unverifiable paths are refused. Existing targets require `--force`.
13. Inspection: `--dry-run` and `--preview-prompt` contact no provider. Previews use private POSIX permissions where available and are not replaced without `--force`.

## Release integrity

Releases publish `docgen.jar`, a CycloneDX 1.6 SBOM (`docgen.cdx.json`), SHA-256 checksums, and Sigstore bundles (`*.sigstore.json`) for all four files. They are signed keyless by the release workflow through GitHub Actions OIDC. The installer downloads artifacts from the latest `brunocorreia-dev/Docgen` release, strictly parses and verifies the JAR checksum, and, when cosign is available, verifies the JAR and checksum signatures against the release workflow identity on a tag. `DOCGEN_REQUIRE_SIGNATURE=1` makes cosign mandatory; `DOCGEN_SKIP_SIGNATURE=1` explicitly opts out of provenance verification.

Trust model: when signature verification is enabled, the signature authenticates this repository's release workflow identity and release tag. The reviewed workflow provides the build-provenance link by signing only artifacts from its same-run, read-only build job. A compromised GitHub release alone cannot swap the JAR and checksum together unnoticed. Without cosign, the checksum detects corruption but not a malicious replacement of both the artifact and checksum. Signatures do not protect against a compromise of the repository or of the workflow definition itself; review workflow changes and pinned action SHAs in pull requests.

## Automated security gates

- CodeQL analyzes Java with the extended security query suite on pushes and pull requests.
- GitHub Dependency Review rejects pull requests that introduce dependencies with moderate-or-higher known vulnerabilities.
- OSV-Scanner performs a full dependency scan on pushes, pull requests, weekly schedules, and release tags; a finding fails the job, and the release build depends on that gate.
- `mvn verify` must generate the CycloneDX SBOM successfully. CI retains it as a workflow artifact, and releases sign and publish it alongside the JAR.
- Workflow permissions default to none and are granted per job. External actions and reusable workflows are pinned to full commit SHAs; checkouts do not persist credentials.
- Release builds and tests run with `contents: read`. Only a separate job that does not check out or execute project code receives `contents: write` and `id-token: write` so it can sign and upload the already-built artifacts.

To make these checks mandatory before merging, repository administrators must configure branch protection or rulesets to require the CI jobs; workflow failures alone do not prevent a merge unless the corresponding checks are required.

## Reporting issues

Open a private security advisory or contact the repository owner before publishing a vulnerability.

## Known limits

- **Secret redaction is pattern-based.** It covers common cloud/SaaS token formats (AWS, Google, GitHub, GitLab, Slack, Stripe, SendGrid, Twilio, npm, PyPI, Hugging Face, DigitalOcean, Shopify, Azure, JWTs, private key blocks, URL-embedded and assignment-style credentials), but it cannot recognize proprietary or unprefixed formats, secrets split across lines, or encoded/obfuscated values. Use `.docgenignore` for files whose leakage would be unacceptable, and `--dry-run` / `--preview-prompt` to inspect before sending.
- **Prompt-injection defenses are mitigations, not guarantees.** Untrusted-data delimiters, tag neutralization, and post-block reinforcement reduce the chance that repository content steers the model, but no prompt eliminates this risk; generated documentation must be reviewed before publication.
- **`.docgenignore` supports no negation.** Patterns only exclude; a `!` line fails the scan instead of being silently ignored.
- **Secure traversal is intentionally not portable to every filesystem.** Providers without `SecureDirectoryStream` fail closed. Symlinks and ancestor renames are contained, but portable Java NIO cannot distinguish a hardlink or bind mount from a legitimate in-root inode; do not scan repositories writable by an untrusted local principal.
- **Pair writes are rollback-aware, not a crash journal.** File and directory syncs reduce loss, but abrupt process/OS failure between publications can leave a recovery file or a mixed pair requiring manual inspection. Portable Java NIO also has no conditional unlink/chmod by file identity, does not preserve every ACL/xattr, and cannot defend against a hostile process with the same account mutating a shared directory between checks. Use an existing, trusted, non-shared output directory; on POSIX it must not be writable by group or other users.
- **Validation establishes shape, not truth.** Passive-Markdown checks block active output forms but cannot prove generated statements are accurate or free of social-engineering content. Review generated documentation before publishing it.
- **Signature verification is only as strong as the workflow.** Keyless signing authenticates the `release.yml` identity and tag; assurance that the signed bytes came from the build depends on reviewing that workflow. It does not establish the absence of malicious code in the repository at that tag.
