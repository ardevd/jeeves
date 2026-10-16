# Search-index interruption recovery

An on-demand corpus records executable identity on its first edit, without
requiring a full build. Headers also record `totalClasses` and
`completeCoverage`; a partial corpus stays partial after reopen. Legacy headers
without identity are rejected. In on-demand mode, Jeeves regenerates current
text for resolvable classes from that header, its pending queue and edit history
(including original identities and virtual-method family members). Rejected
gzip text is never adopted just by attaching the current APK hash. This is
selected recovery, not a full-project index. Classes not selected remain outside
text search. Direct decompilation still does not populate this corpus.

The raw search API retains its group list and adds `meta.searchCoverage`.
The search tool returns `{groups, coverage}` so the MCP bridge and model retain
coverage even when there are no groups. Coverage includes indexed/total class
counts, pending work, and whether the observed index revision changed during
search. Zero matches with partial coverage do not establish absence. Search
and a concurrent edit are not a transactional snapshot. The browser displays
the same partial-coverage warning. Missing or unreadable gzip entries detected
during hydration are queued for regeneration.

The search corpus is a derived cache, separate from the native database and edit
history. A rename or comment invalidates affected class text and records its
address in `search-index/pending.json`. Automatic mode starts a background worker.
With `WORKBENCH_INDEX_MODE=on-demand`, invalidation, warm adoption and hydration
never start that worker. The next text search refreshes pending classes and
persists their text/header; concurrent searches share the refresh and later
queries reuse it. Explicit full rebuilds still run when requested. Slow native
rendering can delay a requested search and other operations sharing the engine.
Cancelling background work does not disable search or its durable refresh.

A pending entry remains until both the replacement gzip and the matching header
have been written durably. The gzip is written to a temporary sibling, flushed,
then atomically replaced. Failed or cancelled decompilation, failed gzip writes,
and failed header commits retain recovery work. Reopening a valid corpus reloads
that work. `partialCoverage` remains true and `pendingReindex` counts pending
classes even after the worker has removed an item from its in-memory queue.

Each invalidation has an in-memory revision. If a new edit arrives while the old
text is being decompiled, the old result cannot publish over the new pending
entry. Worker completion and queue handoff share a monitor, avoiding a lost wakeup
when an edit arrives at the end of a pass.

Cancellation interrupts decompilation. It defers an interrupt while a filesystem
commit is already in progress, then restores it. This avoids an interrupt closing
the channel used to flush `pending.json`. Closing a target does not wait on the
index monitor while holding the engine monitor. A still-running decompiler can
finish its current burst; cancellation is not a hard thread kill.

Run the focused recovery fixture with an activated JEB installation available
for compilation:

```sh
JEB_HOME=/path/to/activated-jeb gradle -p backend searchIndexPersistenceIT
JEB_HOME=/path/to/activated-jeb gradle -p backend onDemandSearchIT
```

The fixture uses a controlled fake index host; it opens no JEB engine or model.
It checks interruption even when the host clears the interrupt flag, unsuccessful
decompilation, failed text replacement, failed header persistence, a newer edit
arriving during an older decompile, eight concurrent demand queries sharing one
render, no background work on demand-mode edits/reopen, and recovery after each case. Its two
filesystem-failure messages are deliberate injections. Native rename/save tests
separately exercise the adapter's normal close path.

An error persisting a new invalidation is reported in index status and the log;
storage must be repaired before relying on that cache's recovery queue. Rebuilding
the derived corpus remains possible from the saved database. These checks do not
prove survival of physical disk loss, and index completion is not a database-save
receipt. See [native save and history recovery](persistence.md).
