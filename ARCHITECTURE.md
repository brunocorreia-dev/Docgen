# Architecture

DocGen is a small Java CLI organized around a linear documentation-generation pipeline.

```text
DocGenCLI
  -> RepoScanner (+ DocgenIgnore, SecretRedactor)
  -> PromptBuilder
  -> [--dry-run / --preview-prompt exit here, before any provider exists]
  -> LLMProviderFactory
      -> GroqProvider | OllamaProvider
  -> OutputWriter
```

## Components

- `DocGenCLI` parses command-line options, coordinates the workflow, and enforces the consent flow: `--dry-run` and `--preview-prompt` terminate before any provider is constructed, and remote providers trigger an explicit warning plus an interactive y/N confirmation (skippable with `--yes`) that states what is about to leave the machine.
- `RepoScanner` walks the repository deterministically, applies exclusions in a fixed order (skipped directories, `.docgenignore`, sensitive names/extensions, unsupported types, optional `--include-ext` allowlist), redacts secrets, and enforces per-file and total prompt-size limits. Exclusions are recorded with reasons for `--dry-run`; only size-based skips are surfaced to the model.
- `DocgenIgnore` loads glob exclusion patterns from `.docgenignore` at the repository root; negation patterns are rejected loudly rather than silently ignored.
- `SecretRedactor` centralizes best-effort removal of common cloud/SaaS tokens, private key blocks, URL-embedded credentials, authorization headers, and credential assignments.
- `PromptBuilder` wraps scanned content in explicit untrusted-data boundaries, neutralizes delimiter-lookalike tags inside file content so files cannot forge or close the boundary, and restates the security rules after the data block. It demands Markdown-only output.
- `RepoContext` carries the snapshot; it distinguishes prompt-visible skips (size limits) from local-only exclusions (sensitive/ignored files), so excluded names never reach a prompt.
- `LLMProvider` is the provider abstraction; implementations declare `isRemote()` and `describeDestination()`, which drive the CLI's consent checks and warnings.
- `GroqProvider` calls Groq's OpenAI-compatible chat completion endpoint with JSON serialization via Jackson, request timeouts, HTTP status checks and bounded retries.
- `OllamaProvider` calls a local Ollama server, validates connectivity through `/api/tags`, parses streamed JSON lines and checks HTTP status codes.
- `OutputWriter` writes generated files atomically where supported and refuses to overwrite existing documentation unless `--force` is set.

## Security boundaries

Groq is a remote boundary: repository snippets leave the machine only when `--allow-remote` is passed, `GROQ_API_KEY` is set, and (in interactive runs) the user confirms the send. Ollama is treated as local because requests are sent to `localhost:11434`. `--dry-run` and `--preview-prompt` allow full inspection of the selection and the exact prompts without crossing any boundary.

Scanned repository files remain untrusted throughout the pipeline. Prompt hardening (delimiters, tag neutralization, post-block reinforcement) reduces the chance that malicious text inside a repository changes model behavior, but generated output must still be reviewed before publication. SECURITY.md documents the exact egress list and the limits of each control.

## Build and release

The Maven build produces a shaded executable JAR. CI runs tests on pushes and pull requests. Release builds run `mvn verify`, produce `target/docgen.jar`, generate `docgen.jar.sha256`, sign both artifacts keyless with cosign via GitHub Actions OIDC (uploading `*.sigstore.json` bundles), and upload everything to the GitHub release. `install.sh` always verifies the checksum and, when cosign is present, also verifies both signatures against the release workflow identity.
