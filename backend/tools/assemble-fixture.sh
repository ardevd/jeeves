#!/bin/bash
# Assemble a smali fixture directory into one classes.dex with the smali/dexlib2 bundled in apktool
# (brew install apktool). Produces the committed test fixture; the tests themselves never need it.
#   backend/tools/assemble-fixture.sh <smali dir> <out.dex>
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${APKTOOL_JAR:-$(ls /opt/homebrew/Cellar/apktool/*/libexec/apktool_*.jar 2>/dev/null | tail -1)}"
[ -f "$JAR" ] || { echo "no apktool jar (brew install apktool, or APKTOOL_JAR=...)" >&2; exit 1; }
OUT_CLASSES="$HERE/../.qa/tools/assemble"
mkdir -p "$OUT_CLASSES"
javac -cp "$JAR" -d "$OUT_CLASSES" "$HERE/AssembleFixture.java"
java -cp "$JAR:$OUT_CLASSES" AssembleFixture "$1" "$2"
