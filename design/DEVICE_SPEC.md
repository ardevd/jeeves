# Device View Specification (v0.1 · draft)

Status: spec discussion · 2026-07-24
Scope: live devices as target *sources* — browse a rooted phone's
filesystem/processes/services from the workbench, maintain a running
LLM-annotated catalog of what's on board, and pull artifacts into the
project as targets on demand.

Motivation: today this is manual (`adb exec-out` one-liners in research
READMEs — see fcm_map). The device is where targets *come from*; the
workbench should treat it as a first-class source, not an afterthought.

---

## 1. Concepts

| Concept | Definition |
|---|---|
| **Device** | A phone reachable via adb (USB or tunnel), rooted. Devices register in the workspace and appear in the status ribbon (DEV count) and the Device view. |
| **Catalog** | A per-device, project-cached inventory: packages (user + system), running processes, published binder services, shared libs, partitions, and notable files. Lives in the project sidecar (`<project>/devices/<serial>.catalog.json`), refreshed on demand or on connect, diffed between refreshes. |
| **Catalog entry** | One item: kind (`app|system-app|lib|service|process|partition|file`), identity (package name / path / pid), version/uid/size, plus annotations. |
| **Annotation** | Analyst or agent note on an entry: relevance verdict, tags, rationale. **Agent annotations are proposals** — same review grammar as everything else. |
| **Pull** | Copying an entry (APK + splits, .so, partition image, file) from device to the project, creating a **target** bound to an engine. |

The device's *runtime* side (frida attach, breakpoints, hits) is the Runs
view — unchanged. The Device view is the *inventory/sourcing* side.

## 2. UI — the Device view (`⌃6` / rail phone icon)

Center-surface view, same swap mechanics as Strings/Runs/Search/Agents.

- **Device bar**: selector for connected devices (serial, model, Android
  version, root status dot, frida status), refresh button, "catalog age"
  readout (`catalog built 12m ago · 312 entries · +4 since last`).
- **Catalog browser** (center): filter input + kind chips
  (app / system-app / lib / service / process / partition / file —
  user-extensible, same type-registry principle as maps) + "annotated
  only" / "changed since last refresh" toggles. Table rows: kind icon,
  name (mono), version/uid — for services the binder interface descriptor,
  dimmed — size, annotation badge (🤖 proposed / ✎ human /
  — none), pulled state (→ target name if pulled).
- **Row expansion** (process rows): the kind icon on a `process` row is a
  toggle — clicking it rotates the caret and opens an inline panel below the
  row, spanning the table, with that process's live `/proc` detail
  (`device.proc`): a summary line (state · ppid · threads · RSS/VmSize ·
  oom_adj · uptime · cmdline), the per-thread COMM/STATE table, and the
  memory block in MB. Values are fetched per expand and cached until the next
  catalog refresh; several rows may be open at once. The toggle is separate
  from the row's own click, which still selects the entry into the rail, and
  only `process` rows carry it — no other kind has per-row detail to open.
  Expansions survive a filter change; a tab switch resets them (each tab is a
  different row set).
- **Rail (entry detail)**: for the selected entry — full identity block,
  for apps: activities/services/receivers count, signer, first-installed;
  for libs: soname, paths, dependents; for services: `iface`, host process
  and the `pkg`/`apkPath` behind it (no PULL — a service is a registry
  entry, not an artifact); annotations with accept/reject for
  agent proposals; **PULL button** (with split-aware size estimate,
  `pull → target: gms.apk`), and for running processes an **ATTACH** button
  (hands off to Runs).
- Pull flow: pull starts → progress in the status ribbon busy slot → on
  completion a new target chip appears in the strip (engine auto-detected
  from magic bytes; APK splits auto-merge per old Jeeves behavior).

## 3. The running catalog & LLM assistance

- **Refresh lifecycle**: full scan on first connect (packages via `pm`,
  processes via `ps`, services via `service list` — the ServiceManager
  binder registry — joined to their host process by `dumpsys --pid` and to
  a package/apk by `dumpsys activity services`, libs via filesystem walk of
  `/system/lib{,64}`, `/apex`, `/vendor`, partitions via `ls /dev/block`),
  then incremental refreshes (diff-based: installed/removed/updated).
  Service→package resolution never guesses: a service hosted in
  `system_server` maps to the framework (`/system/framework/framework.jar`),
  one hosted in an app process maps to that package's apk, and a service
  whose host is a native daemon (or a process shared by several packages)
  keeps its host and leaves `pkg`/`apkPath` absent.
  Catalog is per-project cache; multiple projects may catalog the same
  device independently.
- **LLM-assisted triage** (the "catalog mission"): an agent walks the
  catalog and annotates entries with relevance to the investigation —
  e.g. scanning for push-related packages, crypto libs, IPC surfaces,
  or outliers (non-stock apks, su remnants). Annotations land as
  **proposals** in the Agents queue (`kind: catalog`), accepted/rejected
  like any other. A "Annotate with agent" button on the device bar spawns
  the mission; annotation results re-sort the browser (annotated-first).
- The catalog is queryable by agents directly (`device.catalog` op) — an
  agent can answer "what on this phone talks to mtalk?" by scanning the
  catalog, then `strings`/`search` on pulled targets.

## 4. MCP ops

| Op | M | Purpose |
|---|---|---|
| `device.list` | | connected devices (serial, model, android, root, frida) |
| `device.catalog <serial> [--refresh] [--kind] [--filter]` | | catalog entries, paged; refresh triggers re-scan (diff returned). The scans are CAPPED (`PKG_CAP` 2000, `LIB_CAP` 300/dir, `PROC_CAP` 200, `SVC_CAP` 500, `PART_CAP` 200), so `entries` is what was kept, never the device's inventory: `kindTotals {kind: n}` counts each kind on the device BEFORE its cap and `cappedKinds []` names the kinds that hit one, so a UI count can render `606+` instead of passing a cap off as a total. A refresh also returns `removed []` — the entries that vanished since the last scan, each `{removed: true, changed: "removed"}` — scoped to that one refresh and capped at 500 rows (`diff.removed` stays the true count). Tombstones ride OUTSIDE `entries`: a gone package must not answer `device.annotate`/`device.pull`, only be listable |
| `device.browse <serial> <path>` | | raw filesystem listing (root shell) for paths outside the catalog |
| `device.pull <serial> <entry> [--to <project>]` | ✓ | pull artifact into the **target store** (`targets/<key>/`, content-keyed) → opens it as a target; the staging copy in `pulled/` is reaped once adopted, and its provenance is carried into the store. Deduped by content: re-pulling the same bytes reopens the existing entry (`skippedPull`) instead of transferring again — live → navigate, closed → reopen from the store. A non-openable artifact (no engine, e.g. ELF) stays in `pulled/` with an honest `E_UNSUPPORTED`. Returns a progress ticket (`op.cancel` works) |
| `device.annotate <serial> <entry> <note>` | ✓ | human annotation (agent annotations are proposals via the mission) |
| `device.spawn-mission <serial> <goal>` | ✓ | "catalog mission" — LLM triage of the catalog (Agents view tracks it) |
| `device.services <serial>` | | the device's binder service registry — the catalog's `service` entries (name, `iface`, host process, `pkg`/`apkPath` where resolvable) |
| `device.proc <serial> <pid>` | | live `/proc/<pid>` detail for one running process, read in a single device-side script (one adb round trip — N calls would sample the process at N instants): `state`/`stateText`, `ppid`, `uid`/`gid`, `threads` and a per-thread `threadList` (`tid`, `comm`, `state`; main thread first, capped at 64 with `threadsShown`/`threadsTruncated`), `mem` (VmPeak/VmSize/VmRSS/RssAnon/RssFile/VmSwap, kB as /proc reports them), `utime`/`stime`/`cpuSeconds` (clock ticks converted via the device's `CLK_TCK`), `processor`, `startTime`/`uptime`/`deviceUptime` (seconds), `ctxtVoluntary`/`ctxtNonvoluntary`, `fds`, `oomScore`/`oomScoreAdj`, `exe`/`cwd` (empty where the kernel denies the readlink) and `cmdline`. Pids race the catalog: a process that exited between the scan and the call answers `E_NOTFOUND` ("process exited"), never a shell error |
| `device.components <serial> <pkg>` | | the components one package DECLARES, from `dumpsys package --all-components <pkg>`: `{kind, class}` per component (class fully qualified — the dump's relative `.Foo` forms are expanded), plus `permission` and intent `actions` for the filtered subset the resolver tables cover and `authorities` for providers. Package identity rides along (`versionName`, `versionCode`, `codePath`, `apkPath`). `found`/`partial`/`truncated` say what the parse got: an unknown package is an empty result, never an error. dumpsys prints no per-component process name, so components carry none |

Pulled targets are indistinguishable from file-opened targets afterward —
the catalog records provenance (`pulledFrom: serial:path:sha256`) so a
later `device.pull --refresh` can detect on-device updates.

## 5. Safety & integrity

- Root shell access is powerful: all device ops are read-only except
  `device.pull` (device→host copy) — the workbench never writes to the
  device filesystem (frida instrumentation goes through the Runs channel,
  which has its own consent model).
- Catalog records sha256 of pulled artifacts; re-pull of a changed binary
  warns ("on-device version differs from target").
- Tunnel devices (adb over SSH/Tailscale, the fcm_map setup) are
  first-class — a device is just an adb endpoint.

## 6. Open questions

1. Partition images (init_boot etc.) as targets: worth a dedicated
   `image` target type with a bootloader-aware engine, or plain hex view?
2. Catalog scope creep: full filesystem walk is expensive on 200k+ files —
   default to curated roots (packages, libs, /apex, /vendor, /data/app)
   with opt-in deep scan?
3. Multi-device missions: should a catalog mission compare two devices
   (stock vs. tampered) — diff catalogs as a first-class output?
