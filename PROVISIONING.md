# Provisioning a workbench box

Written for whoever sets up a machine — a person, or the operator's assistant (Claude Code
or any LLM with a shell) handed this file. The workbench server never provisions itself:
no sudo, no installs, no keys from inside the JVM. The knowledge of what each OS needs
lives in `provision.sh`, not in anyone's head or prompt.

## The loop

```bash
./provision.sh --check            # table; exit 1 while a required row is missing
./provision.sh --check --json     # the same rows, machine-readable
./provision.sh --apply            # run every row marked auto (no sudo, no secrets), then re-check
```

1. Run the check. Read the rows: `missing` + `required` blocks the server; `warn` is an
   optional or a human step still open (a device, a login, a key).
2. Run `--apply`. It only does the unattended subset: `npm ci`, the web build, the frida
   venv and server binary.
3. Re-read the check. Every remaining fix is marked `human`: report it with its exact
   command and stop. Never attempt one on the operator's behalf — they need sudo, a
   license, a login, or a physical device.
4. Once green, start the server (`backend/workbench.sh`) and re-run the check: the
   provider and reversing-agent rows only resolve against a running server.

Inside a running server the `system.doctor` op (MCP tool `wb_system_doctor`) answers the
subset it can see — the same row shape, minus the build-time rows (gradle, node, web-deps,
server) and with one combined `frida` row — so an assistant connected over MCP can ask the
workbench what is missing without a shell. It is operator-only: a mission actor gets E_SCOPE.

## Human rows, and what to say

| Row | What the person does |
|---|---|
| `jdk21`, `gradle`, `node`, `python3`, `adb` | install with the printed package-manager line (sudo on Linux) |
| `jeb-engine` | set `JEB_HOME` to your own activated JEB install before starting the server; nothing is copied, and without one the server runs mock-only (`docs/local-jeb.md`) |
| `devices` | plug a device with USB debugging on, or start an emulator; `adb root` on emulator/userdebug builds; a real device needs root (Magisk/KernelSU/APatch). On Linux a `no permissions` state is the udev rule / `plugdev` group |
| `claude-cli` | `curl -fsSL https://claude.ai/install.sh \| bash`, then `claude auth login` — only needed for the Anthropic provider's "Claude Code login" connection (its "API key" connection needs no CLI) |
| `llm-providers` | PROVIDERS panel in the workbench, or MCP `wb_llm_configure` (`create:true` to add a provider) / `wb_llm_setkey`. Keys are write-only: the check reports which providers have one, never the value |
| `reversing-agent` | REVERSING AGENT settings window, or MCP `wb_task_configure reverse quick|deep --model provider:model` |

## Where macOS and Linux differ

| Prerequisite | macOS | Linux |
|---|---|---|
| JDK 21 | `brew install --cask temurin@21`; `/usr/libexec/java_home -v 21` finds it | `apt install openjdk-21-jdk` (or Adoptium's repo); `/usr/lib/jvm/*21*`; `JAVA_HOME` is honoured first on both |
| adb | `brew install --cask android-platform-tools` or Android Studio's `~/Library/Android/sdk` | `apt install adb android-sdk-platform-tools-common`, `plugdev` group, re-login; or `~/Android/Sdk` |
| Claude CLI | native installer puts it in `~/.local/bin`; brew in `/opt/homebrew/bin` | `~/.local/bin`; an npm-global or nvm install is found through `PATH` |
| frida-server download | needs `brew install xz` (stock macOS has none) | `xz-utils` is usually present |
| Python venv | built in | Debian/Ubuntu: `python3-venv` is a separate package |
| gradle | `brew install gradle` | distro packages are too old: sdkman |
| node | `brew install node` | distro node is often < 18: nodesource or nvm |
| Heap | 16 GB default | same; `WORKBENCH_XMX=8g` on a small box that only reopens cached `.jdb2` |
| Opening the UI | `open http://127.0.0.1:7800` | `xdg-open http://127.0.0.1:7800` |

`backend/workbench.sh` resolves the JDK itself (`JAVA_HOME`, then the OS, then the Temurin
paths) and refuses to start on anything but a 21. `WORKBENCH_ADB` overrides the adb binary
on both systems; `ADB` does the same for the frida sidecar.

## Rules for an assistant

- Never run anything that walks the filesystem root (`find /`, `grep -r /`, `du /`).
  Every path the check needs is known; `command -v` answers "is it on PATH".
- Never print or copy a key. `llm-keys.json` is read for its ids only.
- Never `pkill` the server: more than one workbench JVM may be alive. Find the `:7800`
  pid (`lsof -nP -iTCP:7800 -sTCP:LISTEN`) and kill that one.
- Re-run the check after every fix; the row is the receipt, not the command's exit code.
