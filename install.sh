#!/usr/bin/env sh
set -eu

REPO="wagcarneiro/docgen"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
BIN="$INSTALL_DIR/docgen"
JAR="$INSTALL_DIR/docgen.jar"
TMP_DIR="$(mktemp -d)"

# Release artifacts are signed keyless by the release workflow via GitHub
# Actions OIDC (Sigstore). The signing identity is therefore this repository's
# release workflow running on a tag, not a long-lived key.
CERT_IDENTITY_REGEXP="^https://github\\.com/$REPO/\\.github/workflows/release\\.yml@refs/tags/"
CERT_OIDC_ISSUER="https://token.actions.githubusercontent.com"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

download() {
  if command_exists curl; then
    curl -fsSL "$1" -o "$2"
  elif command_exists wget; then
    wget -q "$1" -O "$2"
  else
    echo "curl or wget is required to install DocGen." >&2
    exit 1
  fi
}

if ! command_exists java; then
  echo "Java 21+ is required. Install Java before running DocGen." >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR"
BASE_URL="https://github.com/$REPO/releases/latest/download"

download "$BASE_URL/docgen.jar" "$TMP_DIR/docgen.jar"
download "$BASE_URL/docgen.jar.sha256" "$TMP_DIR/docgen.jar.sha256"

cd "$TMP_DIR"
if command_exists sha256sum; then
  sha256sum -c docgen.jar.sha256
elif command_exists shasum; then
  EXPECTED="$(awk '{print $1}' docgen.jar.sha256)"
  ACTUAL="$(shasum -a 256 docgen.jar | awk '{print $1}')"
  [ "$EXPECTED" = "$ACTUAL" ] || { echo "Checksum verification failed." >&2; exit 1; }
else
  echo "sha256sum or shasum is required to verify the download." >&2
  exit 1
fi

# Signature verification policy:
# - cosign installed: verify both artifacts against the release workflow
#   identity; any failure (including missing signature files) aborts unless
#   DOCGEN_SKIP_SIGNATURE=1 is set.
# - cosign not installed: continue with checksum only and print a warning,
#   unless DOCGEN_REQUIRE_SIGNATURE=1 is set, which aborts instead.
if [ "${DOCGEN_SKIP_SIGNATURE:-0}" = "1" ]; then
  echo "WARNING: DOCGEN_SKIP_SIGNATURE=1 set; skipping signature verification." >&2
elif command_exists cosign; then
  if ! download "$BASE_URL/docgen.jar.sigstore.json" "$TMP_DIR/docgen.jar.sigstore.json" ||
     ! download "$BASE_URL/docgen.jar.sha256.sigstore.json" "$TMP_DIR/docgen.jar.sha256.sigstore.json"; then
    echo "Signature bundles are missing from the latest release. Refusing to install." >&2
    echo "Set DOCGEN_SKIP_SIGNATURE=1 to install with checksum verification only." >&2
    exit 1
  fi
  cosign verify-blob \
    --bundle docgen.jar.sigstore.json \
    --certificate-identity-regexp "$CERT_IDENTITY_REGEXP" \
    --certificate-oidc-issuer "$CERT_OIDC_ISSUER" \
    docgen.jar
  cosign verify-blob \
    --bundle docgen.jar.sha256.sigstore.json \
    --certificate-identity-regexp "$CERT_IDENTITY_REGEXP" \
    --certificate-oidc-issuer "$CERT_OIDC_ISSUER" \
    docgen.jar.sha256
  echo "Signature verification passed (signed by $REPO release workflow)."
elif [ "${DOCGEN_REQUIRE_SIGNATURE:-0}" = "1" ]; then
  echo "DOCGEN_REQUIRE_SIGNATURE=1 is set but cosign is not installed." >&2
  echo "Install cosign (https://docs.sigstore.dev/cosign/system_config/installation/) and retry." >&2
  exit 1
else
  echo "WARNING: cosign not found; signature verification skipped (checksum only)." >&2
  echo "Install cosign to also verify that artifacts were built by the $REPO release workflow." >&2
fi

mv "$TMP_DIR/docgen.jar" "$JAR"
cat > "$BIN" <<EOF
#!/usr/bin/env sh
exec java -jar "$JAR" "\$@"
EOF
chmod +x "$BIN"

echo "DocGen installed at $BIN"
echo "For Groq: export GROQ_API_KEY=... and run docgen --allow-remote /path/to/repo"
echo "For local Ollama: run docgen --provider ollama /path/to/repo"
