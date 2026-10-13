package dev.workbench.llm;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM provider registry (multi-LLM support). Providers carry
 * {id, label, driver: builtin|cli|http, baseUrl, models[], local, needsKey}.
 * Built-ins:
 *   scripted   — the deterministic triage loop (no model)
 *   claude     — headless claude CLI (existing mode)
 *   deepseek   — https://api.deepseek.com (key)
 *   openrouter — https://openrouter.ai/api (key)
 *   ollama     — http://127.0.0.1:11434 (LOCAL, no key)
 *   custom     — user-set baseUrl (key)
 *   fixture    — LOCAL canned driver for tests (no key, no network)
 * User edits ({baseUrl, defaultModel} per id, plus new http providers) merge in
 * from the gitignored backend/.workbench/llm.json via llm.configure.
 *
 * Provider vs model (llm-config design, 2026-09-11): a provider is ONE vendor
 * endpoint with ONE key; its models[] are the versions selectable under it and
 * defaultModel is explicit — INVARIANT defaultModel ∈ models, or both empty
 * (enforced on write, repaired on read). Ids are the vendor (zai, deepseek),
 * never a model version, and change only through a rename.
 *
 * Store contract (§5): a mutator validates, snapshots what it touches, applies,
 * persists, and on a failed write REVERTS memory and throws E_IO. Memory and
 * disk never silently diverge until a restart.
 */
public final class LlmRegistry {

    public static final class Provider {
        public String id, label, driver = "http", baseUrl = "", defaultModel = "";
        public List<String> models = new ArrayList<>();
        public boolean local, needsKey = true;
        /** How the provider connects — "cli" | "api" for a vendor that offers both
         *  (anthropic: your Claude Code sign-in, or an Anthropic API key); "" for a
         *  single-option provider. driver / baseUrl / needsKey / the seed list DERIVE
         *  from it (applyAuth) — auth is what persists (llm-anthropic-vendor design). */
        public String auth = "";
        /** Per-provider extra request params merged into every chat request body
         *  (e.g. DeepSeek thinking mode). Never sent by other providers. */
        public Map<String, Object> extraParams = new LinkedHashMap<>();
        /** The model's context window in tokens. The agent loop sends the whole history and
         *  evicts the OLDEST tool results only above ~90% of this (minus the completion reserve);
         *  a wrong value is not fatal — a provider "context length" refusal evicts and retries
         *  once and ADOPTS the maximum the refusal states (setContextTokens). 1M by default — most
         *  current models; an overestimate costs one retried request, an underestimate throws away
         *  context the agent needs; ollama/fixture 128k; llm.configure sets it. */
        public int contextTokens = 1_000_000;
        /** The wire contract beyond OpenAI's. A thinking
         *  model returns its trace on the assistant message under {@code reasoningField}
         *  ("reasoning_content"; "" = none expected). {@code reasoningReplay} says what the
         *  provider wants done with it when the history is sent back:
         *    all   — every earlier assistant message keeps it while tools are in play (DeepSeek
         *            thinking mode: a 400 otherwise; Kimi K3: "pass the complete assistant
         *            message back as-is")
         *    task  — kept within one tool-call loop, dropped once a new user message starts a
         *            task (Kimi K2.x without preserved thinking)
         *    never — stripped before resending (endpoints that do not know the field)
         *  The agent loop serialises per policy; nothing is sent to a provider that does not want
         *  it, nothing a provider needs is dropped. */
        public String reasoningField = "", reasoningReplay = "never";
        /** The endpoint answers chat completions as an SSE stream only (some gateways require
         *  stream:true): the client collects the deltas into one message. */
        public boolean streamOnly;
        /** The endpoint's own cap on max_tokens (0 = none). */
        public int maxOutputTokens;
        /** Defined by the operator through the environment (WORKBENCH_LLM_&lt;ID&gt;_URL …): read,
         *  usable, not editable from the UI or the ops. */
        public boolean managed;
    }

    public static final List<String> REPLAY_POLICIES = List.of("all", "task", "never");

    /** a provider id: a file-safe slug (it names the key in llm-keys.json and the
     *  env override WORKBENCH_LLM_&lt;ID&gt;_KEY) */
    static final String SLUG = "[a-z0-9][a-z0-9_-]{0,31}";

    private final File configFile;
    private final KeyStore keys;
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    /* the shipped providers and their shipped model lists / labels: user-added
       ones can be removed (llm.remove) and persist their label/models; a
       built-in persists its list or label only when the user changed it */
    private final Set<String> builtinIds = new LinkedHashSet<>();
    private final Map<String, List<String>> builtinModels = new LinkedHashMap<>();
    private final Map<String, String> builtinLabels = new LinkedHashMap<>();
    private final Map<String, Boolean> builtinLocal = new LinkedHashMap<>();

    /** the shipped LOCAL flag (false for a user-added provider) */
    private boolean shippedLocal(String id) { return Boolean.TRUE.equals(builtinLocal.get(id)); }
    /* transient read failure at load: the on-disk llm.json may be healthy — the
       first persist parks it aside as evidence instead of silently overwriting
       the user's provider config with derived state (fix-1-r3, review #12) */
    private boolean configDegraded;

    public LlmRegistry(File sidecarDir, KeyStore keys) {
        this.configFile = new File(sidecarDir, "llm.json");
        this.keys = keys;
        builtins();
        builtinIds.addAll(providers.keySet());
        for (Provider pr : providers.values()) {
            builtinModels.put(pr.id, new ArrayList<>(pr.models));
            builtinLabels.put(pr.id, pr.label);
            builtinLocal.put(pr.id, pr.local);
        }
        mergeConfig();
    }

    private static Provider p(String id, String label, String driver, String baseUrl,
            boolean local, boolean needsKey, String... models) {
        Provider pr = new Provider();
        pr.id = id;
        pr.label = label;
        pr.driver = driver;
        pr.baseUrl = baseUrl;
        pr.local = local;
        pr.needsKey = needsKey;
        pr.models = new ArrayList<>(List.of(models));
        pr.defaultModel = models.length > 0 ? models[0] : "";
        return pr;
    }

    /** Anthropic's two connections (llm-anthropic-vendor design). `cli` = your Claude Code
     *  sign-in: the CLI's aliases (claude-code = its own default, no --model; the rest go
     *  as --model), no key. `api` = an Anthropic API key against the OpenAI-compatible
     *  surface (Bearer works on chat AND /models — reviewer-verified). Disjoint id-spaces,
     *  so a switch RE-SEEDS the list. */
    static final List<String> ANTHROPIC_CLI_MODELS = List.of("claude-code", "fable", "opus", "sonnet", "haiku");
    static final List<String> ANTHROPIC_API_MODELS = List.of("claude-opus-5", "claude-sonnet-5", "claude-fable-5-1", "claude-haiku-4-5");
    static final String ANTHROPIC_API_BASE = "https://api.anthropic.com/v1";
    /** the legacy id of the Anthropic provider — reserved so the resolve alias can never be shadowed (A4) */
    static final String LEGACY_CLAUDE_ID = "claude";

    private void builtins() {
        providers.put("scripted", p("scripted", "Scripted (built-in)", "builtin", "",
                true, false, "builtin:triage"));
        /* Anthropic — the VENDOR; how you connect is the option under it. Shipped as
           `cli` (zero-config once `claude auth login` has run — today's working path);
           driver / baseUrl / needsKey / the list DERIVE from auth (applyAuth). */
        Provider an = p("anthropic", "Anthropic", "cli", "", false, false, ANTHROPIC_CLI_MODELS.toArray(new String[0]));
        an.auth = "cli";
        providers.put("anthropic", an);
        providers.put("deepseek", p("deepseek", "DeepSeek", "http", "https://api.deepseek.com",
                false, true, "deepseek-v4-pro", "deepseek-flash"));   // "deepseek-flash" is the id the endpoint serves — the old "deepseek-v4-flash" was silently aliased (wet test 2026-09-12)
        /* thinking mode is default-enabled on v4; agent requests auto-raise to max.
         * reasoning_content rides back on the raw assistant message we already
         * append, so multi-turn tool loops stay valid. */
        providers.get("deepseek").extraParams = new LinkedHashMap<>(Map.of(
                "thinking", Map.of("type", "enabled"),
                "reasoning_effort", "high"));
        /* DeepSeek thinking mode with tools: "the reasoning_content of all previous turns should
           be passed back to the API … even for turns where the model did not perform a tool
           call", else "the API will return a 400 error" (api-docs.deepseek.com/guides/thinking_mode,
           read 2026-10-07). Without tools the field is ignored, so one policy serves both. */
        providers.get("deepseek").reasoningField = "reasoning_content";
        providers.get("deepseek").reasoningReplay = "all";
        /* Moonshot (Kimi): "for multi-turn conversations and tool calls, pass the complete assistant
           message returned by the API back to messages as-is (including reasoning_content)" for
           kimi-k3; K2.x keeps it within one tool-call loop unless preserved thinking is on
           (platform.kimi.ai/docs/guide/use-kimi-k2-thinking-model, read 2026-10-07). Model ids are
           the doc's and await live qualification; a deployment's own K3 is reached through the
           gateway, defined by the operator (WORKBENCH_LLM_<ID>_URL), not this entry. */
        Provider kimi = p("moonshot", "Moonshot (Kimi)", "http", "https://api.moonshot.ai/v1",
                false, true, "kimi-k3", "kimi-k2.7-code", "kimi-k2.6");
        kimi.reasoningField = "reasoning_content";
        kimi.reasoningReplay = "all";
        providers.put("moonshot", kimi);
        providers.put("openrouter", p("openrouter", "OpenRouter", "http", "https://openrouter.ai/api",
                false, true, "openai/gpt-4o-mini", "anthropic/claude-sonnet-4", "google/gemini-2.0-flash-001"));
        providers.put("ollama", p("ollama", "Ollama (local)", "http", "http://127.0.0.1:11434",
                true, false, "llama3.1", "qwen2.5-coder"));
        providers.put("custom", customSeed());
        providers.put("fixture", p("fixture", "Fixture (canned, tests)", "http", "fixture://loop",
                true, false, "fixture-1"));
        providers.get("ollama").contextTokens = 128_000;   // local models: the one place a small window is the norm
        providers.get("fixture").contextTokens = 128_000;
        /* the fixture provider's scenarios (AgentLoop.fixtureClient): recorded-shape answers of a
           thinking provider with tools, reasoning-only answers, malformed bodies, an upstream
           failure, a slow endpoint — the provider-loop tests run the loop against them */
        providers.get("fixture").models = new ArrayList<>(List.of("fixture-1", "ds-tools", "ds-long", "plain-strips", "reasoning-only",
                "malformed", "upstream-500", "slow-tools", "big-results", "stream-tools"));
        envProviders();
    }

    /** Providers the operator defines in the environment — an inference gateway, an
     *  operator-defined route: WORKBENCH_LLM_&lt;ID&gt;_URL (required), _MODELS (comma-separated, the first
     *  is the default), _LABEL, _REASONING ("reasoning_content:all" — field and replay policy),
     *  _STREAM (1: SSE only), _MAXOUT (the endpoint's max_tokens cap), and _KEY (KeyStore). Such
     *  a provider is MANAGED: listed and usable, never edited or removed through the ops, and
     *  nothing a user saved under its id is read (a changed endpoint would carry the key). */
    private void envProviders() {
        java.util.regex.Pattern urlVar = java.util.regex.Pattern.compile("^WORKBENCH_LLM_([A-Z0-9_]+)_URL$");
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(System.getenv()).entrySet()) {
            java.util.regex.Matcher m = urlVar.matcher(e.getKey());
            if (!m.matches() || e.getValue() == null || e.getValue().isBlank()) continue;
            String id = m.group(1).toLowerCase(java.util.Locale.ROOT);
            if (!id.matches(SLUG)) continue;
            String pre = "WORKBENCH_LLM_" + m.group(1) + "_";
            String models = System.getenv(pre + "MODELS");
            List<String> list = new ArrayList<>();
            if (models != null) for (String x : models.split(",")) { String t = x.trim(); if (!t.isEmpty() && !list.contains(t)) list.add(t); }
            Provider pr = p(id, System.getenv(pre + "LABEL") == null || System.getenv(pre + "LABEL").isBlank() ? id : System.getenv(pre + "LABEL").trim(),
                    "http", e.getValue().trim(), false, true, list.toArray(new String[0]));
            String reasoning = System.getenv(pre + "REASONING");
            if (reasoning != null && !reasoning.isBlank()) {
                String[] parts = reasoning.trim().split(":", 2);
                pr.reasoningField = parts[0].trim();
                pr.reasoningReplay = parts.length > 1 && REPLAY_POLICIES.contains(parts[1].trim()) ? parts[1].trim() : "all";
            }
            String stream = System.getenv(pre + "STREAM");
            pr.streamOnly = stream != null && (stream.trim().equals("1") || stream.trim().equalsIgnoreCase("true"));
            String maxout = System.getenv(pre + "MAXOUT");
            if (maxout != null && maxout.trim().matches("\\d+")) pr.maxOutputTokens = Integer.parseInt(maxout.trim());
            pr.managed = true;
            providers.put(id, pr);   // the environment wins over a shipped entry of the same id
        }
    }

    private static Provider customSeed() {
        return p("custom", "Custom (OpenAI-compatible)", "http", "", false, true);
    }

    /** Merge user config: {providers: {id: {baseUrl?, model?}}} — edits known
     *  providers, adds new http ones. Never carries keys. */
    @SuppressWarnings("unchecked")
    private void mergeConfig() {
        if (!configFile.isFile()) return;
        try {
            Object parsed = Json.parse(Files.readString(configFile.toPath()));
            if (!(parsed instanceof Map)) {
                /* right file, wrong shape — park the evidence (was: silent fall
                   through to builtins, and the next persist made it permanent).
                   A FAILED park leaves it in place — mark degraded (fix-1-r5 #2) */
                if (!dev.workbench.registry.SidecarIO.parkCorrupt(configFile, "llm",
                        new IllegalStateException("expected a JSON object")))
                    configDegraded = true;
                return;
            }
            Object provs = ((Map<String, Object>) parsed).get("providers");
            if (!(provs instanceof Map)) return;      // no providers key = nothing to merge
            Map<String, Object> provMap = (Map<String, Object>) provs;
            for (Map.Entry<String, Object> e : provMap.entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                Map<String, Object> cfg = (Map<String, Object>) e.getValue();
                String pid = e.getKey();
                /* the legacy `claude` entry IS the Anthropic provider (A9): read it as
                   `anthropic` — unless an `anthropic` entry also exists, in which case the
                   newer one wins and the legacy one is ignored (logged). Remapped BEFORE the
                   pr == null branch so it can never become a user-added `claude`. */
                if (LEGACY_CLAUDE_ID.equals(pid)) {
                    if (provMap.containsKey("anthropic")) { System.err.println("[llm] llm.json: ignoring the legacy 'claude' entry — 'anthropic' is present"); continue; }
                    pid = "anthropic";
                }
                Provider pr = providers.get(pid);
                if (pr != null && pr.managed) continue;   // the operator's definition stands; nothing saved under its id is read
                if (pr == null) {                       // user-added provider: http custom
                    pr = p(pid, pid, "http", "", false, true);
                    providers.put(pid, pr);
                }
                boolean chooses = !authOptions(pid).isEmpty();
                /* A3: where the provider offers a connection choice, `auth` is what
                   persists — derive driver/baseUrl/needsKey from it FIRST (absent = cli),
                   and IGNORE a disk baseUrl/local (derived, never stored); the list and the
                   default merge afterwards as usual (the user's list for that mode) */
                if (chooses) {
                    Object a = cfg.get("auth");
                    String au = a == null ? "cli" : String.valueOf(a).trim();
                    if (!authOptions(pid).contains(au)) {   // a hand-edited typo must not silently revert the connection (review A9)
                        System.err.println("[llm] llm.json: " + pid + " auth=\"" + au + "\" is not one of " + authOptions(pid) + " — using cli");
                        au = "cli";
                    }
                    if (!au.equals(pr.auth)) applyAuth(pr, au);
                }
                if (cfg.get("label") != null) pr.label = String.valueOf(cfg.get("label"));
                if (!chooses && cfg.get("baseUrl") != null) pr.baseUrl = String.valueOf(cfg.get("baseUrl"));
                /* LOCAL is an http endpoint's property; an older llm.json carried
                   "local": true on the claude CLI entry (settings r1 H4). It also
                   decides the key requirement — re-derived HERE, not only in
                   configure(): a user-added LOCAL provider came back "no key" on
                   the next restart (settings r2 H1) */
                if (!chooses && cfg.get("local") != null && "http".equals(pr.driver)) {   // !chooses: a connection-choosing provider derives needsKey from auth, never from a disk `local` (A3)
                    pr.local = Boolean.TRUE.equals(cfg.get("local"));
                    pr.needsKey = !pr.local;
                }
                if (cfg.get("models") instanceof List<?> ml) {
                    List<String> models = new ArrayList<>();
                    for (Object o : ml) { String s = String.valueOf(o).trim(); if (!s.isEmpty()) models.add(s); }
                    /* an explicit [] on disk CLEARS — the user emptied a built-in's
                       list (persist now writes [] when it differs from the shipped
                       set). It must NOT be ignored, or the shipped list silently
                       returns at the next boot (review r-f31a574 M2 — the label-M3
                       bug's twin: accepted, read back, never written back). */
                    pr.models = models;
                    if (models.isEmpty()) pr.defaultModel = "";
                    else if (pr.defaultModel.isEmpty() || !pr.models.contains(pr.defaultModel)) pr.defaultModel = pr.models.get(0);
                }
                /* the list first, THEN the default: load must never fail, so an
                   out-of-list default in an older file is REPAIRED by appending it
                   to the list (the invariant is enforced on write — design §4) */
                if (cfg.get("model") != null) {
                    pr.defaultModel = String.valueOf(cfg.get("model"));
                    if (!pr.models.contains(pr.defaultModel)) pr.models.add(pr.defaultModel);
                }
                if (cfg.get("contextTokens") instanceof Number ct && ct.intValue() >= 8_192) pr.contextTokens = ct.intValue();
                if (cfg.get("reasoning") instanceof Map<?, ?> rz) {
                    pr.reasoningField = rz.get("field") == null ? "" : String.valueOf(rz.get("field")).trim();
                    String rp = rz.get("replay") == null ? "never" : String.valueOf(rz.get("replay")).trim();
                    pr.reasoningReplay = REPLAY_POLICIES.contains(rp) ? rp : "never";
                }
                if (cfg.get("streamOnly") != null) pr.streamOnly = Boolean.TRUE.equals(cfg.get("streamOnly"));
                if (cfg.get("maxOutputTokens") instanceof Number mo && mo.intValue() >= 0) pr.maxOutputTokens = mo.intValue();
                if (cfg.get("extraParams") instanceof Map<?, ?> ep) {
                    pr.extraParams = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> x : ep.entrySet())
                        pr.extraParams.put(String.valueOf(x.getKey()), x.getValue());
                }
            }
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption: routed by permanence — a
               charset-parked file is already aside; a transient one marks the
               store degraded (see persist) */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(configFile, "llm", e))
                configDegraded = true;
        } catch (Exception e) {
            /* was catch-all-silent (fix-1-r3): a torn llm.json read as
               "no user config" and the next persist overwrote it. A FAILED park
               leaves it in place — mark degraded (fix-1-r5 #2) */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(configFile, "llm", e))
                configDegraded = true;
        }
    }

    public synchronized Provider get(String id) {
        return providers.get(id);
    }

    /** Registered provider ids, in registration order — for error messages. */
    public synchronized List<String> ids() {
        return new ArrayList<>(providers.keySet());
    }

    public synchronized boolean isBuiltin(String id) { return builtinIds.contains(id); }

    /** a transient load failure left the on-disk file un-parked; composites refuse (§5) */
    public synchronized boolean isDegraded() { return configDegraded; }

    /** A concrete, resolvable {@code provider:model} example for a spec that did
     *  NOT resolve: if the spec is a bare model some provider offers, prefix that
     *  provider (the common agent mistake); otherwise name a real registered one. */
    public synchronized String exampleSpec(String modelSpec) {
        if (modelSpec != null && !modelSpec.isBlank())
            for (Provider pr : providers.values())
                if (pr.models.contains(modelSpec)) return pr.id + ":" + modelSpec;
        for (Provider pr : providers.values())
            if ("http".equals(pr.driver) && !pr.defaultModel.isEmpty())
                return pr.id + ":" + pr.defaultModel;
        for (Provider pr : providers.values())
            if (!pr.defaultModel.isEmpty()) return pr.id + ":" + pr.defaultModel;
        return "provider:model";
    }

    public boolean configured(Provider pr) {
        if ("builtin".equals(pr.driver)) return true;
        if ("cli".equals(pr.driver)) return claudePresent() && claudeSignedIn();   // B2: installed AND signed in — a ✓ must mean a mission would run
        if (pr.needsKey) return keys.has(pr.id);
        return true;
    }

    private static boolean claudePresent() { return claudeBinary() != null; }

    /** B2: "configured" for the Claude Code connection means SIGNED IN, not merely
     *  installed. `claude auth status --json` → {"loggedIn":…}, under the spawn's own
     *  credential policy (applyBillingEnv) so this answer IS the spawn's answer. Cached
     *  60s so list() never spawns the CLI per call; a 5s cap bounds the first stall. */
    private static volatile long signedInAt;
    private static volatile boolean signedInVal;
    /** One cache, several feeders (review A4): Doctor's own probe and a successful CLI
     *  llm.probe both PROVE the answer — they record it here so the card never lags
     *  behind a `claude auth login` by up to the cache TTL. */
    public static void noteSignedIn(boolean v) { signedInVal = v; signedInAt = System.currentTimeMillis(); }
    public static void bustSignedIn() { signedInAt = 0; }
    static synchronized boolean claudeSignedIn() {
        long now = System.currentTimeMillis();
        if (now - signedInAt < 60_000) return signedInVal;
        boolean v = false;
        String cli = claudeBinary();
        if (cli != null) {
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(cli, "auth", "status", "--json");
                dev.workbench.server.AgentManager.applyBillingEnv(pb.environment());
                p = pb.start();
                final Process proc = p;
                /* reader threads + waitFor + destroyForcibly — the cap must be a REAL cap even
                   when a grandchild keeps the pipe open (the bug Doctor.exec fixed as review M7
                   and LlmProbe.probeCli avoids; an unbounded readAllBytes here held this
                   monitor, the class monitor and AgentManager's — review A1). stdout only:
                   stderr is drained, never parsed (an update notice must not read as
                   "signed out" — review A3). */
                java.util.concurrent.atomic.AtomicReference<String> out = new java.util.concurrent.atomic.AtomicReference<>("");
                Thread ro = new Thread(() -> { try { out.set(new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {} }, "claude-auth-status");
                Thread re = new Thread(() -> { try { proc.getErrorStream().readAllBytes(); } catch (Exception ignored) {} }, "claude-auth-status-err");
                ro.setDaemon(true); re.setDaemon(true); ro.start(); re.start();
                if (proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    ro.join(1000);
                    v = proc.exitValue() == 0 && out.get().matches("(?s).*\"loggedIn\"\\s*:\\s*true.*");   // Doctor's tolerant rule — ONE parse rule for both call sites
                } else proc.destroyForcibly();
            } catch (Exception ignored) { v = false; if (p != null) p.destroyForcibly(); }
        }
        noteSignedIn(v);
        return v;
    }

    /** The claude CLI binary, or null: the native installer's ~/.local/bin, the brew /
     *  /usr/local paths, then PATH (npm-global and nvm installs on Linux live there).
     *  Shared by the CLI provider, the claude-mode spawn and system.doctor. */
    public static String claudeBinary() {
        List<String> cands = new ArrayList<>(List.of(System.getProperty("user.home") + "/.local/bin/claude",
                "/usr/local/bin/claude", "/opt/homebrew/bin/claude"));
        String path = System.getenv("PATH");
        if (path != null)
            for (String d : path.split(File.pathSeparator))
                if (d.startsWith("/")) cands.add(d + File.separator + "claude");   // absolute entries only: "." would resolve against the server cwd
        for (String p : cands) {
            File f = new File(p);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    /** llm.providers — the full list. configured/local only, NEVER key material. */
    public synchronized List<Object> list() {
        List<Object> out = new ArrayList<>();
        for (Provider pr : providers.values()) out.add(rowOf(pr));
        return out;
    }

    /** one provider's llm.providers row, or an empty map when unknown */
    public synchronized Map<String, Object> row(String id) {
        Provider pr = providers.get(id);
        return pr == null ? new LinkedHashMap<>() : rowOf(pr);
    }

    private Map<String, Object> rowOf(Provider pr) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", pr.id);
        row.put("label", pr.label);
        row.put("driver", pr.driver);
        row.put("baseUrl", pr.baseUrl);
        row.put("models", new ArrayList<>(pr.models));
        row.put("model", pr.defaultModel);
        row.put("local", pr.local);
        row.put("needsKey", pr.needsKey);
        row.put("configured", configured(pr));
        row.put("builtin", builtinIds.contains(pr.id));
        row.put("extraParams", new LinkedHashMap<>(pr.extraParams));
        row.put("contextTokens", pr.contextTokens);
        row.put("reasoning", new LinkedHashMap<>(Map.of("field", pr.reasoningField, "replay", pr.reasoningReplay)));
        if (pr.streamOnly) row.put("streamOnly", true);
        if (pr.maxOutputTokens > 0) row.put("maxOutputTokens", pr.maxOutputTokens);
        if (pr.managed) row.put("managed", true);
        List<String> ao = authOptions(pr.id);
        if (!ao.isEmpty()) {                                          // only where there IS a choice (#6): ollama never carries a bogus auth:"api"
            row.put("auth", pr.auth);
            row.put("authOptions", new ArrayList<>(ao));
            if ("cli".equals(pr.driver)) { row.put("installed", claudePresent()); row.put("signedIn", claudeSignedIn()); }   // B2/B10: "not installed" vs "installed, not signed in"
            row.put("keyStored", keys.hasStored(pr.id));   // a key stored while the CLI connection is active must stay reachable by CLEAR KEY (review B10); membership only — never the key
        }
        return row;
    }

    /** two ids that map to the same WORKBENCH_LLM_…_KEY variable (zai-x / zai_x) would
     *  share one env override — refused for a new or renamed id (design §6.1/§6.2, L3) */
    private void envClash(String id) throws OpException {
        String env = KeyStore.envName(id);
        for (Provider other : providers.values())
            if (!other.id.equals(id) && KeyStore.envName(other.id).equals(env))
                throw OpException.syntax("provider id " + id + "'s key variable " + env
                        + " is shared with provider " + other.id + " — pick a distinct id", "id=" + id);
    }

    /** The connection choices a provider offers — more than one only for anthropic (#6). */
    static List<String> authOptions(String id) {
        return "anthropic".equals(id) ? List.of("cli", "api") : List.of();
    }

    /** Derive driver / baseUrl / needsKey / the seed list from `auth` (anthropic). The
     *  list is RE-SEEDED — the two id-spaces are disjoint (CLI aliases vs API ids), so a
     *  list cannot carry across; the default follows the list. `local` is meaningless
     *  here (A2). Pure memory: callers persist (or revert). */
    static void applyAuth(Provider pr, String auth) {
        pr.auth = auth;
        bustSignedIn();   // a connection switch must re-probe sign-in on the next list, not serve a stale ✓ (review A4)
        pr.local = false;
        if ("api".equals(auth)) {
            pr.driver = "http"; pr.baseUrl = ANTHROPIC_API_BASE; pr.needsKey = true;
            pr.models = new ArrayList<>(ANTHROPIC_API_MODELS);
        } else {
            pr.driver = "cli"; pr.baseUrl = ""; pr.needsKey = false;
            pr.models = new ArrayList<>(ANTHROPIC_CLI_MODELS);
        }
        pr.defaultModel = pr.models.get(0);
    }

    /** llm.configure {id, baseUrl?, model?, label?, models?, local?, extraParams?, create?, auth?}
     *  — edit a provider, or CREATE a new OpenAI-compatible (http) one when
     *  create:true; an unknown id without it is E_NOTFOUND (the create gate — a
     *  stale SAVE or MCP client must not resurrect a renamed-away id, review H4).
     *  The slug rule is checked BEFORE the gate so an invalid id is E_SYNTAX either
     *  way. `models` replaces the pick list; `model` sets the default and, when
     *  `models` is given, MUST be one of them (E_SYNTAX — no silent adoption);
     *  without `models` a new `model` is adopted and appended (the MCP one-liner).
     *  `auth` (anthropic: cli|api) travels ALONE — combined with models/model/baseUrl/
     *  local it is E_SYNTAX (A1); it re-derives the endpoint and re-seeds the list.
     *  Validate → snapshot → apply → persist → revert+throw on a failed write (§5).
     *  Returns the provider row as re-listed. */
    /** The loop learned the model's real window from the provider's refusal: adopt it for the
     *  provider so the next mission starts right (a smaller value only ever comes from the provider). */
    public synchronized void setContextTokens(String id, int n) throws OpException {
        Provider pr = providers.get(id);
        if (pr == null || n < 8_192 || pr.contextTokens == n) return;
        int old = pr.contextTokens;
        pr.contextTokens = n;
        if (!persist()) { pr.contextTokens = old; throw OpException.io("llm.json was not written — the learned window is not saved", "id=" + id); }
    }

    public synchronized Map<String, Object> configure(String id, String baseUrl, String model,
            String label, List<String> models, Boolean local, Map<String, Object> extraParams,
            Boolean create, String auth, Integer contextTokens) throws OpException {
        if (id == null || id.isBlank())
            throw OpException.syntax("llm.configure needs a provider id", "");
        if (providers.get(id) != null && providers.get(id).managed)
            throw OpException.scope("provider " + id + " is defined by the operator (the environment) — it cannot be changed here", "managed=" + id);
        if (contextTokens != null && contextTokens < 8_192)
            throw OpException.syntax("contextTokens is the model's context window in tokens — at least 8192", "contextTokens=" + contextTokens);
        String key = id.trim();
        Provider pr = providers.get(key);
        boolean created = false;
        String newAuth = auth == null || auth.isBlank() ? null : auth.trim();
        // ---- validate (nothing mutated yet)
        if (pr == null) {
            if (!key.matches(SLUG))                                   // slug before the gate (M10aIT:569)
                throw OpException.syntax("provider id: lowercase letters, digits, - and _ (max 32)", "id=" + id);
            if (LEGACY_CLAUDE_ID.equals(key))                         // reserved: the alias for anthropic must never be shadowed (A4)
                throw OpException.syntax("the id \"claude\" is reserved — it is the legacy name of the Anthropic provider (id anthropic)", "id=" + id);
            if (!Boolean.TRUE.equals(create))
                throw OpException.notFound("provider not found: " + key + " — pass create:true to add a new provider",
                        "registered: " + String.join(", ", providers.keySet()));
            envClash(key);
        }
        if (newAuth != null) {
            if (!authOptions(key).contains(newAuth))
                throw OpException.unsupported("only Anthropic offers a choice of connection (cli = your Claude Code sign-in, api = an API key)"
                        + (authOptions(key).isEmpty() ? " — " + key + " has none" : " — auth=" + newAuth + " is not one of " + authOptions(key)), "id=" + key);
            if (models != null || model != null || baseUrl != null || local != null)
                throw OpException.syntax("auth travels alone — send {id, auth} by itself, then edit the list or endpoint in a second call (a switch re-seeds them)", "id=" + key);
        }
        if (local != null && !authOptions(key).isEmpty())
            throw OpException.unsupported("LOCAL does not apply to " + key + " — its key requirement follows its connection (auth)", "id=" + key);
        if (baseUrl != null && !authOptions(key).isEmpty())   // derived from auth and never persisted — accepting it here made memory and disk diverge (review A2, §5)
            throw OpException.unsupported("the endpoint of " + key + " follows its connection (auth) — it cannot be set", "id=" + key);
        List<String> clean = null;
        if (models != null) {
            clean = new ArrayList<>();
            for (String s : models) { String t = s == null ? "" : s.trim(); if (!t.isEmpty() && !clean.contains(t)) clean.add(t); }
        }
        String newDefault = model == null || model.isBlank() ? null : model.trim();
        if (newDefault != null && clean != null && !clean.contains(newDefault))
            throw OpException.syntax("the default must be one of the listed models",
                    "model=" + newDefault + " models=" + clean);
        // ---- snapshot
        Provider snap = null;
        if (pr == null) {
            pr = p(key, label == null || label.isBlank() ? key : label.trim(), "http", "", false, true);
            providers.put(key, pr);
            created = true;
        } else {
            snap = copy(pr);
        }
        // ---- apply
        if (newAuth != null && !newAuth.equals(pr.auth)) applyAuth(pr, newAuth);   // re-derives driver/baseUrl/needsKey, re-seeds the list (re-setting the current mode is a no-op)
        if (label != null && !label.isBlank()) pr.label = label.trim();
        if (baseUrl != null) pr.baseUrl = baseUrl.trim();
        if (clean != null) {
            pr.models = clean;                                   // an explicit empty list CLEARS (r2 L2)
            if (clean.isEmpty()) pr.defaultModel = "";
        }
        if (extraParams != null) pr.extraParams = new LinkedHashMap<>(extraParams);   // a vendor extra (DeepSeek's thinking) is configurable too (r2 L3)
        if (contextTokens != null) pr.contextTokens = contextTokens;
        if (newDefault != null) {
            pr.defaultModel = newDefault;
            if (!pr.models.contains(newDefault)) pr.models.add(newDefault);   // models absent: adopt-and-append
        } else if (pr.defaultModel.isEmpty() || !pr.models.contains(pr.defaultModel)) {
            pr.defaultModel = pr.models.isEmpty() ? "" : pr.models.get(0);
        }
        if (local != null && "http".equals(pr.driver)) {
            pr.local = local;
            /* LOCAL means "loopback, no key needed" (the form's own words): the
               key requirement follows the flag for every http provider — the
               shipped ones already satisfy needsKey == !local, and un-ticking
               LOCAL on ollama must ask for a key (settings r1 H1, r2 M3) */
            pr.needsKey = !local;
        }
        // ---- persist, or revert
        if (!persist()) {
            if (created) providers.remove(key); else restore(pr, snap);
            throw OpException.io("llm.json was not written — the provider was not saved (evidence parked; see server log)",
                    configFile.getPath());
        }
        return rowOf(pr);
    }

    private static Provider copy(Provider pr) {
        Provider c = new Provider();
        c.id = pr.id; c.label = pr.label; c.driver = pr.driver; c.baseUrl = pr.baseUrl;
        c.defaultModel = pr.defaultModel; c.models = new ArrayList<>(pr.models);
        c.local = pr.local; c.needsKey = pr.needsKey; c.extraParams = new LinkedHashMap<>(pr.extraParams);
        c.auth = pr.auth;
        return c;
    }

    private static void restore(Provider pr, Provider snap) {
        pr.label = snap.label; pr.baseUrl = snap.baseUrl; pr.defaultModel = snap.defaultModel;
        pr.models = snap.models; pr.local = snap.local; pr.needsKey = snap.needsKey; pr.extraParams = snap.extraParams;
        pr.driver = snap.driver; pr.auth = snap.auth;   // applyAuth mutates these — a failed persist must put them back (§5)
    }

    /** llm.remove {id} — a user-added provider and its key go; a built-in one
     *  is edited, never removed (its id is what the task settings and the
     *  mission cards refer to). Registry persist FIRST, then the key (§5): a
     *  failed write reverts and throws with nothing destroyed; the key of a
     *  surviving provider is never lost. */
    public synchronized Map<String, Object> remove(String rawId) throws OpException {
        if (rawId == null || rawId.isBlank())
            throw OpException.syntax("llm.remove needs a provider id", "");
        String id = rawId.trim();
        if (builtinIds.contains(id))
            throw OpException.unsupported(
                    "built-in provider " + id + " cannot be removed — edit it instead", "");
        Provider gone = providers.get(id);
        if (gone == null)
            throw OpException.notFound("provider not found: " + id,
                    "registered: " + String.join(", ", providers.keySet()));
        Map<String, Provider> before = new LinkedHashMap<>(providers);
        providers.remove(id);
        if (!persist()) {
            providers.clear(); providers.putAll(before);
            throw OpException.io("llm.json was not written — the provider was not removed (evidence parked; see server log)",
                    configFile.getPath());
        }
        try {
            keys.set(id, null);
        } catch (OpException e) {   // the provider is already gone; the key was not DELETED — not "not saved" (review r-f31a574 L6)
            throw OpException.io("the provider " + id + " was removed, but its stored key could not be deleted — it is an orphan"
                    + " under " + id + " (evidence parked; see server log): llm.setkey {id:\"" + id + "\", key:\"\"} clears it", "");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("removed", true);
        return out;
    }

    /** The rename refusals that are the registry's to know — nothing mutated
     *  (design §6.2; the composite checks the keyring and degraded state itself). */
    public synchronized void checkRename(String id, String newId) throws OpException {
        if (id == null || id.isBlank() || newId == null || newId.isBlank())
            throw OpException.syntax("llm.rename needs id and newId", "");
        String from = id.trim(), to = newId.trim();
        if (!to.matches(SLUG))
            throw OpException.syntax("provider id: lowercase letters, digits, - and _ (max 32)", "newId=" + newId);
        if (LEGACY_CLAUDE_ID.equals(to))                             // reserved: the legacy alias of anthropic must never be shadowed (A4)
            throw OpException.syntax("the id \"claude\" is reserved — it is the legacy name of the Anthropic provider (id anthropic)", "newId=" + to);
        if (from.equals(to))
            throw OpException.syntax("newId must differ from id", "id=" + from);
        if (!providers.containsKey(from))
            throw OpException.notFound("provider not found: " + from,
                    "registered: " + String.join(", ", providers.keySet()));
        if (builtinIds.contains(from) && !"custom".equals(from))
            throw OpException.unsupported("built-in provider " + from + " cannot be renamed — its id is referenced by the workbench", "");
        if (providers.containsKey(to))
            throw OpException.syntax("a provider is already registered as " + to, "newId=" + to);
        envClash(to);
    }

    /** The registry's part of a rename: the entry moves to newId AT THE SAME
     *  POSITION (the map is rebuilt, not remove+put — persist/list/ids keep their
     *  order, review H7), its id updated; a renamed-from-custom label that still
     *  reads "Custom (OpenAI-compatible)" becomes the new id. Persist → revert +
     *  throw on failure. The caller (LlmComposites) has already validated and
     *  moved the key; this is also the UNDO primitive (applyRename(newId, id)),
     *  so it performs no policy checks beyond existence and collision. */
    public synchronized void applyRename(String from, String to) throws OpException {
        Provider pr = providers.get(from);
        if (pr == null) throw OpException.notFound("provider not found: " + from, "");
        if (providers.containsKey(to)) throw OpException.syntax("a provider is already registered as " + to, "");
        Map<String, Provider> before = new LinkedHashMap<>(providers);
        String prevId = pr.id, prevLabel = pr.label;
        Map<String, Provider> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, Provider> e : providers.entrySet())
            rebuilt.put(e.getKey().equals(from) ? to : e.getKey(), e.getValue());
        pr.id = to;
        if (builtinIds.contains(from) && pr.label.equals(builtinLabels.get(from))) pr.label = to;   // forward: custom's shipped label follows the new id
        else if (builtinIds.contains(to) && pr.label.equals(from)) pr.label = builtinLabels.get(to);  // undo (applyRename(newId, custom)) restores the shipped label — else "nothing changed" was a lie (review r-f31a574 M5)
        providers.clear(); providers.putAll(rebuilt);
        if (!persist()) {
            pr.id = prevId; pr.label = prevLabel;
            providers.clear(); providers.putAll(before);
            throw OpException.io("llm.json was not written (evidence parked; see server log)", configFile.getPath());
        }
    }

    /** After `custom` was renamed away, put the shipped empty `custom` back at
     *  once (it would otherwise reappear only at the next boot — design §6.2).
     *  Pure memory: an empty built-in is never written by persist(). */
    public synchronized void reseedCustom() {
        if (!providers.containsKey("custom")) providers.put("custom", customSeed());
    }

    /** true = durable on disk (§5). */
    private boolean persist() {
        Map<String, Object> provs = new LinkedHashMap<>();
        for (Provider pr : providers.values()) {
            if (pr.managed) continue;   // defined by the environment, every boot: nothing of it is written
            Map<String, Object> cfg = new LinkedHashMap<>();
            boolean chooses = !authOptions(pr.id).isEmpty();
            if (chooses && !"cli".equals(pr.auth)) cfg.put("auth", pr.auth);   // the connection is what persists; cli is the shipped default (llm-anthropic-vendor)
            if (!chooses && !pr.baseUrl.isEmpty()) cfg.put("baseUrl", pr.baseUrl);   // a choosing provider's endpoint is DERIVED from auth, never stored (A3)
            if (!pr.defaultModel.isEmpty()) cfg.put("model", pr.defaultModel);
            /* LOCAL is an http property (mergeConfig ignores it elsewhere), written
               BOTH ways: an absent key means the shipped value, and un-ticking it
               on ollama must survive a restart (settings r3 M1) */
            if ("http".equals(pr.driver) && pr.local != shippedLocal(pr.id)) cfg.put("local", pr.local);
            if (!pr.extraParams.isEmpty()) cfg.put("extraParams", new LinkedHashMap<>(pr.extraParams));
            cfg.put("contextTokens", pr.contextTokens);   // always explicit: a seed default can change between versions
            if (!pr.reasoningField.isEmpty() || !"never".equals(pr.reasoningReplay)) cfg.put("reasoning", new LinkedHashMap<>(Map.of("field", pr.reasoningField, "replay", pr.reasoningReplay)));
            if (pr.streamOnly) cfg.put("streamOnly", true);
            if (pr.maxOutputTokens > 0) cfg.put("maxOutputTokens", pr.maxOutputTokens);
            /* a user-added provider is nothing but its config: label + list
               travel with it; a built-in's list — or label (review M3: an edited
               built-in label was accepted, read back, and never written) — is
               written only when edited */
            boolean userAdded = !builtinIds.contains(pr.id);
            if (userAdded || !pr.label.equals(builtinLabels.get(pr.id))) cfg.put("label", pr.label);
            /* write the list whenever it differs from the shipped set — INCLUDING an
               emptied built-in (models:[]), or the clear silently reverts at the next
               boot (review r-f31a574 M2). A user-added provider always differs (no
               shipped set), so its list — empty or not — always travels. */
            /* the shipped set is the CONNECTION's seed for a choosing provider — the constructor
               snapshot is the cli list, so an untouched API list read as "user-edited" and froze
               forever on disk (review A5) */
            List<String> shipped = chooses ? ("api".equals(pr.auth) ? ANTHROPIC_API_MODELS : ANTHROPIC_CLI_MODELS) : builtinModels.get(pr.id);
            if (userAdded || !pr.models.equals(shipped))
                cfg.put("models", new ArrayList<>(pr.models));
            if (!cfg.isEmpty()) provs.put(pr.id, cfg);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("providers", provs);
        /* atomic + loud via the shared sidecar discipline (was: plain
           writeString, catch-all silent — a torn write or a quiet IO failure
           dropped the user's provider config) */
        if (configDegraded) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) — a FALSE here, not a silent return */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(configFile, "llm"))
                return false;
            configDegraded = false;
        }
        return dev.workbench.registry.SidecarIO.writeAtomic(configFile, Json.write(root), "llm");
    }

    /** Resolve a mission model spec: "provider:model" → [provider, model];
     *  bare "provider" → its default model; unknown → null. builtin:triage and
     *  claude-code resolve to their providers with the model untouched. */
    /** llm.configure {reasoningField, reasoningReplay, streamOnly, maxOutputTokens}: the wire contract of
     *  a provider (Provider.reasoningField …). Absent = keep; validated, persisted, reverted on a
     *  failed write like every mutator (§5). */
    public synchronized Map<String, Object> configureWire(String id, String reasoningField, String reasoningReplay,
            Boolean streamOnly, Integer maxOutputTokens) throws OpException {
        Provider pr = providers.get(id);
        if (pr == null) throw OpException.notFound("unknown provider: " + id, "ids=" + ids());
        if (pr.managed) throw OpException.scope("provider " + id + " is defined by the operator (the environment) — it cannot be changed here", "managed=" + id);
        if (reasoningReplay != null && !REPLAY_POLICIES.contains(reasoningReplay.trim()))
            throw OpException.syntax("reasoningReplay must be one of " + REPLAY_POLICIES, "reasoningReplay=" + reasoningReplay);
        if (maxOutputTokens != null && maxOutputTokens < 0)
            throw OpException.syntax("maxOutputTokens is the endpoint's cap on max_tokens (0 = none)", "maxOutputTokens=" + maxOutputTokens);
        String f0 = pr.reasoningField, r0 = pr.reasoningReplay; boolean s0 = pr.streamOnly; int m0 = pr.maxOutputTokens;
        if (reasoningField != null) pr.reasoningField = reasoningField.trim();
        if (reasoningReplay != null) pr.reasoningReplay = reasoningReplay.trim();
        if (streamOnly != null) pr.streamOnly = streamOnly;
        if (maxOutputTokens != null) pr.maxOutputTokens = maxOutputTokens;
        if (!pr.reasoningField.isEmpty() && "never".equals(pr.reasoningReplay) && reasoningReplay == null)
            pr.reasoningReplay = "all";   // a field named with no policy: the one that loses nothing
        if (!persist()) {
            pr.reasoningField = f0; pr.reasoningReplay = r0; pr.streamOnly = s0; pr.maxOutputTokens = m0;
            throw OpException.io("llm.json could not be written — the change was not kept", configFile.getPath());
        }
        return rowOf(pr);
    }

    public synchronized String[] resolve(String modelSpec) {
        if (modelSpec == null || modelSpec.isBlank()) return null;
        String pid = modelSpec, model = null;
        int cut = modelSpec.indexOf(':');
        if (cut > 0) {
            pid = modelSpec.substring(0, cut);
            model = modelSpec.substring(cut + 1);
        }
        if (LEGACY_CLAUDE_ID.equals(pid)) pid = "anthropic";        // a stored/typed `claude:<m>` or bare `claude` keeps resolving (the id itself is reserved — A4)
        Provider pr = providers.get(pid);
        if (pr == null) {
            /* "claude-code" is the CLI's own-default sentinel: resolvable however
               the user reorders the anthropic list (settings r1 LAT-4) */
            if ("claude-code".equals(modelSpec) && providers.containsKey("anthropic"))
                return new String[] { "anthropic", "claude-code" };
            /* fallback: the whole spec is a provider's DEFAULT model (builtin:triage).
               Deliberately not "any model some provider lists": a bare model name
               is the documented agent mistake and must stay a loud block
               (agent-error-ux change 1; settings r2 H2 — the wider rule silently
               resolved deepseek-v4-flash and turned M7IT into a billed call) */
            for (Provider cand : providers.values())
                if (modelSpec.equals(cand.defaultModel))
                    return new String[] { cand.id, cand.defaultModel };
            return null;
        }
        if (model == null || model.isBlank()) model = pr.defaultModel;
        /* "custom:" — a provider with no model at all is not a resolvable spec
           (settings r1 M7): the spawn guard and task.configure refuse it loudly
           instead of an empty "model" reaching the endpoint */
        if (model == null || model.isBlank()) return null;
        return new String[] { pid, model };
    }
}
