# DocGen

DocGen is a Java 21 CLI that scans a repository and generates two documentation files with an LLM:

- `README.md`
- `ARCHITECTURE.md`

It supports Groq as a remote provider and Ollama as a local provider.

## Security defaults

DocGen is designed to avoid the main foot-guns in automated documentation generation:

- Groq requires `--allow-remote` because repository content is sent to a remote API, and interactive runs additionally ask for confirmation before anything is sent (`--yes` skips the question, not the `--allow-remote` requirement).
- Groq API keys are read only from `GROQ_API_KEY`, not from CLI arguments.
- Known secrets are redacted before prompt construction (common cloud/SaaS token formats, private key blocks, credential assignments, URLs with embedded passwords).
- Sensitive files such as `.env`, private keys and credential files are skipped and can never be re-included, not even via `--include-ext`.
- A `.docgenignore` file excludes anything you decide must never be scanned; excluded names are not even mentioned inside prompts.
- `--dry-run` shows exactly which files would be included or excluded without contacting any provider; `--preview-prompt` writes the exact prompts to disk without sending them.
- Existing `README.md` and `ARCHITECTURE.md` are not overwritten unless `--force` is provided.
- Repository content is delimited as untrusted inside prompts, delimiter-lookalike tags in file content are neutralized, and the security rules are restated after the content block to reduce prompt-injection risk.

These controls reduce risk, but they are not a substitute for reviewing generated documentation before publishing it. See [SECURITY.md](SECURITY.md) for the exact data-egress list and the known limits of each control.

## Requirements

- Java 21+
- Maven 3.9+ to build from source
- One provider:
  - Groq API key in `GROQ_API_KEY`, or
  - Ollama running locally on `http://localhost:11434`

## Build

```sh
mvn -B test
mvn -B package
```

The executable JAR is created at `target/docgen.jar`.

## Usage

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

Generate docs with local Ollama:

```sh
java -jar target/docgen.jar --provider ollama --model llama3.2 --output ./generated /path/to/repository
```

Overwrite existing output files:

```sh
java -jar target/docgen.jar --allow-remote --force /path/to/repository
```

## Controlling what is scanned

Three mechanisms narrow the scan; none of them can widen it past the built-in sensitive-file exclusions:

1. **`.docgenignore`** — a file at the repository root. Blank lines and `#` comments are ignored. A pattern without `/` matches at any depth (`notes.md`, `*.sql`); a pattern with `/` is relative to the root (`docs/internal.md`); a trailing `/` excludes a directory and everything under it (`docs/`). Glob syntax (`*`, `?`, `**`) is supported. Negation (`!`) is not supported and rejected. Excluded files are omitted from prompts entirely, including their names.
2. **`--include-ext java,md,xml`** — an optional allowlist. When set, only files ending with the listed extensions are included. It narrows the built-in supported set and cannot re-include sensitive files.
3. **`--dry-run`** — prints included files, size-skipped files, and excluded files with the reason for each, then exits without contacting any provider. Use it to check the effect of the two mechanisms above.

## What is sent to the provider

When a generation run proceeds, the prompt contains: the redacted content of every included file (up to 20,000 chars per file and 120,000 chars total), the relative paths of included files, and the names of files skipped for size reasons. With Groq this leaves your machine to `https://api.groq.com`, authenticated with `GROQ_API_KEY`; with Ollama it stays on `http://localhost:11434`. Names of sensitive, ignored, or filtered-out files are never included. Secret redaction is best-effort — review with `--preview-prompt` when in doubt.

## Options

```text
--provider <groq|ollama>   LLM provider. Default: groq
--model <name>             Provider model
--allow-remote             Required for Groq remote API use
--dry-run                  List included/excluded files and exit; contacts no provider
--preview-prompt           Write the exact prompts to the output dir; contacts no provider
--include-ext <exts>       Comma-separated extension allowlist (e.g. java,md,xml)
-y, --yes                  Skip the interactive confirmation before remote sends
-o, --output <dir>         Output directory. Default: current directory
--force                    Overwrite README.md and ARCHITECTURE.md
-h, --help                 Show help
-V, --version              Show version
```

`--preview-prompt` writes `docgen-readme.prompt.txt` and `docgen-architecture.prompt.txt` into the output directory, overwriting previous previews.

## Installation from release

```sh
curl -fsSL https://raw.githubusercontent.com/wagcarneiro/docgen/main/install.sh | sh
```

The installer downloads `docgen.jar` from the latest GitHub release and always verifies the SHA-256 checksum. If [cosign](https://docs.sigstore.dev/cosign/system_config/installation/) is installed, it also verifies the Sigstore signatures and refuses to install artifacts that were not produced by this repository's release workflow. Set `DOCGEN_REQUIRE_SIGNATURE=1` to make cosign mandatory, or `DOCGEN_SKIP_SIGNATURE=1` to opt out of signature checks (checksum still applies).

### Verifying a release manually

Releases are signed keyless via GitHub Actions OIDC (Sigstore), so there is no long-lived public key to distribute: the signing identity is the release workflow itself. Download `docgen.jar` and `docgen.jar.sigstore.json` from the release, then:

```sh
cosign verify-blob \
  --bundle docgen.jar.sigstore.json \
  --certificate-identity-regexp '^https://github\.com/wagcarneiro/docgen/\.github/workflows/release\.yml@refs/tags/' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  docgen.jar
```

A passing verification proves the JAR was built and signed by this repository's `release.yml` workflow running on a tag. The same check works for `docgen.jar.sha256` with its own `.sigstore.json` bundle.

## Development

```sh
mvn -B test
mvn -B verify
```

CI runs the test suite on pushes and pull requests. Releases build with tests enabled, upload the JAR plus SHA-256 checksum, and sign both artifacts with cosign (keyless, GitHub OIDC).
