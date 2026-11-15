#!/usr/bin/env sh
# Installs a DocGen app image (built by build-app-image.sh) for the current user, without root:
#   ${DOCGEN_INSTALL_DIR:-~/.local/opt/DocGen}            the application (replaced as a whole)
#   ~/.local/share/applications/docgen.desktop             menu entry
#   ~/.local/share/icons/hicolor/512x512/apps/docgen.png   icon
#
# Usage: install-user.sh <path/to/DocGen>     install or update
#        install-user.sh --uninstall           remove the application and its menu entry
#
# Projects, logs and settings (~/.local/share/docgen, ~/.local/state/docgen) are never touched.
set -eu

DEST="${DOCGEN_INSTALL_DIR:-$HOME/.local/opt/DocGen}"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
case "$DATA_HOME" in /*) ;; *) DATA_HOME="$HOME/.local/share" ;; esac
ENTRY="$DATA_HOME/applications/docgen.desktop"
ICON="$DATA_HOME/icons/hicolor/512x512/apps/docgen.png"

case "$DEST" in
  /*) ;;
  *) echo "install-user: DOCGEN_INSTALL_DIR must be an absolute path." >&2; exit 1 ;;
esac

is_app_image() {
  [ -d "$1" ] && [ ! -L "$1" ] && [ -x "$1/bin/DocGen" ] \
    && [ -f "$1/lib/app/docgen-launcher.jar" ] && [ -f "$1/lib/backend/docgen-api.jar" ]
}

if [ "${1:-}" = "--uninstall" ]; then
  if [ -e "$DEST" ] || [ -L "$DEST" ]; then
    if ! is_app_image "$DEST"; then
      echo "install-user: $DEST does not look like a DocGen installation; refusing to remove it." >&2
      exit 1
    fi
    rm -rf -- "$DEST"
  fi
  rm -f -- "$ENTRY" "$ICON"
  echo "DocGen removed. Your projects and logs were kept."
  exit 0
fi

SOURCE="${1:-}"
if [ -z "$SOURCE" ] || ! is_app_image "$SOURCE"; then
  echo "Usage: install-user.sh <path/to/DocGen app image> | --uninstall" >&2
  exit 2
fi
SOURCE="$(CDPATH= cd -- "$SOURCE" && pwd)"

# Never follow or replace something that is not a previous DocGen installation.
if [ -L "$DEST" ]; then
  echo "install-user: $DEST is a symbolic link; refusing to replace it." >&2
  exit 1
fi
if [ -e "$DEST" ] && ! is_app_image "$DEST"; then
  echo "install-user: $DEST exists and is not a DocGen installation; refusing to replace it." >&2
  exit 1
fi

PARENT="$(dirname -- "$DEST")"
mkdir -p -- "$PARENT"
STAGING="$(mktemp -d "$PARENT/.docgen-install.XXXXXX")"
trap 'rm -rf -- "$STAGING"' EXIT INT TERM
cp -R -- "$SOURCE/." "$STAGING/app"
chmod -R go-w -- "$STAGING/app"

if [ -e "$DEST" ]; then
  mv -- "$DEST" "$STAGING/previous"
fi
mv -- "$STAGING/app" "$DEST"

mkdir -p -- "$(dirname -- "$ENTRY")" "$(dirname -- "$ICON")"
cp -- "$DEST/lib/DocGen.png" "$STAGING/docgen.png"
mv -- "$STAGING/docgen.png" "$ICON"
# Desktop entry values: the Exec path is quoted per the freedesktop specification.
EXEC_ESCAPED="$(printf '%s' "$DEST/bin/DocGen" | sed 's/[\\"`$]/\\\\&/g')"
sed -e "s|@EXEC@|\"$EXEC_ESCAPED\"|" -e "s|@ICON@|$ICON|" "$DEST/lib/docgen.desktop" > "$STAGING/docgen.desktop"
chmod 0644 "$STAGING/docgen.desktop"
mv -- "$STAGING/docgen.desktop" "$ENTRY"
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$(dirname -- "$ENTRY")" >/dev/null 2>&1 || true
fi

echo "DocGen installed in $DEST"
echo "Open it from the applications menu, or run: \"$DEST/bin/DocGen\""
