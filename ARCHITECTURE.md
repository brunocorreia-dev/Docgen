# Architecture

DocGen is a small Java CLI organized around a linear documentation-generation pipeline.

```text
DocGenCLI
  -> RepoScanner (+ DocgenIgnore, SecretRedactor)
  -> [--dry-run exits here]
  -> secure output/preview preflight
  -> PromptBuilder
  -> [--preview-prompt exits here, before any provider exists]
  -> LLMProviderFactory
      -> GroqProvider | OllamaProvider
  -> DocumentationValidator
  -> OutputWriter (revalidates before publication)
```

## Components

- `DocGenCLI` parses command-line options, coordinates the workflow, and enforces the consent flow. `--dry-run` terminates after scanning; every other mode preflights its output pair before prompt construction, and `--preview-prompt` then terminates before any provider is constructed. Groq always requires `--allow-remote`; a real terminal additionally gets a y/N confirmation, while a run without a console must explicitly pass `--yes` as well.
- `RepoScanner` traverses through `SecureDirectoryStream` handles only, rejects symlinks and insecure filesystems, applies built-in/ignore/allowlist exclusions deterministically, redacts full bounded files before truncation, and enforces entry, depth, file-count, path, tree, metadata and content limits. Only bounded content-limit skips are surfaced to the model.
- `DocgenIgnore` loads the root `.docgenignore` and scoped root/nested `.gitignore` files through secure handles. Its bounded iterative matcher supports common git-style wildcards without regex backtracking, reserves global glob/segment/NFA allocation budgets before compiling, and keeps directory negations from cancelling more-specific child rules; `.docgenignore` remains exclusion-only.
- `SecretRedactor` centralizes best-effort removal of common cloud/SaaS tokens, private key blocks, URL-embedded credentials, authorization headers, and credential assignments.
- `PromptBuilder` redacts every prompt-visible content/path/metadata field, wraps the snapshot in explicit untrusted-data boundaries, neutralizes delimiter-lookalike tags, and restates the rules after the data block. It demands passive Markdown.
- `RepoContext` carries the snapshot; it distinguishes prompt-visible skips (size limits) from local-only exclusions (sensitive/ignored files), so excluded names never reach a prompt.
- `LLMProvider` is the provider abstraction; implementations declare `isRemote()` and `describeDestination()`, which drive the CLI's consent checks and warnings.
- `GroqProvider` calls the fixed Groq HTTPS endpoint with bounded retries, discards error bodies, bounds successful response bytes, and requires a complete `finish_reason`.
- `OllamaProvider` calls fixed localhost endpoints without a system proxy, receives the complete response under the HTTP timeout with a 16 MiB cap, bounds NDJSON lines/fragments/total output, and requires a terminal completion marker.
- `DocumentationValidator` bounds and re-redacts model output, validates Unicode and Markdown structure, and enforces an H1/passive-Markdown contract before publication. Provider adapters separately require a successful completion marker.
- `SecurePairWriter` is shared by documentation and preview writers. It requires an existing trusted/non-shared output directory (and rejects POSIX group/world-writable directories), opens every ancestor with secure handles, stages through private `CREATE_NEW` channels, verifies file identities and digests, publishes without replacing a concurrently appearing final target, and restores quarantined originals in reverse order on failure.
- `OutputWriter` and `PromptPreviewWriter` select the target pair and transaction messages; neither replaces existing files without `--force`. Their read-only preflight rejects an invalid directory or target before prompt/provider work, and the actual write repeats the validation to contain races.

## Security boundaries

Groq is a remote boundary: repository snippets leave the machine only when `--allow-remote` is passed, `GROQ_API_KEY` is set, and the user confirms the send interactively or supplies `--yes` in a non-interactive run. Ollama is treated as local because requests are sent directly, without a system proxy, to IPv4 loopback at `127.0.0.1:11434`. `--dry-run` and `--preview-prompt` allow full inspection of the selection and the exact prompts without crossing any boundary.

Scanned repository files remain untrusted throughout the pipeline. Prompt hardening reduces the chance that malicious text changes model behavior, while response completion checks, final redaction and passive-Markdown validation constrain what can be published. None proves semantic correctness, and generated output still requires review. Filesystems lacking secure directory handles fail closed; hardlinks/bind mounts and abrupt-crash pair consistency remain documented boundaries.

## Build and release

The Maven build produces a shaded executable JAR and a CycloneDX 1.6 JSON SBOM. CI runs tests and SBOM generation, CodeQL with extended security queries, GitHub Dependency Review on pull requests, and full OSV dependency scans (also scheduled weekly). The release workflow is restricted to `brunocorreia-dev/Docgen`; scan and build use the immutable commit from the release event, the build depends on a clean OSV scan, runs `mvn verify`, and generates checksums for the JAR and SBOM in a job limited to `contents: read`. A separate job downloads those workflow artifacts, performs no checkout or Maven execution, and alone receives `id-token: write` and `contents: write` to sign all four files keyless with cosign via GitHub Actions OIDC and upload them with their `*.sigstore.json` bundles. All external actions are pinned to full commit SHAs. `install.sh` downloads the latest release, always verifies a strictly parsed JAR checksum, and, when cosign is present, verifies the JAR and checksum signatures against the canonical release workflow identity on a tag.
