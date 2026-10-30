#!/usr/bin/env bash
# pull-frida.sh — build the python venv for the runs sidecar + fetch frida-server binaries.
# Usage: ./backend/runs/pull-frida.sh [arm64|x86_64|arm|x86|all ...]
# The frida-server binaries are gitignored like backend/engine. Sources, in order:
#   1. ~/projects/jeeves/frida/server/ (vendored cache, if present)
#   2. frida release download (github.com/frida/frida/releases)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# keep in step with requirements-runner.txt (client and device server must match)
VER="17.18.0"

map_abi_to_arch() {
  case "$1" in
    arm64-v8a|arm64|android-arm64) printf 'arm64' ;;
    x86_64|android-x86_64)         printf 'x86_64' ;;
    armeabi-v7a|armeabi|arm|android-arm) printf 'arm' ;;
    x86|android-x86)               printf 'x86' ;;
    *) return 1 ;;
  esac
}

find_adb() {
  local c home_dir="${HOME:-/root}"
  for c in "${WORKBENCH_ADB:-}" "$home_dir/Library/Android/sdk/platform-tools/adb" "$home_dir/Android/Sdk/platform-tools/adb" \
           "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" "$(command -v adb 2>/dev/null || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

# 1. Build the Python sidecar venv first so a binary download / xz issue never leaves the sidecar unbuilt.
if [ ! -x "$HERE/venv/bin/python" ] || ! "$HERE/venv/bin/python" -c "import frida, jsonschema; assert frida.__version__ == '$VER'" >/dev/null 2>&1; then
  echo "[pull-frida] building python venv (frida==$VER)…"
  rm -rf "$HERE/venv"
  python3 -m venv "$HERE/venv"
  "$HERE/venv/bin/pip" install -q -r "$HERE/requirements-runner.txt"
fi

# 2. Resolve target architectures (arguments, or auto-detect from connected ADB devices, defaulting to arm64).
ARCHS=()
add_arch() {
  local a="$1" existing
  for existing in "${ARCHS[@]+"${ARCHS[@]}"}"; do
    [ "$existing" = "$a" ] && return 0
  done
  ARCHS+=("$a")
}

if [ $# -gt 0 ]; then
  for arg in "$@"; do
    if [ "$arg" = "all" ]; then
      for a in arm64 x86_64 arm x86; do add_arch "$a"; done
    else
      mapped="$(map_abi_to_arch "$arg" || true)"
      if [ -z "$mapped" ]; then
        echo "[pull-frida] unknown architecture or ABI: $arg (expected arm64, x86_64, arm, x86, or all)" >&2
        exit 2
      fi
      add_arch "$mapped"
    fi
  done
else
  ADB_BIN="$(find_adb || true)"
  if [ -n "$ADB_BIN" ]; then
    while IFS= read -r serial; do
      [ -n "$serial" ] || continue
      abi="$("$ADB_BIN" -s "$serial" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n ' || true)"
      if [ -n "$abi" ]; then
        mapped="$(map_abi_to_arch "$abi" || true)"
        [ -n "$mapped" ] && add_arch "$mapped"
      fi
    done < <("$ADB_BIN" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
  fi
  if [ "${#ARCHS[@]}" -eq 0 ]; then
    add_arch "arm64"
  fi
fi

# 3. Ensure server directory exists and fetch each requested architecture atomically.
mkdir -p "$HERE/server"
SUMMARY=()
for ARCH in "${ARCHS[@]}"; do
  NAME="frida-server-$VER-android-$ARCH"
  BIN="$HERE/server/$NAME"
  if [ ! -s "$BIN" ]; then
    SRC="${HOME:-/root}/projects/jeeves/frida/server/$NAME"
    TMP="$BIN.tmp.$$"
    trap 'rm -f "$TMP"' EXIT
    if [ -s "$SRC" ]; then
      cp "$SRC" "$TMP"
      chmod 755 "$TMP"
      mv "$TMP" "$BIN"
      echo "[pull-frida] copied $NAME from local cache"
    else
      if ! command -v xz >/dev/null 2>&1; then
        echo "[pull-frida] ERROR: xz is required to unpack $NAME.xz" >&2
        exit 1
      fi
      echo "[pull-frida] downloading frida-server $VER (android-$ARCH, xz)…"
      curl -fSL "https://github.com/frida/frida/releases/download/$VER/$NAME.xz" \
        | xz -d > "$TMP"
      chmod 755 "$TMP"
      mv "$TMP" "$BIN"
    fi
    trap - EXIT
  fi
  SUMMARY+=("android-$ARCH ($(du -h "$BIN" | cut -f1))")
done

PY_VER="$("$HERE/venv/bin/python" -c 'import frida; print(frida.__version__)')"
SUMMARY_STR="$(printf '%s, ' "${SUMMARY[@]}")"
echo "[pull-frida] OK — frida $PY_VER · ${SUMMARY_STR%, }"
