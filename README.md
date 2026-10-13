# Jeeves

A reverse-engineering workbench for Android: JEB-powered decompilation and
cross-references, a persisted search index over decompiled text, live Frida runs
on a device, project-level flow maps, and supervised LLM agents. One op registry
drives the web UI, the MCP bridge and scripts alike.

Jeeves is a front-end to [JEB](https://www.pnfsoftware.com/jeb/). You bring your
own licensed, activated JEB install; nothing of JEB is in this repository and
nothing is copied from your install. Without JEB the workbench runs on a mock
engine with fixture targets, which is enough to work on the UI, the agents and
the MCP surface.

## Requirements

| Need | For |
|---|---|
| JDK 21 | everything |
| Gradle 8 or later | build |
| Node 18 or later | frontend build (TypeScript) |
| Python 3.10 or later | the MCP bridge (standard library only) and the Frida sidecar |
| JEB | real decompilation: an edition that permits third-party clients (`any-client`), or one that permits core-API use through JEB's official headless client. See `docs/local-jeb.md`. |
| adb and a rooted device or emulator | the Runs and Device views (optional) |

Fresh analysis of a large APK (100,000+ classes) wants a 16 GB heap, the
default; `WORKBENCH_XMX` tunes it. Later opens come from the saved `.jdb2`.

## Quickstart

```sh
./provision.sh --check                          # what this machine is missing, with the fix lines
JEB_HOME=/path/to/jeb backend/workbench.sh      # build backend + frontend, serve http://127.0.0.1:7800
```

Omit `JEB_HOME` to run on the mock engine. `backend/jeb-check.sh <jeb dir>` says
whether an install boots in-process before you point the workbench at it. Open a
target with the **+** chip in the target strip: an APK, a DEX or a `.jdb2`.

## Assistants

`mcp/workbench_mcp.py` exposes the workbench's tool facade to an assistant over
stdio (Claude Code, Kimi Code, OpenCode, Codex, …); `MANUAL.md` has the client
configuration.

## Documentation

- `MANUAL.md` — the user manual: concepts, configuration, every view, agents, MCP, troubleshooting
- `PROVISIONING.md` — setting up a machine, written for a person or their assistant
- `docs/local-jeb.md` — using your own JEB install: the two start-up routes and the edition each needs
- `design/*_SPEC.md` — the specifications: workbench, views, graphs, agents, device, MCP
- `AGENTS.md` and `CONTRIBUTING.md` — build, test and contribution notes

## Tests

`gradle -p backend classes` builds without JEB; `gradle -p backend parityTest`
checks the op registry against the MCP spec on the mock engine (its current
state is noted in `AGENTS.md`). The integration suites
(`gradle -p backend m7IT`, `keysIT`, `paramsIT`, …) need a JEB install and APK
fixtures that are not in the repository (`WORKBENCH_QUALIFY_FIXTURE=<apk>`).

## Licence

Apache License 2.0 (`LICENSE`). JEB is a product of PNF Software and is licensed
separately; this project contains none of it.
