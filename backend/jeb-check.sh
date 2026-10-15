#!/bin/bash
# jeb-check.sh — does this JEB install start in-process, the way the workbench embeds it?
#
#   backend/jeb-check.sh <jeb directory>        e.g. backend/jeb-check.sh ~/jeb-5.47
#
# Compiles backend/tools/JebSmoke.java against the install's jars and boots its core once:
# the first check on a new machine, a new JEB version or a new licence, before the workbench
# is pointed at it (JEB_HOME). Exit 0 = the core started and reported its version. It reads
# nothing but the install and writes only under backend/build.
set -euo pipefail
JEB="${1:-${JEB_HOME:-}}"
[ -n "$JEB" ] || { echo "usage: backend/jeb-check.sh <jeb directory>  (or JEB_HOME set)" >&2; exit 2; }
JEB="$(cd "$JEB" && pwd)"
[ -f "$JEB/bin/app/jeb.jar" ] || { echo "not a JEB install: no bin/app/jeb.jar in $JEB (a fresh distribution needs the vendor's own installer step first)" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
JAVA_BIN=""
for c in "${JAVA_HOME:-}" "$( [ "$(uname -s)" = Darwin ] && /usr/libexec/java_home -v 21 2>/dev/null || true )" \
         /usr/lib/jvm/temurin-21-jdk-* /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-21*; do
  [ -n "$c" ] && [ -x "$c/bin/java" ] && "$c/bin/java" -version 2>&1 | grep -q 'version "21[."]' && { JAVA_BIN="$c/bin/java"; break; }
done
[ -n "$JAVA_BIN" ] || { echo "no JDK 21 found — set JAVA_HOME" >&2; exit 1; }
JAVAC="$(dirname "$JAVA_BIN")/javac"
OUT="$HERE/build/jebsmoke"
mkdir -p "$OUT"
"$JAVAC" -cp "$JEB/bin/app/*" -d "$OUT" "$HERE/tools/JebSmoke.java"
echo "[jeb-check] $JEB · $(cat "$JEB/bin/VERSION.TXT" 2>/dev/null || echo '?')"
source "$HERE/jeb-jvm-options.sh"
load_jeb_jvm_options "$JEB"
cd "$JEB"
exec "$JAVA_BIN" ${JEB_JVM_OPTIONS[@]+"${JEB_JVM_OPTIONS[@]}"} -Djava.security.manager=allow --add-opens java.base/java.lang=ALL-UNNAMED \
  -Djava.awt.headless=true -cp "$OUT:$JEB/bin/app/*" JebSmoke "$JEB"
