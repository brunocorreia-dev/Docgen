# DocGen

DocGen is a Java 21 CLI that scans a repository and generates two documentation files with an LLM:

- `README.md`
- `ARCHITECTURE.md`

It supports Groq as a remote provider and Ollama as a local provider.

## Security defaults

DocGen is designed to avoid the main foot-guns in automated documentation generation:

- Groq requires `--allow-remote` because repository content is sent to a remote API. Interactive runs additionally ask for confirmation before anything is sent; runs without a console (such as CI and many IDE launches) must also pass `--yes`. `--yes` never replaces `--allow-remote`.
- Groq API keys are read only from `GROQ_API_KEY`, not from CLI arguments.
- Known secrets are redacted before prompt construction (common cloud/SaaS token formats, private key blocks, credential assignments, URLs with embedded passwords).
- Sensitive files such as `.env`, private keys and credential files are skipped and can never be re-included, not even via `--include-ext`.
- Root and nested `.gitignore` files are honored, and a root `.docgenignore` can exclude anything you decide must never be scanned; excluded names are not mentioned inside prompts.
- Repository traversal and output writes require secure, handle-relative filesystem operations. Symlinks in the root, any ancestor, directory, file, ignore file, or output path are never followed. The output directory must already exist, be trusted and non-shared, and, on POSIX filesystems, must not be writable by group or other users.
- `--dry-run` shows exactly which files would be included or excluded without contacting any provider; `--preview-prompt` writes the exact prompts with owner-only permissions where POSIX attributes are available and does not replace an existing preview unless `--force` is supplied.
- Existing `README.md` and `ARCHITECTURE.md` are not overwritten unless `--force` is provided.
- Missing/insecure output directories and overwrite conflicts fail before prompt construction or provider initialization; the final write revalidates the same conditions against races.
- Repository content is delimited as untrusted inside prompts, delimiter-lookalike tags in file content are neutralized, and the security rules are restated after the content block to reduce prompt-injection risk.
- Model responses must be complete, are redacted again, and must pass a bounded passive-Markdown contract before either output file is written.

These controls reduce risk, but they are not a substitute for reviewing generated documentation before publishing it. See [SECURITY.md](SECURITY.md) for the exact data-egress list and the known limits of each control.

## Requirements

- Java 21+
- Maven 3.9+ to build from source
- A filesystem whose Java provider implements `SecureDirectoryStream`; DocGen fails closed instead of using an insecure fallback
- An existing, trusted output directory; on POSIX it must not be group/world-writable
- One provider:
  - Groq API key in `GROQ_API_KEY`, or
  - Ollama running locally on `http://127.0.0.1:11434`

## Build

```sh
mvn -B test
mvn -B package
```

The executable JAR is created at `target/docgen.jar`.

## Usage

Create a private output directory once (the writer intentionally does not create path components):

```sh
mkdir -p ./generated
chmod 700 ./generated
```

Inspect what would be scanned (nothing is sent anywhere):

```sh
java -jar target/docgen.jar --dry-run /path/to/repository
```

Inspect the exact prompts that a real run would send (written locally, nothing is sent):

```sh
java -jar target/docgen.jar --preview-prompt --output ./generated /path/to/repository
```

Generate docs with Groq:

```sh
export GROQ_API_KEY="..."
java -jar target/docgen.jar --allow-remote --output ./generated /path/to/repository
```

The command above asks for y/N confirmation in a real terminal. In CI or another non-interactive environment, add `--yes`; without both `--allow-remote` and `--yes`, no remote request is made.

Generate docs with local Ollama:

```sh
java -jar target/docgen.jar --provider ollama --model llama3.2 --output ./generated /path/to/repository
```

Overwrite existing output files:

```sh
java -jar target/docgen.jar --allow-remote --force /path/to/repository
```

## Controlling what is scanned

The following controls narrow the scan; none can widen it past the built-in sensitive-file exclusions:

1. **Built-in exclusions** — agent/IDE/build directories, prompt-preview snapshots, `.env*`, `credentials*`, `*secret*`, service-account files, private keys, key stores and other sensitive path components are excluded before any allowlist can apply.
2. **`.gitignore`** — root and nested files are loaded in scope. Ordered negation, BOMs, `*`, `?`, ASCII bracket ranges and `**` directory segments are supported with Git-compatible UTF-8 byte matching; POSIX/nested or non-ASCII bracket classes and non-NFC patterns fail closed instead of being approximated. Because repository `core.ignoreCase` is not trusted, ordered case-sensitive and ASCII-case-insensitive states are both evaluated and a path excluded by either remains excluded. Ambiguously cased ignore filenames and ambiguous control characters fail closed, while non-NFC/decode-ambiguous pathnames are excluded. A child cannot be re-included while its parent directory remains ignored, and a directory negation cannot cancel a more-specific child exclusion; sensitive-file exclusions still win over negation.
3. **`.docgenignore`** — a root-only, exclusion-only file. Blank lines and `#` comments are ignored. A pattern without `/` matches at any depth (`notes.md`, `*.sql`); a pattern with `/` is relative to the root (`docs/internal.md`); a trailing `/` excludes a directory and everything under it (`docs/`). Negation (`!`) is rejected rather than silently ignored.
4. **`--include-ext java,md,xml`** — an optional allowlist that narrows the built-in supported set.
5. **`--dry-run`** — prints the bounded local selection report and exits without contacting any provider.

The scan is deterministic and bounded to 4,096 discovered entries, 512 attempted files, 64 directory levels, 512 characters per prompt-visible path, 256 KiB read per file, 20,000 prompt characters per file, and 120,000 file-content characters total. Each ignore file is limited to 64 KiB; at most 512 ordered rules, 2,048 deterministic compiled globs, 8,192 segment matchers and 250,000 NFA states (both case modes) are accepted, under a global matching-work budget. File-tree and skipped-file metadata have separate bounded budgets. A limit excess is reported or fails closed; it never causes unbounded matcher allocation, an unbounded prompt, or regex-style glob backtracking.

## What is sent to the provider

When a generation run proceeds, each prompt contains the redacted content of included files, redacted relative paths/file tree, and bounded, redacted metadata for files skipped by content limits. A normal run makes one README request and one architecture request; Groq can make at most three attempts per request after rate-limit or server errors. With Groq this leaves your machine for the fixed `https://api.groq.com` endpoint, authenticated with `GROQ_API_KEY`; with Ollama it goes directly (system proxies are disabled) to the fixed IPv4 loopback endpoint `http://127.0.0.1:11434`. Names of sensitive, ignored, unsupported, or allowlist-filtered files never enter a prompt. Secret redaction is best-effort — review with `--preview-prompt` when in doubt.

Before either prompt is built, a normal run verifies that the output directory and both final targets are eligible for publication. Provider responses are bounded before accumulation, must carry a successful completion marker, are redacted a second time, and are limited to 2,000,000 characters. Ollama's complete NDJSON body is additionally capped at 16 MiB and consumed by the timed HTTP exchange before parsing. Output must start with an H1 and use passive Markdown: no raw HTML, images, autolinks, or non-web link schemes. Only after both documents validate does the writer revalidate the output conditions and start its handle-relative, rollback-aware pair transaction.

## Options

```text
--provider <groq|ollama>   LLM provider. Default: groq
--model <name>             Provider model
--allow-remote             Required for Groq remote API use
--dry-run                  List included/excluded files and exit; contacts no provider
--preview-prompt           Write the exact prompts to the output dir; contacts no provider
--include-ext <exts>       Comma-separated extension allowlist (e.g. java,md,xml)
-y, --yes                  Confirm remote sending in non-interactive runs
-o, --output <dir>         Output directory. Default: current directory
--force                    Overwrite existing documentation or prompt previews
-h, --help                 Show help
-V, --version              Show version
```

The output directory must already exist and satisfy the secure-filesystem requirements above. `--preview-prompt` writes `docgen-readme.prompt.txt` and `docgen-architecture.prompt.txt` there with owner-only permissions where POSIX attributes are available. Existing previews are preserved unless `--force` is supplied, and preview filenames are always excluded from future scans.

## Installation from release

```sh
curl -fsSL https://raw.githubusercontent.com/brunocorreia-dev/Docgen/main/install.sh | sh
```

### Verifying a release manually

Releases are signed keyless via GitHub Actions OIDC (Sigstore), so there is no long-lived public key to distribute: the signing identity is the release workflow itself. Download `docgen.jar` and `docgen.jar.sigstore.json` from the release, then:

```sh
cosign verify-blob \
  --bundle docgen.jar.sigstore.json \
  --certificate-identity-regexp '^https://github\.com/brunocorreia-dev/Docgen/\.github/workflows/release\.yml@refs/tags/' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  docgen.jar
```

A passing verification proves the JAR was signed under this repository's `release.yml` identity for a release tag. Build provenance additionally depends on the reviewed workflow, which signs only the artifacts received from its same-run, read-only build job. The same check works for `docgen.jar.sha256`, the CycloneDX SBOM `docgen.cdx.json`, and its checksum, each with its corresponding `.sigstore.json` bundle.

## Development

```sh
mvn -B test
mvn -B verify
```

CI runs tests, generates and retains a CycloneDX SBOM, reviews dependency changes, blocks known vulnerable dependencies with OSV-Scanner, and performs CodeQL analysis with the extended security query suite. Releases are restricted to `brunocorreia-dev/Docgen` and rerun the vulnerability gate. The build and tests execute in a job limited to `contents: read`; a separate job with no checkout or Maven execution receives only the resulting artifacts, signs them keyless through GitHub OIDC, and uploads the JAR, CycloneDX SBOM, and both checksums. Every third-party GitHub Action reference is pinned to a full commit SHA and kept updateable by Dependabot.
