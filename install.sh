#!/usr/bin/env sh
set -eu

REPO="brunocorreia-dev/Docgen"
REQUIRE_SIGNATURE="${DOCGEN_REQUIRE_SIGNATURE:-0}"
SKIP_SIGNATURE="${DOCGEN_SKIP_SIGNATURE:-0}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
BIN="$INSTALL_DIR/docgen"
JAR="$INSTALL_DIR/docgen.jar"

case "$REQUIRE_SIGNATURE:$SKIP_SIGNATURE" in
  0:0|0:1|1:0) ;;
  1:1)
    echo "DOCGEN_REQUIRE_SIGNATURE=1 conflicts with DOCGEN_SKIP_SIGNATURE=1." >&2
    exit 1
    ;;
  *)
    echo "DOCGEN_REQUIRE_SIGNATURE and DOCGEN_SKIP_SIGNATURE accept only 0 or 1." >&2
    exit 1
    ;;
esac

TMP_DIR="$(mktemp -d)"
JAR_TMP=""
WRAPPER_TMP=""

# Release artifacts are signed keyless by the release workflow via GitHub
# Actions OIDC (Sigstore). The signing identity is therefore this repository's
# release workflow running on a release tag, not a long-lived key.
CERT_IDENTITY_REGEXP="^https://github\\.com/$REPO/\\.github/workflows/release\\.yml@refs/tags/"
CERT_OIDC_ISSUER="https://token.actions.githubusercontent.com"

cleanup() {
  [ -z "$JAR_TMP" ] || rm -f "$JAR_TMP"
  [ -z "$WRAPPER_TMP" ] || rm -f "$WRAPPER_TMP"
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

download() {
  case "$1" in
    https://*) ;;
    *)
      echo "Refusing to download from a non-HTTPS URL." >&2
      exit 1
      ;;
  esac

  if command_exists curl; then
    curl --proto '=https' --tlsv1.2 --retry 3 --retry-delay 1 -fsSL "$1" -o "$2"
  elif command_exists wget; then
    wget --https-only --secure-protocol=TLSv1_2 -q "$1" -O "$2"
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
[ "$(awk 'END { print NR }' docgen.jar.sha256)" -eq 1 ] || {
  echo "Invalid checksum file." >&2
  exit 1
}
IFS=' ' read -r EXPECTED CHECKSUM_NAME EXTRA < docgen.jar.sha256 || {
  echo "Invalid checksum file." >&2
  exit 1
}
[ "$CHECKSUM_NAME" = "docgen.jar" ] && [ -z "${EXTRA:-}" ] && [ "${#EXPECTED}" -eq 64 ] || {
  echo "Invalid checksum file." >&2
  exit 1
}
case "$EXPECTED" in
  *[!0-9A-Fa-f]*)
    echo "Invalid checksum file." >&2
    exit 1
    ;;
esac

if command_exists sha256sum; then
  ACTUAL="$(sha256sum docgen.jar | awk '{print $1}')"
elif command_exists shasum; then
  ACTUAL="$(shasum -a 256 docgen.jar | awk '{print $1}')"
else
  echo "sha256sum or shasum is required to verify the download." >&2
  exit 1
fi
[ "$EXPECTED" = "$ACTUAL" ] || { echo "Checksum verification failed." >&2; exit 1; }
echo "Checksum verification passed."

# Signature verification policy:
# - cosign installed: verify both artifacts against the release workflow
#   identity; any failure (including missing signature files) aborts unless
#   DOCGEN_SKIP_SIGNATURE=1 is set.
# - cosign not installed: continue with checksum only and print a warning,
#   unless DOCGEN_REQUIRE_SIGNATURE=1 is set, which aborts instead.
if [ "$SKIP_SIGNATURE" = "1" ]; then
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
elif [ "$REQUIRE_SIGNATURE" = "1" ]; then
  echo "DOCGEN_REQUIRE_SIGNATURE=1 is set but cosign is not installed." >&2
  echo "Install cosign (https://docs.sigstore.dev/cosign/system_config/installation/) and retry." >&2
  exit 1
else
  echo "WARNING: cosign not found; signature verification skipped (checksum only)." >&2
  echo "Install cosign to verify that artifacts were signed by the $REPO release workflow." >&2
fi

JAR_TMP="$(mktemp "$INSTALL_DIR/.docgen-jar.XXXXXX")"
WRAPPER_TMP="$(mktemp "$INSTALL_DIR/.docgen-wrapper.XXXXXX")"
cp "$TMP_DIR/docgen.jar" "$JAR_TMP"
cat > "$WRAPPER_TMP" <<'EOF'
#!/usr/bin/env sh
SCRIPT_DIR="$(CDPATH= cd "$(dirname "$0")" && pwd)"
exec java -jar "$SCRIPT_DIR/docgen.jar" "$@"
EOF
chmod 700 "$WRAPPER_TMP"

# Never follow an existing launcher/JAR symlink during replacement. Validate
# both targets before changing either one; real directories are never removed.
if [ -d "$JAR" ] && [ ! -L "$JAR" ]; then
  echo "Refusing to replace directory: $JAR" >&2
  exit 1
fi
if [ -d "$BIN" ] && [ ! -L "$BIN" ]; then
  echo "Refusing to replace directory: $BIN" >&2
  exit 1
fi
if [ -L "$JAR" ]; then
  rm -f "$JAR"
fi
if [ -L "$BIN" ]; then
  rm -f "$BIN"
fi
mv "$JAR_TMP" "$JAR"
JAR_TMP=""
mv "$WRAPPER_TMP" "$BIN"
WRAPPER_TMP=""

echo "DocGen installed at $BIN"
echo "For Groq: export GROQ_API_KEY=... and run docgen --allow-remote /path/to/repo"
echo "For non-interactive Groq runs, also pass --yes."
echo "For local Ollama: run docgen --provider ollama /path/to/repo"
