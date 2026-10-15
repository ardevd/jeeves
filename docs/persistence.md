# Saving edits and recovering interrupted writes

Renames and comments change the live JEB project immediately. Their history is a separate recovery record. A completed database save contains the native JEB state; the history alone is not a replacement for a verified database and backup.

## What a successful save means

- A target's edit transaction excludes a save on that same target. Rename ownership checks, the actual change, and its history record use the same gate. Different targets have separate gates.
- History is written to a temporary sibling, its contents are forced to storage, the file is atomically replaced, and the containing directory is forced. Failure returns an error; an already-applied edit is explicitly marked `applied:true`, `durable:false` and remains in memory for recovery. Keep the target open, correct storage, then retry saving.
- Before JEB writes its database, Jeeves copies the previous complete database to `.save-checkpoints/`, preserves its timestamp, forces it to storage, and verifies its SHA-256. It writes a durable `.jdb2.save-in-progress.json` receipt before allowing the native writer to touch the current file.
- JEB saves to its original path. A temporary save-as filename was tested and rejected because it moved the SDK's lock away from the final database.
- On a refused or throwing native save, Jeeves preserves the failed bytes as evidence and restores the verified prior database, including its modification time. Its accepted history can then replay edits newer than that checkpoint.
- On successful native save, Jeeves forces the new file and directory, removes the in-progress receipt, and only then releases unsaved rename claims. The save response includes `historyRevision` and `durableHistory:true`.

Checkpoints have unique names and are retained. This consumes approximately one previous database's size per save; monitor free disk space and copy verified checkpoints to independent storage. No automatic count cap or pruning policy is imposed.

## Polling an asynchronous save

Call the `target` MCP tool with `action:"save"` and the explicit target. Its response supplies `startedAt` and a callable `pollTool` example for `action:"save_status"`.

Completion requires `saving:false` and `last.at >= startedAt`. Success additionally requires `last.ok:true`. Missing or older `last` is not completion. `last.ok:false` includes the failure, including a failure to flush history before JEB was called. If synchronous saves overlap, `saving` remains true until every accepted save has finished; outcome publication follows native-write order. The HTTP equivalents are `target.save` with `async:true` and `target.saveStatus`.

A close takes the same target gate as edits and flushes the recovery history before detaching the target. A failed history flush leaves the target open. A native close-save failure reaches a synchronous caller with `closed:true, saved:false`; an asynchronous close logs it. A target disappearing from the list is therefore not a save-success receipt. Require an explicit fresh successful save before an unattended round close.

## An interrupted process

An in-progress receipt prevents the database from being loaded or overwritten as though the interrupted write had completed. First identify and stop the old owner, preserving its logs and all checkpoint/history files. Never remove a native `.lock` while a process still owns the database.

After checking that no owner remains and dealing with its stale lock, use the explicit recovery helper:

```sh
java -cp backend/build/classes/java/main dev.workbench.engine.CheckpointedFileSave recover /absolute/path/Target.jdb2
```

The helper refuses a remaining native lock, checks the receipt's destination and checkpoint digest, retains the interrupted bytes, and restores the previous complete file and timestamp. Reopen the original artifact with its history sidecar to replay accepted unsaved edits. Inspect `recoveredEdits`. Any failed replay entry makes the open fail and prevents a partially recovered project from being saved, including during close. Preserve the checkpoint and journal, inspect the reported entry, correct its cause, and reopen. Once recovery succeeds, save, close, and reopen again to check the expected names and comments.

## Undo, redo and discard after a save

A history entry remains the item shown in the undo list. New entries carry `journalVersion:2`; their `reversals` array records each undo or redo with its own timestamp, revision, actor and referenced entry. The reversal and the entry's displayed `undone` flag are written in the same atomic history replacement. A failed write reports an applied but not durable change and keeps the reversal in memory for retry.

Recovery reads original edits and their reversal events in execution order. A saved rename followed by an unsaved undo replays the inverse; a saved undo followed by an unsaved redo replays the forward change. Class/member identities and local-method identities use original symbols, so changing the containing class does not strand a later comment or local rename. Parameter reversals restore previous analyst names or clear an assignment back to its engine default. The database timestamp retains filesystem precision instead of being truncated to milliseconds.

Discard marks only changes newer than the database, including pending reversals of older entries. It restores the undo list's saved state and prevents those discarded changes from returning on reopen. Unsaved ownership is rebuilt from effective current names and the latest reversal time; a redo of an older entry cannot install a stale later-created name in the cache.

Legacy entries remain readable. A new reversal records their starting state before changing it. Historical reversals never recorded by an older build cannot be reconstructed retroactively. Complete a verified native save before downgrading to a build that does not understand reversal events.

## Round checkpoints and independent verification

Stop issuing new edits while making a round checkpoint. Require a fresh successful save, preserve the database, `.key`, artifact `.workbench` sidecar and project state, and record hashes and modification times. Preserve the original database timestamp when restoring a copied checkpoint: recovery uses it to distinguish edits already in that database from later journal events. Copy the checkpoint to independent storage; a second directory on the same disk does not protect against disk loss.

Verify the saved database without supplying recovery history as well as with its full sidecars. Otherwise history replay could hide a native save that did not contain the expected changes. Do not open the same writable database in two processes or two copies with one internal JEB key in the same context. Target IDs can change on reopen; resolve the new ID before launching the next agents.

## Reproducing the checks

The local filesystem and mock tests need Java 21 and Gradle, with no device, model or paid API:

```sh
gradle -p backend persistenceTest historyJournalTest workspacePersistenceTest concurrentRenameTest
```

The native tests use the small authored DEX files committed under `backend/src/test/fixtures`, create separate test homes and databases, and use only the published SDK:

```sh
JEB_HOME=/path/to/activated/jeb gradle -p backend nativeSaveIT nativeRecoveryIT nativeHistoryRecoveryIT concurrentRenameIT renameFamilyIT
```

`nativeRecoveryIT` and `nativeHistoryRecoveryIT` intentionally halt their own child JVMs. Their drivers wait for the exact child to exit and remove only that private fixture's stale native lock before starting the next process. They never remove production locks or use a production database.

## Tested scope and remaining limits

- `PersistenceTest`: 19 actual-filesystem checks covering refused/throwing writers, rollback bytes and timestamps, a child halted mid-write, lock refusal, explicit recovery and sidecar errors.
- `HistoryJournalTest`: 17 checks covering chronological edits/reversals, saved undo/redo, legacy entries, discard, parameter inverses, stable identities and timestamp precision.
- `WorkspacePersistenceTest`: 21 checks covering an actual blocked journal path, applied/not-durable errors, retained records and claims, retry, failed save polling, close gating/error reporting, out-of-order redo and overlapping saves.
- `NativeSaveIT`: 13 checks on personal JEB covering repeated saves, native lock retention/release, a database-only reopen retaining a class name and comment, and invalid recovery leaving the original database bytes unchanged.
- `NativeRecoveryIT`: 11 checks across two fresh JVMs covering accepted rename/comment records, a deliberate interrupted native write, checkpoint restore, journal replay and a later database-only reopen.
- `NativeHistoryRecoveryIT`: 48 checks across three fresh JVMs covering saved class/method/comment/local/parameter changes and an obfuscated original name, unsaved undo, restart, saved undo, unsaved redo, restart, ownership reconstruction, discard and final database-only reopen.

The concurrent rename and method-family suites separately verify that the persistence gate and claim rebuilding preserve the guarded mutation contract. These checks establish the exercised paths on the tested filesystem and JEB version. They do not simulate physical media failure or prove that hardware honors every flush. Disk loss, loss of every copy, invalid or incomplete legacy history, externally altered timestamps, SDK incompatibility and unsupported filesystem directory-sync semantics remain possible. Directory-sync failures are reported, not silently described as durable. Keep independent backups and verify them by reopening.

**NOT HIBERNATION-SAFE — laptop must remain awake and connected.** When Jeeves runs locally, analysis, save/backup completion and agent connections depend on that host. Finish the explicit save, verified backup and required cleanup before sleeping or disconnecting. The required awake time depends on the database and transfer size; a copied database is a checkpoint, not proof that the complete workflow survives sleep.
