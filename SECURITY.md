# Security

## Data handling

DocGen scans repository text files and sends selected, redacted snippets to the configured model provider.

When a generation run proceeds, exactly this data leaves the process:

- the redacted content of every included file (20,000 chars per file, 120,000 chars total, hard limits);
- the relative paths of included files (also as a file tree);
- the names and skip reasons of files skipped for size limits;
- to Groq only: the request additionally carries the model name and your `GROQ_API_KEY` as the authorization header, to `https://api.groq.com`.

Ollama requests go to `http://localhost:11434` and stay on the machine. Names of sensitive files, `.docgenignore`-excluded files, and unsupported files are never placed in prompts.

## Controls, in the order they apply

1. Directory skips (`.git`, `node_modules`, build outputs, IDE folders).
2. `.docgenignore` exclusions (glob patterns; no negation support — a rejected `!` pattern fails the scan loudly).
3. Sensitive-name and sensitive-extension exclusions (`.env*`, key stores, private keys, `credentials*`, `*secret*`, lock files). These run before the extension allowlist, so `--include-ext` can narrow the selection but can never re-include a protected file.
4. Optional `--include-ext` allowlist.
5. Secret redaction over every included file (see limits below).
6. Prompt boundary: content is wrapped in an untrusted-data block, delimiter-lookalike tags inside content are neutralized, and instructions are restated after the block.
7. Consent: Groq requires `--allow-remote`; interactive runs also require a y/N confirmation that states what is about to be sent (skippable with `--yes`).
8. Inspection: `--dry-run` (selection with reasons) and `--preview-prompt` (exact prompts written locally) contact no provider.

## Release integrity

Releases publish `docgen.jar`, `docgen.jar.sha256`, and Sigstore bundles (`*.sigstore.json`) for both, signed keyless by the release workflow through GitHub Actions OIDC. The installer always checks the checksum; when cosign is available it also verifies both signatures against the identity `https://github.com/wagcarneiro/docgen/.github/workflows/release.yml@refs/tags/...` and refuses to install on failure. `DOCGEN_REQUIRE_SIGNATURE=1` makes cosign mandatory; `DOCGEN_SKIP_SIGNATURE=1` opts out.

Trust model: the signature proves the artifacts were built by this repository's release workflow on a tag — a compromised GitHub release alone can no longer swap the JAR and checksum together unnoticed. It does not protect against a compromise of the repository or of the workflow definition itself; review workflow changes and pinned action SHAs in pull requests.

## Reporting issues

Open a private security advisory or contact the repository owner before publishing a vulnerability.

## Known limits

- **Secret redaction is pattern-based.** It covers common cloud/SaaS token formats (AWS, Google, GitHub, GitLab, Slack, Stripe, SendGrid, Twilio, npm, PyPI, Hugging Face, DigitalOcean, Shopify, Azure, JWTs, private key blocks, URL-embedded and assignment-style credentials), but it cannot recognize proprietary or unprefixed formats, secrets split across lines, or encoded/obfuscated values. Use `.docgenignore` for files whose leakage would be unacceptable, and `--dry-run` / `--preview-prompt` to inspect before sending.
- **Prompt-injection defenses are mitigations, not guarantees.** Untrusted-data delimiters, tag neutralization, and post-block reinforcement reduce the chance that repository content steers the model, but no prompt eliminates this risk; generated documentation must be reviewed before publication.
- **`.docgenignore` supports no negation.** Patterns only exclude; a `!` line fails the scan instead of being silently ignored.
- **Signature verification is only as strong as the workflow.** Keyless signing attests provenance (built by `release.yml` on a tag), not the absence of malicious code in the repository at that tag.
