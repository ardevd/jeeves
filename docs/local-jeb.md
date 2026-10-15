# Use your own JEB installation

Point Jeeves at your **already activated JEB installation** with `JEB_HOME`. It
uses that installation's JARs directly; nothing is extracted, copied or changed,
and the JEB GUI can be closed once activation is complete. `backend/workbench.sh`
and the gradle `runServer` task both start through `LocalJebLauncher`.

The launcher selects the start-up route the JEB edition permits:

- With `any-client`, it uses the standalone engine start-up
  (`JebCoreService.getInstance`, then an engines context of its own).
- With `official-client` and `core-api`, it starts JEB's public
  `HeadlessClientContext` and gives Jeeves that client's analysis context.

Jeeves then uses its engine adapter for analysis, decompilation, navigation,
editing and saved projects, through JEB's published API only. It does not change
JEB's license flags or add features the selected edition does not provide. It never
copies a key or offers a second activation dialog: activate JEB normally before
launching Jeeves. An edition that permits neither route cannot host Jeeves: the
launcher refuses to start; unset `JEB_HOME` to run on the mock engine.

PNF documents the standalone route at
https://www.pnfsoftware.com/jeb/manual/writing-front-ends/. The installation's API
documentation describes the official-client route under
`com.pnfsoftware.jeb.client.HeadlessClientContext`.

Install and activate JEB the vendor's way. `backend/jeb-check.sh <jeb dir>` then
boots that install's core once, in-process, the way the workbench embeds it, and
reports its version — the first check on a new machine, a new JEB version or a
new licence.

## Installed JVM options

The shell server launcher, `jeb-check.sh`, Gradle server and native tests read
`jvmopt.txt` from the selected installation, matching the vendor launcher's
whitespace-separated option format. The file is optional. Its contents are
arguments, never evaluated as shell code; wildcards are not expanded. As with
that format, shell quoting does not group an argument containing whitespace.
Jeeves supplies its explicit heap, temporary-directory and engine-selection
options afterwards. `WORKBENCH_JAVA_OPTS` and the isolated Java home retain their
existing precedence in the shell launcher. The installation is read in place;
no installation, activation or license file is changed.

## Native cache isolation

JEB keeps native caches under the Java `user.home` (notably `~/.jeb/z3`). Two
different JEB versions can overwrite that shared cache while another JVM maps it;
macOS may then kill the process for a changed code signature.

Set `WORKBENCH_JVM_USER_HOME` to an owned private directory to isolate this JVM's
home-relative state, including the Z3 cache. The launcher passes it as ONE quoted
Java argument; paths with spaces are supported. Relative values resolve against
the invoking directory, before the launcher changes directory. It creates the
directory if needed. OS `HOME` and the selected JEB installation remain unchanged.
Without the option, existing behavior is preserved. It takes precedence over a
`-Duser.home` in `WORKBENCH_JAVA_OPTS`.

This is broader than a Z3-only setting: Java-side `~/` expansion, SDK/tool discovery,
provider/config defaults and other home-relative lookups now see this directory.
Supply explicit JEB, workbench, SDK/tool and credential locations where necessary;
do not copy an entire real home or all secrets into it. Subprocesses still inherit
the normal OS HOME. It is not a sandbox or a licensing mechanism.

Example configuration (launches a second, isolated local server):

```sh
JEB_HOME="$HOME/jeb" \
WORKBENCH_HOME="$PWD/.qa/local-jeb/home" \
WORKBENCH_JVM_USER_HOME="$PWD/.qa/local-jeb/java-home" \
WORKBENCH_PORT=7916 \
backend/workbench.sh
```

Use a free port and a fresh workspace; do not compete with a workbench already on
:7800. Do not open the same writable JEB database in two instances. Stop only the
process you started. The option is for this developer launcher; gradle tasks do not
inherit it automatically.

A laptop must remain awake and connected while a local server analyzes or saves and
while local device sessions need it. Allow a few minutes for a cold target, measured
separately for each target and machine. Close or save targets and stop the server
before sleep.
