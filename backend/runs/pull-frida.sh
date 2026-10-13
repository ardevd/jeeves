#!/usr/bin/env bash
# pull-frida.sh — fetch the frida-server binary + build the python venv for the runs sidecar.
# The frida-server binary (53MB) is gitignored like backend/engine. Sources, in order:
#   1. ~/projects/jeeves/frida/server/ (the old Jeeves vendored copy)
#   2. frida release download (github.com/frida/frida/releases)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VER="17.18.0"   # keep in step with requirements-runner.txt (client and device server must match)
BIN="$HERE/server/frida-server-$VER-android-arm64"

if [ ! -f "$BIN" ]; then
  SRC="$HOME/projects/jeeves/frida/server/frida-server-$VER-android-arm64"
  if [ -f "$SRC" ]; then
    cp "$SRC" "$BIN"
    echo "[pull-frida] copied frida-server from old Jeeves"
  else
    echo "[pull-frida] downloading frida-server $VER (android-arm64, xz)…"
    curl -L "https://github.com/frida/frida/releases/download/$VER/frida-server-$VER-android-arm64.xz" \
      | xz -d > "$BIN"
    chmod 755 "$BIN"
  fi
fi

if [ ! -x "$HERE/venv/bin/python" ]; then
  python3 -m venv "$HERE/venv"
  "$HERE/venv/bin/pip" install -q -r "$HERE/requirements-runner.txt"
fi
echo "[pull-frida] OK — $("$HERE/venv/bin/python" -c 'import frida; print(frida.__version__)') · $(du -h "$BIN" | cut -f1)"
