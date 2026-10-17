CLOUD CATALOG CAMPAIGN

Run one activated JEB/Jeeves process and eight API agents on the remote host.
No laptop, SSH tunnel or local agent process supervises a session. Install JEB
from its official distribution and activate the service account normally first.
The runtime must use the committed backend/web build and Java 21. This directory
contains no JEB library, license, database or API key.

Set JEEVES_CAMPAIGN_ROOT, JEEVES_REPO, JEB_HOME, JAVA_HOME, WORKBENCH_HOME and
JEEVES_APK_SHA256 in the service EnvironmentFile. Set WORKBENCH_HEADLESS=1 and
WORKBENCH_UNLIMITED_BUDGET=1; the runner checks both effective flags at spawn.
The supplied socket proxy can expose the loopback server on an explicit Tailscale
IP, bound to tailscale0. Replace its example address before enabling it and include
that exact authority in WORKBENCH_PROXY_HOSTS. It accepts 4096 TCP connections;
this is a transport setting, independent of the eight model slots. No public
listener is enabled. Tailscale encrypts traffic; browser HTTPS via Tailscale Serve
is an optional alternative when that tailnet feature is enabled.

Configure native Max reasoning and exact model IDs in Jeeves llm.json; store
provider keys in its owner-only llm-keys.json. The example uses four DeepSeek V4
Pro and four GLM-5.3 slots. All-eight of either provider uses the same runner by
changing the eight model entries. There is no silent model substitution.

Before launch, import a hash-verified checkpoint, its sidecars/project metadata,
and historical-aliases.json. Remap targets.json storePath to the new APK path.
The native JDB and key must remain paired. Do not open a live writable JDB in a
second process. Rebuild search text or transfer its validated cache separately.

With the service environment loaded, run:
 python3 campaign.py catalog --config /var/lib/jeeves/campaign/config.json
 python3 campaign.py status --config /var/lib/jeeves/campaign/config.json

Catalog preparation walks every package, passes the actual class count as the
listing limit and rejects truncation/count disagreement. SQLite keys preserve
original descriptors byte-for-byte, including case and Unicode distinctions.
Each session reserves eight distinct classes transactionally. A final partial
page is filled with completed classes for another verification pass. Pending
classes precede ordinary retries of incomplete classes. There is no attempt ceiling.
An optional repair_queue config path points to a JSON list inside the campaign
root: [{"original_class":"LX/18X;","after_session":2,"goal":"Concrete concern to check"}].
The class must exist and belong to the selected focus. This request gets priority
until the class is assigned after the cited session; that assignment consumes its
priority even if the class still needs review. Ordinary eligibility remains.
Duplicate requests and classes outside the focus are rejected. Prior class
results and review findings accompany every later assignment as evidence to
check, never proof. This preserves exactly eight distinct slots and their model mix.

Install jeeves-campaign@.service. Start an explicitly requested number of sessions:
 systemctl start jeeves-campaign@1
Use @30 only when thirty sessions have actually been requested. Do not run two
instances: controller.lock excludes overlapping catalog controllers. invocation.json
preserves the intended completed-session count across controller restarts.
Once invocation.json says complete, restarting the service exits without creating
another invocation. For a newly authorized run, first archive that completed file
under an unused timestamped name; the next start then creates the new intent.
For reboot continuation, enable the requested instance. A graceful stop is:
 touch /var/lib/jeeves/campaign/stop-after-session
It permits the current eight agents and save/verification barrier to finish.
Remove that file before an explicitly requested continuation. The website's
individual pause/stop controls still work; paused missions wait for manual resume.
The server is a separately managed service; stopping the controller does not
stop in-flight agents. Never restart the server during an active session as a
routine deployment operation.

After eight terminal missions, the runner also waits for loopActive and
cancellationUnconfirmed to clear and for their tool operations to finish. It
captures current expectations for all edit-history symbols (including locals
and argument metadata), requests a fresh save, closes the database, builds and
hash-verifies an immutable checkpoint, opens a separate native-only copy WITHOUT
history replay, then reopens and checks the live target. Only that successful
barrier commits class results and permits the next session. A failed barrier
retains its stage and evidence, retries after 30 seconds, and admits no new work.
The pause is a retry interval, not a deadline or attempt limit. An interrupted
archive is retained under an .incomplete name. Native verifier copies are retained.

A lost spawn acknowledgement is reconciled by its unique assignment tag in
agent.missions. If no matching mission can be found, the runner retains an
ambiguous intent and requires reconciliation, rather than risk duplicate writers.
An interrupted model session is classified needs_review and requeued after its
accepted edits pass the save barrier. Full provider conversations cannot resume
inside Jeeves after a JVM restart; a fresh class investigation reads current state.

CLASS_RESULT is a model's completion claim, NOT proof of semantic correctness.
Counts and unresolved items are recorded. Native/live verification proves that
accepted names/comments survived saving; it does not establish their meaning.
Missing/malformed receipts and failed missions remain needs_review.

After the save barrier, quality.py inventories each of the eight classes and
collects full decompiled class text, every method's argument/local metadata,
the session's history delta and the agent's completion claim. Earlier per-class
history is separately included as prior_changes. Successful recorded code reads
of callers/reference sites are included as supporting_reads, using the last
result per exact request and preserving requested windows. These are historical
observations, not a promise of current display names; original identities and
history disambiguate them. Model reasoning is excluded. UI transcript clipping
and missing turns are explicit evidence gaps, never fabricated full source.
Earlier public edit rationales are recovered from the verified checkpoint's
project proposals. Archived file hashes, project/artifact identity, history ID,
author and applied value must agree. Pending, unchanged, undone, discarded or
mismatched edits do not supply accepted evidence. This reads the archive without
JEB/API calls or historical rewrites; missing/corrupt evidence remains explicit.
These rationales are claims to check against code, not proof. The opposite
provider makes one static Max review per class (GLM reviews DS, DS reviews GLM).
The eight evidence packets are collected sequentially before provider requests
start. Their eight reviews then run concurrently: four requests per provider,
with the same settings and request count as before. Completed results are
published incrementally in assignment order by one controller thread. Every
review must settle before the campaign advances; a sibling failure does not
cancel active paid requests. Durable per-class intents/results prevent a
controller restart from blindly charging for the same review again.
It receives no mutation tools. Raw streamed responses, usage, evidence and
findings are durable under rounds/cloud-NNNNNN/quality. Generic-name flags are
only heuristics; model assessments are not ground truth. No corrective renames
are implied by flags for short locals such as v/s/z12 or members such as A0a.
A meaningful one-letter counter can be retained with evidence. No renames
or second reversing session are launched by review. A failed/ambiguous paid
review is retained for manual review instead of automatically repeating it.
Collection errors, reported issues and missing claims keep classes needs_review.
Full inventory uses native reads and history.json rather than history.list's
existing 200-entry default / 2000-entry ceiling. This collector adds no truncation;
the existing UI transcript restrictions above remain visible for recorded reads.

jeeves-status.service exports campaign-only dashboard.json every five seconds.
Read failures retain the last snapshot; each monitor API call has a 30-second
network wait. relay.py serves the existing local port 7930 and fetches ONLY that
snapshot over SSH every five seconds. Its SSH connect wait is 15 seconds, dead
connection detection 15 seconds x2, and per-fetch wait 90 seconds; a failure
retains the last snapshot and retries. None of these monitor settings cancels
inference, saves or review. Browser data older than 45 seconds is visibly stale.
The dashboard has all changes with a latest-30 display and Show all, filtering,
original/current names, per-agent usage, save stages and independent quality
findings. The old K3 dashboard files/history remain separate and intact.
Local dashboard relay is NOT HIBERNATION-SAFE; it catches up after reconnect.
Cloud campaign/review/exporter are separately supervised by systemd.
The dashboard publishes each completed class's existing partial coverage file
while the audit is collecting. Post-save partial evidence supersedes pre-save
evidence; partial results never claim a completed audit. A new session without
an audit clears the previous session's coverage display. This adds no native or
provider calls to the exporter.

Prepared maintenance can use deploy_after_session.py and the supplied systemd
unit. Stage a complete source/backend/web release under /opt/jeeves/releases,
with release-manifest.json containing its clean revision and each file's SHA256.
Link campaign/maintenance/runner to the prepared release's ops/cloud directory and
write campaign/deployment-request.json with root, source, release, revision,
session, artifact, project and loopback url. The unit and its save child use that
immutable maintenance bundle, not modules from the older running source tree.
Enable the unit only for an authorized deployment.
It waits without a deadline for the requested invocation to finish, all agents
and writes to drain, the save/native/live verification receipt, and all eight
quality records. Findings may require review; they do not trigger new agents.
It checks unchanged history, preserves any additional read-only decompilation
through another verified save if needed, and closes the clean database before
restarting Jeeves. The prior release stays on disk. Pre-deployment save stages
are durable and restartable. New maintenance round
directories are created before their barrier children. Root controls activation;
save/native verification runs as the existing jeeves service account with its
runtime environment and owns its private checkpoint files. After an interrupted
save, resume its durable stage even if decompiled is zero or the target is closed;
do not reopen the live database alongside native verification.
Activation/reopen stages
are durable and restartable; the running build and all saved names are checked
after activation. A changed history, unsaved work, bad release hash or failed
verification retains an error and prevents a success claim. No provider calls,
new session, dollar cap or teardown are added by maintenance. Cloud supervision
survives laptop loss; the local dashboard still requires the laptop connection.

Operating limits and persistence:
For a focused campaign, set focus_manifest to an absolute path ending in
manifest.json and optionally focus_first_classes to the ordered original class
identities for the next session. Run focus.py --config CONFIG while paused.
It discovers current FMessage-named classes and all direct incoming references
to their classes, fields and methods, with uncapped xref.to responses. The full
class catalog remains intact. It records stable original identities and all
reference-site evidence, rejects truncated/ambiguous results, and resumes read
collection from an APK/history-pinned cache. It does not decompile, rename or call
a provider. This is a direct-reference scope, not a whole-program transitive walk.

The runner selects only this focused queue; a missing/invalid manifest prevents
new assignments instead of falling back to generic classes. Eight distinct class
owners still run per session. Tail verification uses already-completed focused
classes only. Assignments include the FMessage relationship and entry symbols;
agents read reference bodies, preserve supported earlier names and batch ready
local/argument/member edits with annotate(items). The independent review retains
the same assignment context; a graph relationship alone is not semantic proof.
Preparing a focus manifest never resets a completed invocation or starts a round.

- Existing inherited-field outline traversal stops at six superclass levels;
  impls has an internal 500-result ceiling. Focus discovery uses neither: it
  reads own-member outlines and uncapped incoming references. Unknown subclasses
  are included when referenced directly; this is not proof of an exhaustive
  transitive subtype inventory.
- Exactly eight API agents per session (operator requirement), sharing one JEB.
- No headless turn or mission-token ceiling. Explicit provider context/output
  bounds still apply: configured 1,000,000 context; DS 393,216 output; GLM 131,072.
- Existing annotate write batches hold at most 50 items; the prompt requires
  successive batches and inspection of every result. Package listings default
  to 500; catalog collection passes the full measured class count explicitly.
- Provider transport has existing 15-second connect, 120-second ordinary HTTP
  and 600-second streaming-response-header limits. A provider/transport refusal
  becomes a blocked mission; the campaign saves accepted work and requeues it.
- Existing UI transcript retention is 400 turns; recorded text/tool review fields
  are shortened to 24K/8K characters. These are review-storage bounds, not model
  tool-result truncation; headless tool payloads and name provenance are complete.
- Context maintenance may evict older tool results as the provider window fills.
  Agents should retain durable findings in comments and reread code as necessary.
- JDB/checkpoint durability is on this host. Host deletion/disk failure still
  requires an off-host copy. Checkpoints and verifier copies accumulate; monitor
  real free space. No automatic deletion of the user's checkpoints is performed.
- Provider credits, entitlement, rate limits, machine RAM/disk, JEB license and
  Tailscale key expiry are external constraints. No purchased-funds cap or server
  teardown policy is implied. The CCX43 bills while provisioned.

Do not claim HIBERNATION-SAFE merely because this service file exists. Qualify
actual execution, supervision, durable saves/results and access after SSH/client
loss; report any remaining laptop-dependent setup, backup or cleanup step.

Durable completion-pass coverage (16 October 2026)

The class prompt preserves supported Kimi/K3 names and requires naming_coverage
for every own method. Assessments are durable per actor and current inventory;
the CLASS_RESULT counters alone never qualify a class. The coordinator writes
coverage-before.json after all eight settle, includes each available whole-method
inventory fingerprint in the native/live barrier, and writes coverage-after.json
before independent semantic review. Missing/unresolved/stale assessments or
collection errors keep the class in needs_review even if the agent and reviewer
say complete. A method with no variables still needs an explicit empty receipt.
The dashboard shows method and variable coverage separately from rename counts.
Per-worker writes are counted from current-session durable history, including
direct local/argument renames and accepted proposals, excluding undone/discarded
edits. The old API counter is retained separately for diagnostic comparison.

The existing 50-item annotate batch limit remains; partial rename failures must
be inspected and only failed entries retried. The new coverage tool has no item
cap and accepts partial merges. No headless turn/token mission cap is added. The
HTTP provider output/context allowances still apply. Abstract/non-rendered
arguments without a local target remain unresolved if they need names; this
release does not bypass the analyst-only parameter mapping guard. Deployment
and qualification do not clear invocation.json or launch another paid session.

A follow-up deployment may encounter a database saved by an earlier maintenance
barrier after the session's original save. The deployer accepts that later file
only when its exact checksum and unchanged history match the currently installed
release's completed native/live verification receipt. Unexplained database changes
still refuse activation. This reconciliation also repairs an older deployer that
closed the target before discovering it had used the stale session checksum.
Overnight execution
-------------------
campaign.py run --config /var/lib/jeeves/campaign/config.json --hours 8
uses an eight-hour admission window, with no session-count limit. Each session
has eight agents, then a verified native save/reopen and independent quality
review. Only after those finish can another session start. The active session
finishes even if the window expires during inference, verification or review.

The first launch atomically saves started_at and deadline_at in invocation.json.
Process recovery reuses that deadline; restarting cannot grant another eight
hours. A completed invocation remains completed until an authorized operator
archives it. stop-after-session requests the same graceful boundary. A controller
file lock prevents two units from operating the same catalog concurrently.

jeeves-night@8.service runs this entirely on the cloud host, is restartable on
failure and can be enabled across reboot. It Wants rather than Requires Jeeves:
temporary backend failure must not permanently stop the recovering controller.
Pin its WorkingDirectory and ExecStart to the tested controller release when the
Java backend remains on a different release. Never restart a live backend merely
to install a Python scheduling change. Preserve the previous invocation, catalog,
configuration and verified checkpoints when explicitly starting a new run.

This window is not a dollar ceiling or an eight-hour kill timer. Provider credit,
context, output and availability constraints still apply. Saving/reviewing may
continue beyond the deadline. The provisioned cloud server continues billing;
no automatic top-up or teardown is configured. The local dashboard relay requires
the laptop, but the cloud campaign and durable results do not. Qualify the actual
deployed units, model slots and saved deadline before claiming sleep safety.

To deploy during a running window, stage and verify the immutable release and
deployment-request.json first. Also write maintenance-request.json with revision,
session and invocation_started_at matching the running invocation and requested
session. The coordinator finishes that session's save and review, writes matching
maintenance-ready.json, then releases controller.lock while waiting for the
deployer's completed receipt. The deployer independently rechecks the catalog,
native/live save, quality, history and active operations before restarting Jeeves.
Only a matching clean runtime and positive live-verification receipt release the
coordinator to its next admission check. Controller restart follows the same wait
before making any API call. The original deadline and stop-after-session remain
in force; maintenance never creates a new invocation or grants extra run time.
The maintenance service, coordinator and their request files live on the cloud.

