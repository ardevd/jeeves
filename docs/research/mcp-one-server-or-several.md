# One MCP server or several? Research note, 2026-09-18

Web research by a delegated agent (pages accessed 2026-09-18), condensed. Companion to
`mcp-design-best-practices.md`.

## Findings, well supported

- **The 30–50 figure counts tools in context across all servers, not per server.** Anthropic's tool-search
  doc: a "typical multiserver setup (GitHub, Slack, Sentry, Grafana, and Splunk) can consume ~55k tokens in
  definitions… Claude's ability to pick the right tool degrades once you exceed 30–50 available tools"; the
  worked example is 58 tools summed over five servers. Recommended: keep 3–5 most-used tools non-deferred;
  "Use consistent namespacing in tool names: prefix by service or resource… so one search matches the
  whole group." (platform.claude.com …/tool-search-tool; anthropic.com/engineering/advanced-tool-use)
- **Claude Code makes server count nearly free.** "Only tool names and server instructions load at session
  start, so adding more MCP servers has minimal impact on your context window. Claude Code doesn't impose a
  fixed per-server tool cap." Deferral is global; exemption per server (`alwaysLoad`) or per tool
  (`_meta["anthropic/alwaysLoad"]`, "for a small number of tools"). Server instructions are the discovery
  hook ("explain what category of tasks your tools handle, when Claude should search"). **Tool descriptions
  and server instructions are truncated at 2 KB each.** Names are prefixed `mcp__<server>__<tool>`.
  (code.claude.com/docs/en/mcp, "Scale with MCP tool search")
- **Permission boundaries already work within one server:** allow/deny rules take globs after the literal
  `mcp__<server>__` prefix (`mcp__github__get_*`); subagents can be scoped to servers or tool globs.
  (code.claude.com/docs/en/permissions, …/sub-agents)
- **Spec 2026-07-28:** tool names unique per server only; aggregating clients "SHOULD implement a
  disambiguation strategy such as prefixing tool names with a server identifier"; `serverInfo.name` is not
  a disambiguator; per-server `instructions`; tool lists "MUST NOT vary per-connection"; cross-call state
  through explicit handles returned by a creation tool and passed as arguments. Nothing on server
  granularity. (modelcontextprotocol.io/specification/2026-07-28/server/tools, …/draft/server/discover)
- **Prompt cache:** modifying tool definitions invalidates the whole cache; deferred tools stay out of the
  prefix. (platform.claude.com …/tool-use-with-prompt-caching)
- **Client limits elsewhere:** VS Code Copilot: 128 tools per request across all sources (over-threshold
  tools grouped behind `activate_*` stubs). Cursor: 40 active tools total across servers (2025 forum;
  current docs give no number — low-moderate confidence it still holds). OpenAI Agents SDK: no cap,
  collisions handled only with `include_server_in_tool_names`. Anthropic MCP connector: per-server
  `defer_loading`, allow/deny per tool.

## Practitioner evidence

- **GitHub MCP server: one binary, catalogue by profile.** `--toolsets`, `--tools`, `--read-only`; the
  remote server mounts each toolset at its own URL (`/mcp/x/issues`, `/mcp/x/repos/readonly`). "Enabling
  only the toolsets that you need can help the LLM with tool choice and reduce the context size." Default
  profile = 5 of 22 toolsets. (github.com/github/github-mcp-server)
- GitHub Copilot: 40 → 13 built-in tools, +2–5 points success, −400 ms; too many tools and "the agent ends
  up ignoring explicit instructions, using tools incorrectly, and calling tools that are unnecessary."
- Block: workflow-first tools, one risk level per tool (read-only vs not), instructions feed the system
  prompt, annotations feed approval; silent on one-vs-many servers.
- Cloudflare: 13 servers, split for **auth scope** ("exactly the permissions needed for its specific task").
- AWS: 15K APIs → ~10 tools in one server; duplicate tools across servers "confuse AI agents".
- Speakeasy: a 107-tool server broke small models; 20 tools 19/20; two focused 10-tool servers together: no
  errors (anecdotal, small models). RAG-MCP (arXiv 2505.03275): DeepSeek-v3 confusion above 30 tools.

## Answer for the workbench

**One process, one default registration, a profile-selectable catalogue, group-prefixed tool names. Not four servers.**
- In Claude Code the split is invisible to the model: every tool is deferred either way and ToolSearch
  spans all servers. A split only multiplies instructions, trust prompts and processes.
- 46 tools sits above the in-context threshold and above Cursor's 40, so non-deferring clients need
  profiles anyway. Copy GitHub: `--profile code,maps` (stdio) and `/x/code`, `/x/runs`, `/x/ops`,
  `/x/maps`, `/readonly` mounts on an HTTP endpoint later — "several servers" from a client's view, one
  process, no duplicated `search`.
- Namespace the tools by group (`code_*`, `run_*`, `ops_*`, `map_*`): one ToolSearch query finds the whole
  group, and permission globs (`mcp__jeeves__code_*` allow, `mcp__jeeves__ops_*` deny for mission
  subagents) work without a split.
- The one justified second registration: **ops** (lifecycle, LLM config, review policy) — a different risk
  class and audience; in clients without per-tool filtering a separate endpoint is the only way to withhold
  it (Cloudflare's rationale). So `jeeves` (code + maps + runs) and optionally `jeeves-ops`. Two, not four.
- State: the bridge holds none; the current target and mission live in the backend, so every endpoint
  resolves them the same way. Every tool keeps an optional `target` (and `mission` where relevant) handle,
  defaults to the backend's current one, and reports the handle it used.
- Keep the catalogue fixed for the session: no device-dependent server that appears on attach, no
  `listChanged` when a mission starts — return "no device attached" from the tool instead.

## What not to do
Same bare tool name on two servers in front of clients that do not prefix; splitting to dodge a tool count
in Claude Code (there is none); duplicating a tool across servers; mixing read and write in one tool;
exposing raw backend ops one-to-one; `alwaysLoad` beyond a handful; instructions or descriptions over 2 KB;
mutating tool definitions mid-session.

## Outcome (2026-09-18, evening)
John chose the simpler shape: one server `jeeves`, no profiles, no group prefixes. The bloat was
removed by merging instead — the 24-tool facade in `dev.workbench.llm.ToolFacade` (see the plan,
phase 2a, and the descriptor page revision 4). Note that "mixing read and write in one tool" above is
still honoured: `annotate` writes, the reads read; the action tools (`map_edit`, `mission`, …) group
writes with writes.
