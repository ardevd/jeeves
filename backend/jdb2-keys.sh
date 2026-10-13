#!/bin/bash
# jdb2-keys.sh — record the internal JEB key of saved databases (<name>.jdb2.key beside each).
#
#   backend/jdb2-keys.sh                    every database in the store of backend/.workbench (or $WORKBENCH_HOME)
#   backend/jdb2-keys.sh <home>             the store of another workbench home
#   backend/jdb2-keys.sh <file.jdb2>…       these databases, wherever they are
#   --all                                   read again the databases that already have a record
#
# Why: two databases created under the same JEB project key cannot be open together — JEB keeps
# one project per key, and loading the second takes the first one's place (a save of the first
# then writes the second into its file). Databases made before 2026-10-04 were keyed wb-t1,
# wb-t2 … per server run, so different apks share keys. The workbench refuses a load that would
# clash, but it can only know a database's key once the database has been loaded: this loads
# each one alone, in a process of its own, and writes the key beside it.
#
# Nothing is saved and no database is modified. A database with a lock file beside it (a
# workbench has it open) is not touched — that workbench records its key. A workbench that
# opens a database at the very moment this reads it is told "the database is locked": open it
# again. Each database is loaded whole (a WhatsApp-size one: about a minute and several GB),
# one after another. JDB2_KEYS_XMX sizes the heap of each read (default 12g; the largest
# databases — 2 GB with thousands of decompiled classes — need more: a read that runs out says so).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ALL=0
HOME_DIR=""
FILES=()
for a in "$@"; do
  case "$a" in
    --all) ALL=1 ;;
    *.jdb2) [ -f "$a" ] || { echo "no such database: $a" >&2; exit 1; }
            FILES+=("$(cd "$(dirname "$a")" && pwd)/$(basename "$a")") ;;
    *) HOME_DIR="$a" ;;
  esac
done
if [ ${#FILES[@]} -eq 0 ]; then
  [ -n "$HOME_DIR" ] || HOME_DIR="${WORKBENCH_HOME:-$SCRIPT_DIR/.workbench}"
  [ -d "$HOME_DIR/targets" ] || { echo "no target store at $HOME_DIR/targets" >&2; exit 1; }
  HOME_DIR="$(cd "$HOME_DIR" && pwd)"   # absolute: the JVM below runs from the JEB directory
  while IFS= read -r f; do FILES+=("$f"); done < <(find "$HOME_DIR/targets" -mindepth 2 -maxdepth 2 -type f -name '*.jdb2' | sort)
  [ ${#FILES[@]} -gt 0 ] || { echo "no databases under $HOME_DIR/targets"; exit 0; }
fi
if [ -n "${JEB_HOME:-}" ] && [ -f "$JEB_HOME/bin/app/jeb.jar" ]; then JEB="$(cd "$JEB_HOME" && pwd)"; else JEB="$SCRIPT_DIR/engine"; fi
[ -f "$JEB/bin/app/jeb.jar" ] || { echo "no JEB install (JEB_HOME, or backend/engine)" >&2; exit 1; }
JAVA_BIN=""
for c in "${JAVA_HOME:-}" "$( [ "$(uname -s)" = Darwin ] && /usr/libexec/java_home -v 21 2>/dev/null || true )" \
         /usr/lib/jvm/temurin-21-jdk-* /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-21*; do
  [ -n "$c" ] && [ -x "$c/bin/java" ] && "$c/bin/java" -version 2>&1 | grep -q 'version "21[."]' && { JAVA_BIN="$c/bin/java"; break; }
done
[ -n "$JAVA_BIN" ] || { echo "no JDK 21 found — set JAVA_HOME" >&2; exit 1; }
OUT="$SCRIPT_DIR/build/jdb2keys"
mkdir -p "$OUT"
"$(dirname "$JAVA_BIN")/javac" -cp "$JEB/bin/app/*" -d "$OUT" "$SCRIPT_DIR/tools/Jdb2Keys.java"
label() { echo "$(basename "$(dirname "$1")")/$(basename "$1")"; }
echo "[jdb2-keys] ${#FILES[@]} database(s) · JEB at $JEB"
LAST="$OUT/last.$$.out"   # per run: two censuses at once do not read each other's answers
trap 'rm -f "$LAST"' EXIT
READ=0; KEPT=0; SKIPPED=0
for f in "${FILES[@]}"; do
  if [ -s "$f.key" ] && [ "$ALL" = 0 ]; then
    printf '%s\t%s\ton record\n' "$(head -n 1 "$f.key")" "$(label "$f")"; KEPT=$((KEPT + 1)); continue
  fi
  set +e
  (cd "$JEB" && "$JAVA_BIN" -Xmx"${JDB2_KEYS_XMX:-12g}" -XX:+UseG1GC -Djava.awt.headless=true -Djava.security.manager=allow \
      --add-opens java.base/java.lang=ALL-UNNAMED \
      -cp "$OUT:$JEB/bin/app/*" Jdb2Keys "$JEB" "$f" > "$LAST" 2> >(grep -v -E '^WARNING: (A terminally|System::setSecurityManager|Please consider)' >&2))
  rc=$?
  set -e
  line="$(grep -E $'^[^\t]+\t' "$LAST" | tail -n 1 || true)"
  [ -n "$line" ] || line="$(printf '?\tSKIPPED — the reader ended without an answer (exit %s)' "$rc")"
  printf '%s\t%s\t%s\n' "${line%%$'\t'*}" "$(label "$f")" "${line#*$'\t'}"
  if [ "${line%%$'\t'*}" = "?" ]; then SKIPPED=$((SKIPPED + 1)); else READ=$((READ + 1)); fi
done
echo
echo "$READ read, $KEPT already on record, $SKIPPED skipped"
# the databases that share a key, from the records
GROUPS_OUT="$(for f in "${FILES[@]}"; do if [ -s "$f.key" ]; then printf '%s\t%s\n' "$(head -n 1 "$f.key")" "$(label "$f")"; fi; done \
  | sort | awk -F'\t' '{ n[$1]++; l[$1] = (l[$1] == "" ? $2 : l[$1] ", " $2) } END { for (k in n) if (n[k] > 1) print "  " k ": " l[k] }' | sort)"
if [ -n "$GROUPS_OUT" ]; then
  echo "Databases that share a key — any two of a group cannot be open together (backend/jdb2-rekey.sh gives one a key of its own):"
  echo "$GROUPS_OUT"
else
  echo "No two of these databases share a key."
fi
