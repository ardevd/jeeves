package dev.workbench.llm;

import dev.workbench.registry.OpException;
import dev.workbench.server.AgentManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * llm.probe {id, model?} — a WET test of one provider+model: does the key work, is
 * there credit, does the provider actually reply. One tiny completion ("reply OK",
 * max_tokens 64 so a thinking model has room), or for the Claude Code login a bare
 * `claude -p`. The server makes the call with the stored key; the KEY is never in the
 * result. `ok` means the provider answered a well-formed completion — the exact text is
 * reported, not asserted. Not a mission: no tools, no MCP bridge, no budget.
 */
public final class LlmProbe {
    private LlmProbe() {}

    private static final String PROMPT = "Reply with the single word OK.";
    private static final long HTTP_TIMEOUT_S = 45, CLI_TIMEOUT_S = 90;

    public static Map<String, Object> probe(LlmRegistry registry, KeyStore keys, String id, String model) throws OpException {
        LlmRegistry.Provider pr = registry.get(id == null ? "" : id.trim());
        if (pr == null) throw OpException.notFound("provider not found: " + id, "");
        if ("builtin".equals(pr.driver)) throw OpException.unsupported("the scripted agent is not an LLM — nothing to probe", "");
        if ("fixture".equals(pr.id)) throw OpException.unsupported("fixture is the canned test provider — nothing real to probe", "");
        String m = model == null || model.isBlank() ? pr.defaultModel : model.trim();
        if (m == null || m.isBlank()) throw OpException.syntax("provider " + pr.id + " has no model to probe — pass model or set a default", "");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", pr.id); out.put("label", pr.label); out.put("driver", pr.driver); out.put("model", m);
        long t0 = System.nanoTime();
        try {
            if ("cli".equals(pr.driver)) probeCli(m, out);
            else probeHttp(pr, keys, m, out);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
        if (!Boolean.TRUE.equals(out.get("ok")) && out.get("error") != null) {
            String h = hint(pr.driver, String.valueOf(out.get("error")));
            if (!h.isEmpty()) out.put("hint", h);
        }
        return out;
    }

    // ---- OpenAI-compatible http: one completion through the same client missions use
    @SuppressWarnings("unchecked")
    private static void probeHttp(LlmRegistry.Provider pr, KeyStore keys, String m, Map<String, Object> out) throws Exception {
        if (pr.baseUrl == null || pr.baseUrl.isBlank()) throw new IllegalStateException("no baseUrl configured");
        if (pr.needsKey && !keys.has(pr.id)) throw new IllegalStateException("no key stored (and no " + KeyStore.envName(pr.id) + " in the environment)");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", m);
        req.put("messages", List.of(Map.of("role", "user", "content", PROMPT)));
        req.put("max_tokens", 64);
        /* no sampling params: the mission loop sends none, and some models reject them
           (Moonshot kimi-k3: "only temperature 1 is allowed" — found by this very probe).
           A provider-required param belongs in extraParams, which is merged below. */
        req.putAll(pr.extraParams);                                   // probe the provider AS CONFIGURED (e.g. deepseek thinking)
        AgentLoop.ChatClient client = AgentLoop.openAiClient(pr.baseUrl, keys.get(pr.id));
        AtomicReference<Map<String, Object>> resp = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        Thread t = new Thread(() -> { try { resp.set(client.chat(req)); } catch (Exception e) { err.set(e); } }, "llm-probe-" + pr.id);
        t.setDaemon(true); t.start();
        t.join(TimeUnit.SECONDS.toMillis(HTTP_TIMEOUT_S));
        if (t.isAlive()) { t.interrupt(); throw new IllegalStateException("no reply within " + HTTP_TIMEOUT_S + "s"); }
        if (err.get() != null) throw err.get();
        Map<String, Object> r = resp.get();
        Object choices = r == null ? null : r.get("choices");
        if (!(choices instanceof List<?> cl) || cl.isEmpty()) throw new IllegalStateException("well-formed HTTP 2xx but no choices[] in the reply: " + brief(String.valueOf(r)));
        Map<String, Object> msg = (Map<String, Object>) ((Map<String, Object>) cl.get(0)).get("message");
        String content = msg == null || msg.get("content") == null ? "" : String.valueOf(msg.get("content")).trim();
        String reasoning = msg == null || msg.get("reasoning_content") == null ? "" : String.valueOf(msg.get("reasoning_content")).trim();
        boolean empty = content.isEmpty() && reasoning.isEmpty();
        out.put("ok", !empty);   // a well-formed choice with NOTHING in it is not a reply (review A10)
        if (empty) out.put("error", "the endpoint returned a choice with no content (max_tokens 64 — a thinking model may need more)");
        out.put("reply", brief(content.isEmpty() ? (reasoning.isEmpty() ? "(empty)" : "(reasoning only) " + reasoning) : content));
        if (r.get("usage") != null) out.put("usage", r.get("usage"));
        if (r.get("model") != null) out.put("servedModel", r.get("model"));   // what the endpoint says it ran — catches silent aliasing
    }

    // ---- Claude Code login: a bare `claude -p`, same credential policy as a spawn
    private static void probeCli(String m, Map<String, Object> out) throws Exception {
        String cli = LlmRegistry.claudeBinary();
        if (cli == null) throw new IllegalStateException("claude CLI not installed (not on PATH)");
        /* a probe is not a mission: an EMPTY, strict MCP config so the CLI loads none of the
           user's global servers (a bare `claude -p` would — and MCP init is where it can
           hang; review A6). The temp cwd keeps repo CLAUDE.md out; this keeps ~/.claude.json out. */
        File mcp = null, cwd = null;
        try {
        mcp = File.createTempFile("llm-probe-mcp", ".json");
        Files.writeString(mcp.toPath(), "{\"mcpServers\":{}}");
        List<String> cmd = new ArrayList<>(List.of(cli, "-p", PROMPT, "--output-format", "text",
                "--strict-mcp-config", "--mcp-config", mcp.getAbsolutePath()));
        if (!"claude-code".equals(m)) { cmd.add("--model"); cmd.add(m); }   // claude-code = the CLI's own default (no flag)
        ProcessBuilder pb = new ProcessBuilder(cmd);
        out.put("billing", AgentManager.applyBillingEnv(pb.environment()));
        cwd = Files.createTempDirectory("llm-probe").toFile();      // no repo CLAUDE.md / project context bleeding in
        pb.directory(cwd);
        Process p = pb.start();
        AtomicReference<String> so = new AtomicReference<>(""), se = new AtomicReference<>("");
        Thread ro = new Thread(() -> { try { so.set(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)); } catch (Exception ignored) {} });
        Thread re = new Thread(() -> { try { se.set(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)); } catch (Exception ignored) {} });
        ro.setDaemon(true); re.setDaemon(true); ro.start(); re.start();
        if (!p.waitFor(CLI_TIMEOUT_S, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("claude -p produced no reply within " + CLI_TIMEOUT_S + "s"); }
        ro.join(2000); re.join(2000);
        String stdout = so.get().trim(), stderr = se.get().trim();
        if (p.exitValue() != 0 || stdout.isEmpty())
            throw new IllegalStateException("claude exit " + p.exitValue() + (stderr.isEmpty() ? (stdout.isEmpty() ? " (no output)" : ": " + brief(stdout)) : ": " + brief(stderr)));
        out.put("ok", true);
        out.put("reply", brief(stdout));
        LlmRegistry.noteSignedIn(true);   // a reply through the CLI PROVES sign-in — feed the card's cache (review A4)
        } finally {
            /* nothing of a probe outlives it: the config and the scratch cwd go (they used to pile
               up in the JVM temp dir — 2026-09-15) */
            if (mcp != null) try { Files.deleteIfExists(mcp.toPath()); } catch (Exception ignored) {}
            if (cwd != null) try (var w = Files.walk(cwd.toPath())) { for (java.nio.file.Path q : w.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(q); } catch (Exception ignored) {}
        }
    }

    private static String brief(String s) { s = s == null ? "" : s.replaceAll("\\s+", " ").trim(); return s.length() > 200 ? s.substring(0, 200) + "…" : s; }

    /** A one-line diagnosis from the error text — the thing the operator acts on. Split by
     *  connection (review A7): a CLI "Invalid API key · run /login" means sign in, never
     *  "replace the stored key" (there is none); an http auth failure never says `claude`. */
    private static String hint(String driver, String e) {
        String l = e.toLowerCase();
        if ("cli".equals(driver)) {
            if (l.contains("not installed")) return "install the claude CLI (curl -fsSL https://claude.ai/install.sh | bash), then `claude auth login`";
            if (l.contains("invalid api key") || l.contains("/login") || l.contains("not logged in") || l.contains("unauthorized") || l.contains("authentication")) return "run `claude auth login` on this machine";
            if (l.contains("no reply within") || l.contains("no output")) return "the CLI produced nothing — run `claude auth status`, then try `claude -p hi` by hand";
            return "";
        }
        if (l.contains("no key stored")) return "add a key on the provider card";
        if (l.contains("http 401") || l.contains("http 403") || l.contains("invalid api key") || l.contains("unauthorized") || l.contains("authentication") || l.contains("authorization header")) return "key rejected — check/replace the stored key";
        if (l.contains("http 402") || l.contains("insufficient") || l.contains("balance") || l.contains("quota") || l.contains("credit") || l.contains("billing")) return "no credit / quota exhausted on this account";
        if (l.contains("try pulling") || l.contains("ollama pull")) return "the local server has no such model — `ollama pull <model>`";
        if (l.contains("http 404") || l.contains("model_not_found") || l.contains("does not exist") || l.contains("not found")) return "model id or endpoint path not found — fix the model list (↻ fetch) or the baseUrl";
        if (l.contains("http 429") || l.contains("rate limit")) return "rate limited — try again shortly";
        if (l.contains("connectexception") || l.contains("connection refused") || l.contains("unreachable") || l.contains("no route") || l.contains("unresolvedaddress")) return "provider unreachable — is it running / is the baseUrl right?";
        if (l.contains("no reply within")) return "timed out — provider slow or hung";
        return "";
    }
}
