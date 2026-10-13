# MCP server design: context economy and unattended agents

Research note, 2026-09-18. Web research by a delegated agent (54 tool uses), two framing claims
re-checked by hand against the primary pages the same day.

## Verification status

| Claim | Status |
|---|---|
| The current MCP spec revision is **2026-07-28**: stateless (no `initialize`, no sessions), `server/discover`, tasks moved to an extension (`tasks/get` polling), Multi Round-Trip Requests replace server-initiated elicitation/sampling, Roots/Sampling/Logging deprecated, cross-call state via "explicit, server-minted handles passed as ordinary tool arguments", servers SHOULD return `tools/list` "in a deterministic order to enable client-side caching and improve LLM prompt cache hit rates" | **Verified** on modelcontextprotocol.io/specification/2026-07-28/changelog |
| Claude Code: tool search is the default; MCP output warning at **10,000 tokens**, default cap **25,000** (`MAX_MCP_OUTPUT_TOKENS`), per-tool `_meta["anthropic/maxResultSizeChars"]` up to **500,000 chars** | **Verified** on code.claude.com/docs/en/mcp |
| Claude Code truncates tool descriptions and server instructions at 2 KB; a tool-level `_meta["anthropic/alwaysLoad"]` | **Not found** on that page in my check — treat as unverified |
| `instructions` now lives in the `server/discover` result | Plausible (discover carries identity and capabilities) but not stated on the changelog page — unverified |
| Numbers from Anthropic / GitHub / Block / Speakeasy / AWS / JetBrains posts | Read through a summarizer by the research agent; consistent across sources, quotes may be paraphrased |

## What the sources say (condensed)

Well supported by primary sources:
- **Fewer, workflow-shaped tools; never one tool per endpoint.** Anthropic: tool selection "degrades once you exceed 30–50 available tools"; wrapping every endpoint is the named anti-pattern.
- **Consolidate near-duplicates behind an enum parameter.** GitHub merged four tools into one with a `method` parameter; its Projects consolidation saved about 23k tokens (50%).
- **Descriptions are the top lever** ("by far the most important factor", "at least 3–4 sentences"); tool search matches names, descriptions, argument names and argument descriptions.
- **Token-efficient responses**: a `response_format` concise/detailed switch, high-signal fields only (Anthropic's example: 206 vs 72 tokens).
- **Bound every result and truncate with a steering message**; Claude Code spills anything over its cap to a file and hands the model a path.
- **Validation errors are tool errors** (`isError:true` with actionable text), so the model can self-correct (SEP-1303, spec 2025-11-25).
- **Annotate tools** (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`): unannotated tools get the pessimistic defaults; clients use the hints for auto-approval.
- **Short, static server instructions** about the cross-tool workflow. GitHub's experiment (n=40): workflow adherence 60% → 85%; GPT-5-mini 20% → 80%; Sonnet 4 100% without, 90% with.
- **Leave progressive discovery to the host and keep `tools/list` stable**: changing the tool array mid-conversation invalidates the prompt cache.
- **Evaluate with realistic multi-call tasks**; read transcripts.

Practitioner measurements: GitHub Copilot 40 → 13 built-in tools (+2–5 points on SWE-bench-type tasks, −400 ms latency); Anthropic tool search −85% definition tokens (Opus 4 49% → 74%); Block's Linear server 30+ → 2 tools, "don't mix read and write in the same tool", no dynamic data in instructions; Speakeasy dynamic toolsets −91–97% input tokens but 2–3× the tool calls; "Agents Don't Paginate" (arXiv 2608.26130): zero agent-initiated second-page requests in one middleware's logs.

Evidence against things we might assume: against many small tools (Anthropic, GitHub, Block); against over-consolidation (AWS: focused tools with constrained schemas, ≤ ~8 parameters; never merge reads with writes); against heavy pagination (the zero-continuation finding); against verbose descriptions everywhere (every call pays them where tool search is absent — be detailed only about non-obvious behaviour, and put it first); against server-side dynamic discovery (call-count penalty, cache invalidation); against "code mode" for this domain (reading decompiled code is exactly the "model should see intermediate results" case).

## Applied to Jeeves

State on 2026-09-18: the bridge's default catalogue is the curated one (45 tools, about 6.6k tokens; was 147 tools, about 12.6k); the mission loop sees 24. Definitions are lean (about 150 tokens per tool; GitHub's run about 740) — the old defect was 104 undescribed tools, not the token count.

Recommendations, in order of value:
1. **Merge the confusable families.** `xref_to` / `xref_from` / `callers` / `callees` → one `xrefs {symbol, direction: to|from, kind: call|field|all}`. The five map readers → one `map {id?, view}`. `symbol_tree` + `symbol_search` → one `symbols {prefix | query}`. The loop drops from 24 to about 16 tools, inside every published comfort zone, including weaker models'.
2. **Profiles instead of one catalogue**: reversing (reads + writes), operator (missions, providers, review policy), all. Missions never see operator tools (already true in the loop; make it true for the bridge).
3. **Bounded results with a stated cut.** Agents do not page. A default row limit with `{rows, total, truncated}` and a steering line ("200 of 834 — narrow with package=…") is a visible cut, not a silent one. This revisits the 2026-09-12 rule ("every row unless the caller sets a limit"): John's call.
4. **Annotations on every tool**: reads `readOnlyHint:true, openWorldHint:false`; rename/comment `idempotentHint:true`, non-destructive on the strength of History undo; operator tools `openWorldHint:true`. Cheap, and it removes permission prompts for reads in annotation-aware clients.
5. **Server instructions, static, under 2 KB**: the canonical loop (symbols → outline → method body → xrefs → write), the address grammar, that old names still resolve after a rename, result limits and how to narrow, and the write semantics (a write answers `applied` or `pending`; a pending write is not visible in later reads). The "shared preamble" of the descriptor proposal belongs here for MCP clients.
6. **Batch writes** (`items[]` with per-item results, verbose only for failures) — already in the descriptor proposal; the sources agree, since JSON-RPC batching is gone and each single call costs a full inference turn.
7. **Errors that steer**: tool errors as `isError` results; a not-found symbol returns the nearest candidates (the lenient resolver already has them).
8. **Decompile output as plain text**, not a JSON-escaped string, with the JVM signatures the next call needs.
9. **Long-running work behind a handle** (analysis on open, saves): return at once, `status {id, wait_s}` and `cancel`. Missions already work this way.
10. Keep `tools/list` order deterministic (it is: registration order) and never toggle tools mid-session.

Not recommended: server-side tool discovery or meta-tools, code-execution mode, gating writes through elicitation (unattended agents have nobody to answer; the application-side review switch shipped today is the right mechanism).
