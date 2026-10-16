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
classes precede retries of incomplete classes. There is no attempt ceiling.

Install jeeves-campaign@.service. Start an explicitly requested number of sessions:
 systemctl start jeeves-campaign@1
Use @30 only when thirty sessions have actually been requested. Do not run two
instances: controller.lock excludes overlapping catalog controllers. invocation.json
preserves the intended completed-session count across controller restarts.
For reboot continuation, enable the requested instance. A graceful stop is:
 touch /var/lib/jeeves/campaign/stop-after-session
It permits the current eight agents and save/verification barrier to finish.
Remove that file before an explicitly requested continuation. The website's
individual pause/stop controls still work; paused missions wait for manual resume.
The server is a separately managed service; stopping the controller does not
stop in-flight agents. Never restart the server during an active session as a
routine deployment operation.

After eight terminal missions, the runner waits for their tool operations,
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

Operating limits and persistence:
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
