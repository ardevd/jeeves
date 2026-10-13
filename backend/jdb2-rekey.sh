#!/bin/bash
# jdb2-rekey.sh — give a saved database a JEB key of its own, by having its apk analysed again.
#
#   backend/jdb2-rekey.sh <store entry dir | file.jdb2>
#
# Why: two databases created under the same internal JEB project key cannot be open together
# (JEB keeps one project per key; see backend/jdb2-keys.sh for which ones share a key). JEB has
# no way to change the key of an existing database, so the one way out is a new database: this
# sets the old one aside (<name>.jdb2.rekeyed-<time>, kept with its key record — nothing is
# deleted) and leaves a marker (<name>.jdb2.replay). The next `target open` of that apk
# analyses it again (minutes for a WhatsApp-size apk) under a key no other database has,
# re-applies the edit history — the renames (classes, methods, fields, locals) and comments
# made through the workbench, in order — and only then writes the new database. An open that
# is interrupted writes nothing: the next one starts over.
#
# An entry that cannot be re-applied is counted and logged (the target's message and
# `replayFailed` say how many; logs/workbench.log says which): typically an edit made on a
# symbol whose own rename was later undone out of order — its recorded address no longer exists.
# Not carried over: the decompilations cached in the old database (redone on demand), anything
# done to it outside the workbench (in the JEB GUI), and edits made while the .jdb2 itself was
# the opened target (they sit in <name>.jdb2.workbench/history.json — refused when there are any;
# FORCE=1 goes ahead without them). A database with no apk beside it cannot be analysed again.
# Refused while a workbench has the database open (a lock file beside it): close the target first.
# Undo, before the apk is opened again: move the .rekeyed file (and its .key) back and delete
# the marker. After it was opened: UNDO RE-KEY on the target (the op target.rekeyUndo).
# The workbench does all of this by itself when YOU open a clashing apk from its UI.
set -euo pipefail
[ $# -eq 1 ] || { sed -n '2,5p' "$0" >&2; exit 2; }
if [ -d "$1" ]; then
  DIR="$(cd "$1" && pwd)"
  N=$(find "$DIR" -maxdepth 1 -type f -name '*.jdb2' | wc -l | tr -d ' ')
  [ "$N" = 1 ] || { echo "$DIR holds $N databases — name the .jdb2 itself" >&2; exit 1; }
  JDB2="$(find "$DIR" -maxdepth 1 -type f -name '*.jdb2')"
else
  [ -f "$1" ] || { echo "no such database: $1" >&2; exit 1; }
  JDB2="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
fi
case "$JDB2" in *.jdb2) ;; *) echo "not a .jdb2: $JDB2" >&2; exit 1 ;; esac
BASE="${JDB2%.jdb2}"
ART=""
for ext in apk dex; do [ -f "$BASE.$ext" ] && { ART="$BASE.$ext"; break; }; done
if [ -z "$ART" ]; then   # any other artifact type: the one file with this base name that is not the database's own
  for c in "$BASE".*; do
    [ -f "$c" ] || continue
    case "$c" in *.jdb2|*.jdb2.*|*.part|*.prov.json|*.json) continue ;; esac
    ART="$c"; break
  done
fi
[ -n "$ART" ] || { echo "no apk beside $(basename "$JDB2") (a file named $(basename "$BASE").apk, .dex …): without it the database cannot be analysed again — refused" >&2; exit 1; }
[ ! -e "$JDB2.lock" ] || { echo "$(basename "$JDB2") is open in a workbench ($(basename "$JDB2").lock) — close that target first" >&2; exit 1; }
OLDKEY="$( [ -s "$JDB2.key" ] && head -n 1 "$JDB2.key" || echo "not on record" )"
count_edits() { python3 - "$1" <<'PY' 2>/dev/null || echo "?"
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except FileNotFoundError:
    print(0); sys.exit()
ents = d if isinstance(d, list) else d.get("entries") or []
print(sum(1 for e in ents if not e.get("undone") and e.get("op") in ("rename", "comment")))
PY
}
EDITS="$(count_edits "$ART.workbench/history.json")"
DIRECT="$(count_edits "$JDB2.workbench/history.json")"
if [ "$DIRECT" != 0 ] && [ "${FORCE:-0}" != 1 ]; then
  echo "$(basename "$JDB2").workbench/history.json holds $DIRECT edit(s) made while the .jdb2 itself was the opened target: they would NOT be re-applied (only $(basename "$ART").workbench/history.json is) — refused. FORCE=1 goes ahead without them; the old database is kept either way" >&2
  exit 1
fi
ASIDE="$JDB2.rekeyed-$(date -u +%Y%m%dT%H%M%SZ)"
jq_str() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
KEYJ=""; [ -s "$JDB2.key" ] && KEYJ="$(head -n 1 "$JDB2.key")"
printf '{"at":"%s","key":"%s","aside":"%s","by":"jdb2-rekey.sh"}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(jq_str "$KEYJ")" "$(jq_str "$(basename "$ASIDE")")" > "$JDB2.replay"
mv "$JDB2" "$ASIDE"
if [ -f "$JDB2.key" ]; then mv "$JDB2.key" "$ASIDE.key"; fi   # the record goes with the database it describes
echo "set aside: $(basename "$ASIDE") (old key: $OLDKEY)"
echo "next: open $(basename "$ART") in the workbench — it is analysed again under a key of its own, then $EDITS edit(s) from its history are re-applied and the new database is written."
echo "undo:  mv '$ASIDE' '$JDB2' && rm '$JDB2.replay'; [ -f '$ASIDE.key' ] && mv '$ASIDE.key' '$JDB2.key'   (before the apk is opened again; afterwards: the target's UNDO RE-KEY, or target.rekeyUndo)"
