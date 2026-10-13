#!/bin/bash
# build-bridge.sh — the versioned distributable of the Jeeves MCP bridge for the course client kit.
#
#   deploy/bridge/build-bridge.sh        → deploy/bridge/out/jeeves-mcp-bridge-<version>-<rev12>/ and .tar.gz (+ .sha256)
#
# What it holds: mcp/workbench_mcp.py (standard library only, Python 3.9+), the jeeves-mcp launcher,
# README.md, VERSION.json (version, the Git revision and its date), CHECKSUMS.sha256 of every file.
# No JEB, no backend, no licence, no token, no checkout: the student's laptop runs the bridge and talks
# to the assigned HTTPS room through the class edge. Deterministic tarball (sorted, epoch mtimes, root
# owner). Refuses a tree with modified tracked files unless ALLOW_DIRTY=1 (then VERSION.json says dirty).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/../.." && pwd -P)"
cd "$REPO"
REV="$(git rev-parse HEAD)"
DIRTY="$(git status --porcelain --untracked-files=no)"
if [ -n "$DIRTY" ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then echo "[build-bridge] ERROR: tracked files are modified — a kit is built from a commit" >&2; echo "$DIRTY" >&2; exit 1; fi
VERSION="$(python3 -c 'import re,sys; print(re.search(r"BRIDGE_VERSION = \"([^\"]+)\"", open(sys.argv[1]).read()).group(1))' mcp/workbench_mcp.py)"
NAME="jeeves-mcp-bridge-${VERSION}-${REV:0:12}"
OUT="$HERE/out"; STAGE="$OUT/$NAME"
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp mcp/workbench_mcp.py "$STAGE/workbench_mcp.py"
cp "$HERE/jeeves-mcp" "$STAGE/jeeves-mcp"; chmod 755 "$STAGE/jeeves-mcp"
cp "$HERE/README.md" "$STAGE/README.md"
# the commit's date, not the clock: two builds of the same commit are the same bytes
COMMIT_AT="$(git log -1 --format=%cI HEAD)"
python3 - "$STAGE" "$VERSION" "$REV" "${DIRTY:+dirty}" "$COMMIT_AT" <<'PYEOF'
import hashlib, json, sys, gzip, io, tarfile, stat
from pathlib import Path
stage, version, rev, dirty, commit_at = Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
(stage / "VERSION.json").write_text(json.dumps({"schema": "jeeves-mcp-bridge-kit-v1", "version": version, "revision": rev, "tree_dirty": bool(dirty),
    "committed_at": commit_at, "python": ">=3.9, standard library only", "entrypoint": "jeeves-mcp",
    "server_contract": "the room's HTTP API through the class edge: POST /api/ops.tools, /api/tool.call {tool, args, client, callId, provenance}"}, indent=1) + "\n")
rows = []
for p in sorted(stage.rglob("*")):
    if p.is_file() and p.name != "CHECKSUMS.sha256":
        rows.append("%s  %s" % (hashlib.sha256(p.read_bytes()).hexdigest(), p.relative_to(stage).as_posix()))
(stage / "CHECKSUMS.sha256").write_text("\n".join(rows) + "\n")
out = stage.parent / (stage.name + ".tar.gz")
buf = io.BytesIO()
with tarfile.open(fileobj=buf, mode="w", format=tarfile.PAX_FORMAT) as tf:
    for p in sorted(stage.rglob("*")):
        ti = tf.gettarinfo(str(p), arcname=stage.name + "/" + p.relative_to(stage).as_posix())
        ti.uid = ti.gid = 0; ti.uname = ti.gname = "root"; ti.mtime = 0; ti.pax_headers = {}
        if p.is_dir(): ti.mode = 0o755; tf.addfile(ti)
        else:
            ti.mode = 0o755 if (p.stat().st_mode & stat.S_IXUSR) else 0o644
            with open(p, "rb") as f: tf.addfile(ti, f)
with open(out, "wb") as f, gzip.GzipFile(fileobj=f, mode="wb", mtime=0, filename="") as gz: gz.write(buf.getvalue())
sha = hashlib.sha256(out.read_bytes()).hexdigest()
(out.parent / (out.name + ".sha256")).write_text("%s  %s\n" % (sha, out.name))
print(json.dumps({"kit": str(stage), "tar": str(out), "sha256": sha, "bytes": out.stat().st_size, "files": len(rows), "version": version, "revision": rev, "dirty": bool(dirty)}))
PYEOF
