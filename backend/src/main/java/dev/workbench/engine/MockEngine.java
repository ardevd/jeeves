package dev.workbench.engine;

import dev.workbench.registry.OpException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock engine serving the v7 mockup scene (design/mockups/vocab/v7-helio-dock.html):
 * project "privacy-invite-flow", targets gmail.apk (JEB), gmail.apk (JADX),
 * libgmail.so (IDA), wallet.apk (JADX). Fixtures are Java data, no files.
 */
public final class MockEngine implements Engine {

    // ------------------------------------------------------------ target model

    public record Target(String id, String name, String path, String engine, String profile) {}

    public static final List<Target> SCENE_TARGETS = List.of(
            new Target("t1", "gmail.apk", "/samples/gmail.apk", "JEB", "gmail"),
            new Target("t2", "gmail.apk", "/samples/gmail.apk", "JADX", "gmail"),
            new Target("t3", "libgmail.so", "/samples/libgmail.so", "IDA", "native"),
            new Target("t4", "wallet.apk", "/samples/wallet.apk", "JADX", "wallet"));

    private final Target target;
    private final Map<String, Object> caps;
    private final Map<String, String> renames = new LinkedHashMap<>();
    private final Map<String, String> comments = new LinkedHashMap<>();

    public MockEngine(Target target) {
        this.target = target;
        this.caps = switch (target.profile()) {
            case "gmail" -> target.engine().equals("JEB")
                    ? caps(true, true, true, true, true, true, true, true, "native")
                    : caps(true, false, true, true, true, false, true, false, "sidecar"); // JADX
            case "native" -> caps(true, false, true, true, true, true, true, true, "sidecar"); // IDA bridge
            default -> caps(true, false, true, true, true, false, true, false, "sidecar"); // wallet JADX
        };
    }

    private static Map<String, Object> caps(boolean decompile, boolean smali, boolean xrefs,
            boolean rename, boolean comment, boolean callgraph, boolean strings, boolean runs,
            String persist) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decompile", decompile);
        m.put("smali", smali);
        m.put("xrefs", xrefs);
        m.put("rename", rename);
        m.put("comment", comment);
        m.put("callgraph", callgraph);
        m.put("strings", strings);
        m.put("runs", runs);
        m.put("persist", persist);
        return m;
    }

    private OpException unsupported(String what) {
        return OpException.unsupported(
                what + " not supported by mock engine " + target.engine() + " for " + target.name(),
                "engine=" + target.engine() + " profile=" + target.profile());
    }

    private void requireCap(String cap, String what) throws OpException {
        Object v = caps.get(cap);
        if (!Boolean.TRUE.equals(v)) throw unsupported(what + " (cap '" + cap + "')");
    }

    public Target target() { return target; }

    @Override
    public Map<String, Object> caps() { return new LinkedHashMap<>(caps); }

    @Override
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", target.id());
        m.put("name", target.name());
        m.put("path", target.path());
        m.put("engine", target.engine());
        m.put("classes", switch (target.profile()) {
            case "gmail" -> 4821;
            case "native" -> 0;
            default -> 312;
        });
        m.put("heap", switch (target.profile()) {
            case "gmail" -> "3.2g";
            case "native" -> "0.9g";
            default -> "0.4g";
        });
        m.put("dirty", !renames.isEmpty() || !comments.isEmpty());
        m.put("caps", caps());
        return m;
    }

    // ------------------------------------------------------------ fixtures: tree

    private static final String PKG_SYNC = "com.google.android.gm.sync";
    private static final String PKG_CRYPTO = "com.google.android.gm.crypto";
    private static final String PKG_PROVIDER = "com.google.android.gm.provider";
    private static final String PKG_UTIL = "com.google.android.gm.util";

    private static Map<String, Object> node(String name, String kind, Object... kv) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("kind", kind);
        for (int i = 0; i + 1 < kv.length; i += 2) n.put((String) kv[i], kv[i + 1]);
        return n;
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<Object> gmailTree() {
        return list(
                node(PKG_SYNC, "package", "children", list(
                        node("MessageSyncHandler", "class", "origName", "c", "children", list(
                                node("onMessage(SyncEnvelope)", "method", "origName", "a"),
                                node("handleInvite(SyncEnvelope)", "method", "origName", "a"),
                                node("decryptPayload([B)", "method"))),
                        node("MailSyncService", "class", "children", list(
                                node("onStartCommand(Intent,int,int)", "method"),
                                node("handleIntent(Intent)", "method"))),
                        node("SyncEnvelope", "class"),
                        node("Message", "class"),
                        node("SyncClock", "class"),
                        node("SyncListener", "interface"))),
                node(PKG_CRYPTO, "package", "children", list(
                        node("InviteCipher", "class", "origName", "b", "children", list(
                                node("forKey([B)", "method"),
                                node("open(String,[B)", "method"),
                                node("unwrap([B,[B)", "method"))))),
                node(PKG_PROVIDER, "package", "children", list(
                        node("MailStore", "class"))),
                node(PKG_UTIL, "package", "children", list(
                        node("GeminiClient", "class", "children", list(
                                node("classify(Message)", "method"))))));
    }

    private static List<Object> nativeTree() {
        return list(
                node("libgmail.so", "binary", "children", list(
                        node("nativeDecrypt", "function", "addr", "0x1c40"),
                        node("nativeVerify", "function", "addr", "0x21a8"),
                        node("jni_OnLoad", "function", "addr", "0x1020"))));
    }

    private static List<Object> walletTree() {
        return list(
                node("com.wallet.pay", "package", "children", list(
                        node("PayHandler", "class", "origName", "d", "children", list(
                                node("onPay(Intent)", "method"))))));
    }

    @Override
    public Map<String, Object> tree(String filter) {
        List<Object> t = switch (target.profile()) {
            case "gmail" -> gmailTree();
            case "native" -> nativeTree();
            default -> walletTree();
        };
        if (filter == null || filter.isBlank()) return treeWrap(t);
        String f = filter.toLowerCase();
        List<Object> out = new ArrayList<>();
        for (Object o : t) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pkg = (Map<String, Object>) o;
            Object kids = pkg.get("children");
            List<Object> kept = new ArrayList<>();
            if (kids instanceof List<?> kl) {
                for (Object k : kl) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cls = (Map<String, Object>) k;
                    if (String.valueOf(cls.get("name")).toLowerCase().contains(f)) kept.add(cls);
                }
            }
            if (!kept.isEmpty() || String.valueOf(pkg.get("name")).toLowerCase().contains(f)) {
                Map<String, Object> copy = new LinkedHashMap<>(pkg);
                copy.put("children", kept);
                out.add(copy);
            }
        }
        return treeWrap(out);
    }

    /* same {items, truncated, total} contract as JebEngine.tree — mocks never cap */
    private static Map<String, Object> treeWrap(List<Object> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("truncated", false);
        out.put("total", items.size());
        return out;
    }

    // ------------------------------------------------------------ fixtures: symbols

    /** Known symbols of the gmail scene → resolution record. */
    private static Map<String, Map<String, Object>> gmailSymbols() {
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("messagesynchandler", node("MessageSyncHandler", "class",
                "fqcn", PKG_SYNC + ".MessageSyncHandler", "origName", "c"));
        s.put("onmessage", node("onMessage", "method",
                "owner", PKG_SYNC + ".MessageSyncHandler",
                "sig", "public void onMessage(SyncEnvelope env)", "origName", "a"));
        s.put("handleinvite", node("handleInvite", "method",
                "owner", PKG_SYNC + ".MessageSyncHandler",
                "sig", "void handleInvite(SyncEnvelope env)", "origName", "a"));
        s.put("decryptpayload", node("decryptPayload", "method",
                "owner", PKG_SYNC + ".MessageSyncHandler",
                "sig", "private String decryptPayload(byte[] payload)"));
        s.put("mailsyncservice", node("MailSyncService", "class",
                "fqcn", PKG_SYNC + ".MailSyncService"));
        s.put("onstartcommand", node("onStartCommand", "method",
                "owner", PKG_SYNC + ".MailSyncService",
                "sig", "public int onStartCommand(Intent intent, int flags, int startId)"));
        s.put("handleintent", node("handleIntent", "method",
                "owner", PKG_SYNC + ".MailSyncService",
                "sig", "void handleIntent(Intent intent)"));
        s.put("invitecipher", node("InviteCipher", "class",
                "fqcn", PKG_CRYPTO + ".InviteCipher", "origName", "b"));
        s.put("invitecipher.open", node("open", "method",
                "owner", PKG_CRYPTO + ".InviteCipher",
                "sig", "public byte[] open(String spec, byte[] payload)"));
        s.put("open", s.get("invitecipher.open"));
        s.put("invitecipher.unwrap", node("unwrap", "method",
                "owner", PKG_CRYPTO + ".InviteCipher",
                "sig", "public static byte[] unwrap(byte[] wrapped, byte[] kek)"));
        s.put("unwrap", s.get("invitecipher.unwrap"));
        s.put("invitecipher.forkey", node("forKey", "method",
                "owner", PKG_CRYPTO + ".InviteCipher",
                "sig", "public static InviteCipher forKey(byte[] key)"));
        s.put("forkey", s.get("invitecipher.forkey"));
        s.put("geminiclient", node("GeminiClient", "class",
                "fqcn", PKG_UTIL + ".GeminiClient"));
        s.put("geminiclient.classify", node("classify", "method",
                "owner", PKG_UTIL + ".GeminiClient",
                "sig", "public static String classify(Message msg)"));
        s.put("classify", s.get("geminiclient.classify"));
        s.put("syncworker.onmessage", node("onMessage", "method",
                "owner", PKG_SYNC + ".SyncWorker",
                "sig", "public void onMessage(SyncEnvelope env)"));
        return s;
    }

    private Map<String, Object> symbolTable() {
        Map<String, Object> t = new LinkedHashMap<>();
        switch (target.profile()) {
            case "gmail" -> t.putAll(gmailSymbols());
            case "native" -> {
                t.put("nativedecrypt", node("nativeDecrypt", "function", "addr", "0x1c40",
                        "sig", "int nativeDecrypt(uint8_t* key, uint8_t* in, uint8_t* out, int len)"));
                t.put("nativeverify", node("nativeVerify", "function", "addr", "0x21a8"));
            }
            default -> t.put("payhandler", node("PayHandler", "class",
                    "fqcn", "com.wallet.pay.PayHandler", "origName", "d"));
        }
        // Original identities stay valid after a display rename, just as they do in JEB.
        Map<String, Object> shown = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : t.entrySet()) {
            @SuppressWarnings("unchecked") Map<String, Object> original = (Map<String, Object>) entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>(original);
            String identity = String.valueOf(row.getOrDefault("fqcn", row.getOrDefault("addr",
                    row.get("owner") == null ? row.get("name") : row.get("owner") + "." + row.get("name"))));
            row.put("origSig", identity);
            row.put("addr", identity);
            row.put("originalDisplayName", original.get("name"));
            String changed = renames.get(identity);
            if (changed != null) {
                row.put("name", changed);
                row.put("renamed", true);
                shown.put(normKey(changed), row);
                if (row.get("owner") != null) shown.put(normKey(row.get("owner") + "." + changed), row);
            }
            shown.put(entry.getKey(), row);
            shown.put(normKey(identity), row);
        }
        return shown;
    }

    private String normKey(String symbol) {
        if (symbol == null) return "";
        String s = symbol.trim();
        // allow fqcn forms: take class/method tail
        if (s.contains("(")) s = s.substring(0, s.indexOf('('));
        return s.toLowerCase();
    }

    @Override
    public Map<String, Object> resolve(String symbol) throws OpException {
        Map<String, Object> t = symbolTable();
        String key = normKey(symbol);
        Object hit = t.get(key);
        if (hit == null && key.contains(".")) hit = t.get(key.substring(key.lastIndexOf('.') + 1));
        if (hit == null && !key.isEmpty()) {
            // fuzzy: unique substring match, else ambiguity candidates (never guesses, MCP_SPEC §5)
            List<Object> cand = new ArrayList<>();
            for (Map.Entry<String, Object> e : t.entrySet())
                if (e.getKey().contains(key)) cand.add(e.getValue());
            if (cand.size() == 1) {
                @SuppressWarnings("unchecked")
                Map<String, Object> single = (Map<String, Object>) cand.get(0);
                return new LinkedHashMap<>(single);
            }
            if (cand.size() > 1)
                throw OpException.syntax("ambiguous symbol: " + symbol, "candidates=" + cand.size());
        }
        if (hit == null)
            throw OpException.notFound("symbol not found: " + symbol,
                    "target=" + target.name() + " engine=" + target.engine());
        @SuppressWarnings("unchecked")
        Map<String, Object> h = (Map<String, Object>) hit;
        Map<String, Object> out = new LinkedHashMap<>(h);
        out.put("target", target.id());
        String comment = comments.get(String.valueOf(out.get("origSig")));
        if (comment != null) out.put("comment", comment);
        return out;
    }

    @Override
    public List<Object> symbolSearch(String query) {
        String q = query == null ? "" : query.toLowerCase();
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : symbolTable().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sym = (Map<String, Object>) e.getValue();
            if (q.isEmpty() || String.valueOf(sym.get("name")).toLowerCase().contains(q)
                    || String.valueOf(sym.getOrDefault("fqcn", "")).toLowerCase().contains(q)) {
                if (!out.contains(sym)) out.add(sym);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ fixtures: decompile

    private static final String MESSAGE_SYNC_HANDLER_JAVA = """
            package com.google.android.gm.sync;

            import com.google.android.gm.crypto.InviteCipher;
            import com.google.android.gm.provider.MailStore;
            import com.google.android.gm.util.GeminiClient;

            // obfuscated as 'c' — class + entry method renamed by analyst
            public final class MessageSyncHandler implements SyncListener {

                private static final String CIPHER_SPEC = "AES/GCM/NoPadding";
                private static final String PROTO_TAG   = "invite.v2";

                private final MailStore store;
                private final SyncClock clock;
                private int pending;

                public void onMessage(SyncEnvelope env) {
                    byte[] payload = env.payload();
                    if (payload == null || payload.length == 0) {
                        return;
                    }
                    String proto = env.header(PROTO_TAG);
                    if (!PROTO_TAG.equals(proto)) {
                        this.clock.reject(env);
                        return;
                    }
                    InviteCipher cipher = InviteCipher.forKey(this.store.keyFor(env.threadId()));
                    byte[] plain = cipher.open(CIPHER_SPEC, payload);
                    Message msg = Message.parse(plain);
                    switch (msg.kind) {
                        case Message.INVITE: {
                            String verdict = GeminiClient.classify(msg);  // agent: renames proposed
                            if ("allow".equals(verdict)) {
                                this.store.applyInvite(msg);
                            }
                            break;
                        }
                        default: this.pending++;
                    }
                }
            }
            """;

    private static final String INVITE_CIPHER_JAVA = """
            package com.google.android.gm.crypto;

            import javax.crypto.Cipher;
            import javax.crypto.spec.SecretKeySpec;

            // obfuscated as 'b' — renamed by analyst
            public final class InviteCipher {

                private final byte[] key;

                private InviteCipher(byte[] key) {
                    this.key = key;
                }

                public static InviteCipher forKey(byte[] key) {
                    return new InviteCipher(key);
                }

                public byte[] open(String spec, byte[] payload) {
                    // spec is always "AES/GCM/NoPadding" at the one call site
                    Cipher c = Cipher.getInstance(spec);
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(this.key, "AES"), gcmSpec(payload));
                    return c.doFinal(payload, 12, payload.length - 12);
                }

                public static byte[] unwrap(byte[] wrapped, byte[] kek) {
                    // HKDF(key, "gms-inv") — verify against nativeDecrypt in libgmail.so
                    return hkdf(kek, "gms-inv", wrapped);
                }
            }
            """;

    private static final String MAIL_SYNC_SERVICE_JAVA = """
            package com.google.android.gm.sync;

            import android.app.Service;
            import android.content.Intent;

            public class MailSyncService extends Service {

                private MessageSyncHandler handler;

                public int onStartCommand(Intent intent, int flags, int startId) {
                    SyncEnvelope env = SyncEnvelope.from(intent);
                    this.handler.onMessage(env);
                    return START_STICKY;
                }

                void handleIntent(Intent intent) {
                    if ("invite.v2".equals(intent.getAction())) {
                        this.handler.handleInvite(SyncEnvelope.from(intent));
                    }
                }
            }
            """;

    private static final String NATIVE_DECRYPT_C = """
            // libgmail.so · 0x1c40 · IDA decompilation
            int __fastcall nativeDecrypt(uint8_t *key, uint8_t *in, uint8_t *out, int len)
            {
                // HKDF derivation, salt "gms-inv"; AES-256-GCM open in place.
                uint8_t expanded[32];
                hkdf_sha256(key, 32, "gms-inv", 7, expanded);
                return aes_gcm_open(expanded, in, out, len);
            }
            """;

    @Override
    public Map<String, Object> decompile(String symbol, Map<String, Object> opts) throws OpException {
        requireCap("decompile", "code.decompile");
        Map<String, Object> sym = resolve(symbol);
        String name = String.valueOf(sym.get("name"));
        String text;
        String lang = "java";
        if (target.profile().equals("native")) {
            if (!name.equals("nativeDecrypt")) throw unsupported("code.decompile for " + name);
            text = NATIVE_DECRYPT_C;
            lang = "c";
        } else if (target.profile().equals("wallet")) {
            throw OpException.notFound("no decompilation fixture for " + symbol, "profile=wallet");
        } else {
            text = switch (name) {
                case "MessageSyncHandler" -> MESSAGE_SYNC_HANDLER_JAVA;
                case "InviteCipher" -> INVITE_CIPHER_JAVA;
                case "MailSyncService" -> MAIL_SYNC_SERVICE_JAVA;
                case "onMessage", "handleInvite", "decryptPayload" -> MESSAGE_SYNC_HANDLER_JAVA;
                default -> throw OpException.notFound("no decompilation fixture for " + symbol,
                        "target=" + target.name());
            };
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        out.put("language", lang);
        out.put("engine", target.engine().toLowerCase());
        out.put("text", text);
        out.put("lineCount", text.split("\n", -1).length - 1);
        // semantic token map (classTokens): navigable symbol spans
        if (name.equals("MessageSyncHandler") || name.equals("onMessage")) {
            out.put("tokens", list(
                    map("line", 8, "col", 19, "len", 19, "kind", "class", "symbol", PKG_SYNC + ".MessageSyncHandler"),
                    map("line", 17, "col", 16, "len", 9, "kind", "method", "symbol", PKG_SYNC + ".MessageSyncHandler.onMessage"),
                    map("line", 27, "col", 8, "len", 12, "kind", "class", "symbol", PKG_CRYPTO + ".InviteCipher"),
                    map("line", 28, "col", 30, "len", 4, "kind", "method", "symbol", PKG_CRYPTO + ".InviteCipher.open"),
                    map("line", 32, "col", 33, "len", 21, "kind", "method", "symbol", PKG_UTIL + ".GeminiClient.classify")));
        } else {
            out.put("tokens", list());
        }
        return out;
    }

    @Override
    public Map<String, Object> outline(String classSymbol) throws OpException {
        Map<String, Object> sym = resolve(classSymbol);
        String name = String.valueOf(sym.get("name"));
        if (!name.equals("MessageSyncHandler") && !name.equals("InviteCipher")
                && !name.equals("MailSyncService"))
            throw OpException.notFound("no outline fixture for " + classSymbol, "target=" + target.name());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        switch (name) {
            case "MessageSyncHandler" -> {
                out.put("fields", list(
                        node("name", "CIPHER_SPEC", "sig", "private static final String", "line", 10),
                        node("name", "PROTO_TAG", "sig", "private static final String", "line", 11),
                        node("name", "store", "sig", "private final MailStore", "line", 13),
                        node("name", "clock", "sig", "private final SyncClock", "line", 14),
                        node("name", "pending", "sig", "private int", "line", 15)));
                out.put("methods", list(
                        node("name", "onMessage", "sig", "public void onMessage(SyncEnvelope env)", "line", 17, "origName", "a"),
                        node("name", "handleInvite", "sig", "void handleInvite(SyncEnvelope env)", "origName", "a"),
                        node("name", "decryptPayload", "sig", "private String decryptPayload(byte[] payload)")));
            }
            case "InviteCipher" -> {
                out.put("fields", list(node("name", "key", "sig", "private final byte[]", "line", 9)));
                out.put("methods", list(
                        node("name", "forKey", "sig", "public static InviteCipher forKey(byte[] key)", "line", 15),
                        node("name", "open", "sig", "public byte[] open(String spec, byte[] payload)", "line", 19),
                        node("name", "unwrap", "sig", "public static byte[] unwrap(byte[] wrapped, byte[] kek)", "line", 25)));
            }
            default -> {
                out.put("fields", list(node("name", "handler", "sig", "private MessageSyncHandler", "line", 8)));
                out.put("methods", list(
                        node("name", "onStartCommand", "sig", "public int onStartCommand(Intent, int, int)", "line", 10),
                        node("name", "handleIntent", "sig", "void handleIntent(Intent intent)", "line", 16)));
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> hover(String symbol, int offset) throws OpException {
        Map<String, Object> sym = resolve(symbol);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", sym.get("kind"));
        out.put("owner", sym.getOrDefault("owner", sym.getOrDefault("fqcn", "")));
        out.put("sig", sym.getOrDefault("sig", sym.get("name")));
        out.put("offset", offset);
        out.put("comment", comments.getOrDefault(
                String.valueOf(sym.getOrDefault("fqcn", sym.get("name"))),
                sym.get("name").equals("onMessage")
                        ? "FCM entry point — demuxes invite.v2 envelopes (was: a)" : ""));
        return out;
    }

    // ------------------------------------------------------------ fixtures: relations

    private static Map<String, Object> xref(String symbol, String kind, Object... kv) {
        Map<String, Object> x = node(symbol, kind, kv);
        return x;
    }

    @Override
    public List<Object> xrefsTo(String symbol) throws OpException {
        requireCap("xrefs", "xref.to");
        String name = String.valueOf(resolve(symbol).get("name"));
        if (target.profile().equals("native") && name.equals("nativeDecrypt"))
            return list(xref("decryptPayload", "call", "note", "JNI"));
        if (!name.equals("onMessage") && !name.equals("open") && !name.equals("classify"))
            return list();
        return switch (name) {
            case "onMessage" -> list(
                    xref("MailSyncService.onStartCommand", "call"),
                    xref("MailSyncService.handleIntent", "call"));
            case "open" -> list(
                    xref("MessageSyncHandler.onMessage", "call", "line", 28),
                    xref("decryptPayload", "call"));
            default -> list(xref("MessageSyncHandler.onMessage", "call", "line", 32, "actor", "agent"));
        };
    }

    @Override
    public List<Object> xrefsFrom(String symbol) throws OpException {
        requireCap("xrefs", "xref.from");
        String name = String.valueOf(resolve(symbol).get("name"));
        if (!name.equals("onMessage")) return list();
        return list(
                xref("SyncEnvelope.payload", "call"),
                xref("SyncEnvelope.header", "call"),
                xref("SyncClock.reject", "call"),
                xref("InviteCipher.forKey", "call"),
                xref("InviteCipher.open", "call"),
                xref("Message.parse", "call"),
                xref("GeminiClient.classify", "call", "actor", "agent"),
                xref("MailStore.applyInvite", "call"));
    }

    @Override
    public List<Object> callees(String symbol) throws OpException {
        requireCap("xrefs", "callees");
        String name = String.valueOf(resolve(symbol).get("name"));
        if (!name.equals("onMessage")) return list();
        return list(
                xref("decryptPayload", "call"),
                xref("InviteCipher.unwrap", "call"),
                xref("GeminiClient.classify", "call", "actor", "agent"));
    }

    @Override
    public List<Object> callers(String symbol) throws OpException {
        requireCap("xrefs", "callers");
        String name = String.valueOf(resolve(symbol).get("name"));
        if (!name.equals("onMessage")) return list();
        return list(
                xref("MailSyncService.onStartCommand", "call", "lowConfidence", false),
                xref("MailSyncService.handleIntent", "call", "lowConfidence", false));
    }

    @Override
    public List<Object> implementations(String symbol) throws OpException {
        String name = String.valueOf(resolve(symbol).get("name"));
        if (!name.equals("onMessage")) return list();
        return list(
                xref("SyncWorker.onMessage", "override"),
                xref("g.onMessage", "override", "obfuscated", true));
    }

    // ------------------------------------------------------------ fixtures: strings

    private static Map<String, Object> str(String id, String v, String t, int x, String w,
            String why, boolean unreach, String... xr) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("value", v);
        s.put("type", t);
        s.put("xrefs", x);
        s.put("firstReferrer", w);
        s.put("why", why);
        s.put("unreachable", unreach);
        s.put("xrefList", list((Object[]) xr));
        return s;
    }

    /** The 20 strings of the v7 strings table. */
    private static List<Object> gmailStrings() {
        return list(
                str("s1", "AES/GCM/NoPadding", "crypto", 12, "InviteCipher.open",
                        "crypto · usage-confirmed: arg 0 of Cipher.getInstance (3 call sites)", false,
                        "InviteCipher.open :64", "decryptPayload :28", "nativeDecrypt (libgmail.so)"),
                str("s2", "invite.v2", "intent", 8, "onMessage",
                        "intent · header key, demux point", false, "onMessage :22", "c.smali :27"),
                str("s3", "https://mail.google.com/sync/", "url", 4, "MailSyncService",
                        "url · sync endpoint", false, "MailSyncService:88", "MailSyncService:141"),
                str("s4", "android.intent.action.BOOT_COMPLETED", "intent", 3, "BootReceiver",
                        "intent · manifest-declared", false, "BootReceiver.onReceive"),
                str("s5", "android.permission.READ_CONTACTS", "permission", 1, "AndroidManifest",
                        "permission · manifest", false, "AndroidManifest.xml:14"),
                str("s6", "content://gmail-ls/conversations", "content-uri", 6, "MailContract",
                        "content-uri · provider contract", false, "MailContract:31", "MailStore.query"),
                str("s7", "aXY9c2VjcmV0a2V5", "base64", 1, "(no referrer)",
                        "base64? · lexical-only — no code path found", true, "(unreachable)"),
                str("s8", "gms-inv", "crypto", 2, "decryptPayload",
                        "crypto · HKDF salt candidate", false, "decryptPayload :55", "nativeDecrypt (libgmail.so)"),
                str("s9", "SHA-256", "crypto", 5, "InviteCipher",
                        "crypto · MessageDigest.getInstance arg", false, "InviteCipher:71", "checkin.CheckinService"),
                str("s10", "sync_adapter", "plain", 3, "MailSyncService", "plain", false, "MailSyncService:40"),
                str("s11", "UTC timestamp", "plain", 0, "(no referrer)", "plain · unreachable", true, "(unreachable)"),
                str("s12", "HKDF", "crypto", 4, "nativeDecrypt",
                        "crypto · KDF reference", false, "nativeDecrypt (libgmail.so)", "decryptPayload :58"),
                str("s13", "https://android.clients.google.com/fcm/", "url", 2, "GcmInternalReceiver",
                        "url · FCM endpoint", false, "GcmInternalReceiver:12"),
                str("s14", "gcm.googleapis.com", "url", 3, "mtalk channel",
                        "url · push channel host", false, "mtalk.google.com:5228"),
                str("s15", "invite_key_store", "plain", 1, "MailStore", "plain · pref key", false, "MailStore:97"),
                str("s16", "BEGIN CERTIFICATE", "crypto", 1, "pinned certs",
                        "crypto · PEM marker, pin set", false, "res/raw/pins"),
                str("s17", "MSG_INVITE", "intent", 2, "onMessage",
                        "intent · message kind enum", false, "onMessage :31"),
                str("s18", "dG9rZW4=", "base64", 1, "(no referrer)",
                        "base64? · lexical-only", true, "(unreachable)"),
                str("s19", "com.google.android.gm", "plain", 9, "(package refs)", "plain", false, "(many)"),
                str("s20", "wallet.pay", "plain", 0, "(no referrer)", "plain · unreachable", true, "(unreachable)"));
    }

    private static List<Object> nativeStrings() {
        return list(
                str("n1", "gms-inv", "crypto", 2, "nativeDecrypt", "crypto · HKDF salt", false, "nativeDecrypt"),
                str("n2", "HKDF", "crypto", 1, "nativeDecrypt", "crypto · KDF reference", false, "nativeDecrypt"),
                str("n3", "AES/GCM/NoPadding", "crypto", 1, "nativeDecrypt",
                        "crypto · cross-checked with gmail.apk s1", false, "nativeDecrypt"));
    }

    /** Fixture rows gain a types[] wrapper (VIEWS_SPEC §1 shape) on the way out. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> typedRow(Map<String, Object> s) {
        Map<String, Object> row = new LinkedHashMap<>(s);
        String type = String.valueOf(s.get("type"));
        String why = String.valueOf(s.get("why"));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", type);
        t.put("confidence", why.contains("usage-confirmed") ? "usage" : "lexical");
        t.put("why", why);
        row.put("types", list(t));
        row.put("xrefCount", s.get("xrefs"));
        return row;
    }

    @Override
    public Map<String, Object> strings(String filter, List<String> types, boolean unreachableOnly,
            int offset, int count, String sort) throws OpException {
        requireCap("strings", "strings");
        String sortMode;
        try { sortMode = dev.workbench.engine.StringSort.normalize(sort); }
        catch (IllegalArgumentException e) { throw OpException.syntax(e.getMessage(), "sort=" + sort); }
        List<Object> all = switch (target.profile()) {
            case "gmail" -> gmailStrings();
            case "native" -> nativeStrings();
            default -> list(str("w1", "wallet.pay", "plain", 0, "(no referrer)",
                    "plain · unreachable", true, "(unreachable)"));
        };
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Object o : all) {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) o;
            if (unreachableOnly && !Boolean.TRUE.equals(s.get("unreachable"))) continue;
            if (types != null && !types.isEmpty() && !types.contains(String.valueOf(s.get("type")))) continue;
            if (filter != null && !filter.isBlank()
                    && !String.valueOf(s.get("value")).toLowerCase().contains(filter.toLowerCase()))
                continue;
            filtered.add(s);
        }
        dev.workbench.engine.StringSort.sort(filtered, sortMode);   // before paging, like the real engine
        int cap = count <= 0 ? 200 : Math.min(count, 2000);
        List<Object> rows = new ArrayList<>();
        for (int i = offset; i < filtered.size() && rows.size() < cap; i++)
            rows.add(typedRow(filtered.get(i)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("offset", offset);
        out.put("count", rows.size());
        out.put("total", filtered.size());
        out.put("truncated", offset + rows.size() < filtered.size());
        out.put("totalAll", all.size());
        out.put("sort", sortMode);
        return out;
    }

    @Override
    public List<Object> stringXrefs(String stringId) throws OpException {
        for (Object o : (List<Object>) strings(null, null, false, 0, 0, "order").get("rows")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) o;
            if (s.get("id").equals(stringId)) {
                List<Object> out = new ArrayList<>();
                Object xr = s.get("xrefList");
                if (xr instanceof List<?> xl)
                    for (Object site : xl)
                        out.add(node("site", String.valueOf(site), "stringId", stringId,
                                "value", s.get("value")));
                return out;
            }
        }
        throw OpException.notFound("string id not found: " + stringId, "target=" + target.name());
    }

    /** Class metadata for the inspector bay (code.classinfo) — gmail fixture values. */
    @Override
    public Map<String, Object> classinfo(String classSymbol) throws OpException {
        Map<String, Object> sym = resolve(classSymbol);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("addr", sym.getOrDefault("fqcn", sym.get("name")));
        out.put("name", sym.get("name"));
        out.put("fqcn", sym.getOrDefault("fqcn", sym.get("name")));
        out.put("sig", sym.getOrDefault("sig", sym.get("name")));
        out.put("super", "java.lang.Object");
        out.put("interfaces", sym.get("name").equals("MessageSyncHandler") ? list("SyncListener") : list());
        out.put("flags", "public final");
        out.put("methods", sym.get("name").equals("MessageSyncHandler") ? 7 : 3);
        out.put("fields", sym.get("name").equals("MessageSyncHandler") ? 5 : 1);
        out.put("xrefCount", sym.get("name").equals("onMessage") ? 12 : 2);
        out.put("renamed", sym.containsKey("origName"));
        if (sym.containsKey("origName")) out.put("origName", sym.get("origName"));
        out.put("kind", sym.getOrDefault("kind", "class"));
        return out;
    }

    // ------------------------------------------------------------ fixtures: search

    @Override
    public List<Object> search(String query, Map<String, Object> opts) throws OpException {
        String q = query == null ? "" : query;
        List<Object> groups = new ArrayList<>();
        if (target.profile().equals("gmail")) {
            groups.add(node("symbol", "MessageSyncHandler", "target", target.name(),
                    "engine", target.engine(), "addr", "MessageSyncHandler", "hits", list(
                            node("kind", "code", "line", 10, "addr", "MessageSyncHandler",
                                    "preview", "private static final String CIPHER_SPEC = \"AES/GCM/NoPadding\";"),
                            node("kind", "code", "line", 27, "addr", "MessageSyncHandler",
                                    "preview", "InviteCipher cipher = InviteCipher.forKey(this.store.keyFor(env.threadId()));"),
                            node("kind", "code", "line", 28, "addr", "MessageSyncHandler",
                                    "preview", "byte[] plain = cipher.open(CIPHER_SPEC, payload);"),
                            node("kind", "strings", "id", "s1",
                                    "preview", "\"AES/GCM/NoPadding\" · cipher spec constant"))));
            groups.add(node("symbol", "InviteCipher", "target", target.name(),
                    "engine", target.engine(), "addr", "InviteCipher", "hits", list(
                            node("kind", "code", "line", 19, "addr", "InviteCipher",
                                    "preview", "public byte[] open(String spec, byte[] payload) {"),
                            node("kind", "code", "line", 25, "addr", "InviteCipher",
                                    "preview", "public static byte[] unwrap(byte[] wrapped, byte[] kek) {"),
                            node("kind", "strings", "id", "s2",
                                    "preview", "\"gms-inv\" · HKDF salt for invite cipher"))));
            groups.add(node("symbol", "MailSyncService", "target", target.name(),
                    "engine", target.engine(), "addr", "MailSyncService", "hits", list(
                            node("kind", "code", "line", 10, "addr", "MailSyncService",
                                    "preview", "public int onStartCommand(Intent intent, int flags, int startId) {"))));
        } else if (target.profile().equals("native")) {
            groups.add(node("symbol", "nativeDecrypt", "target", target.name(),
                    "engine", target.engine(), "addr", "nativeDecrypt", "hits", list(
                            node("kind", "code", "line", 5, "addr", "nativeDecrypt",
                                    "preview", "hkdf_sha256(key, 32, \"gms-inv\", 7, expanded);"))));
        }
        if (q.isBlank()) return groups;
        String ql = q.toLowerCase();
        List<Object> out = new ArrayList<>();
        for (Object o : groups) {
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) o;
            List<Object> kept = new ArrayList<>();
            Object hits = g.get("hits");
            if (hits instanceof List<?> hl)
                for (Object h : hl) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> hit = (Map<String, Object>) h;
                    if (String.valueOf(hit.get("preview")).toLowerCase().contains(ql)) kept.add(hit);
                }
            if (!kept.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>(g);
                copy.put("hits", kept);
                out.add(copy);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ write ops

    @Override
    public Map<String, Object> rename(String symbol, String name, String actor) throws OpException {
        requireCap("rename", "rename");
        if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
            throw OpException.syntax("bad Java identifier: " + name, "symbol=" + symbol);
        Map<String, Object> sym = resolve(symbol);
        String key = String.valueOf(sym.get("origSig"));
        String oldName = String.valueOf(sym.get("name"));
        renames.put(key, name);
        // Policy is enforced above the engine. Once called, this mutation has actually happened.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        out.put("oldName", oldName);
        out.put("newName", name);
        out.put("addr", key);
        out.put("newAddr", key);              // mock renames don't shift addresses
        out.put("kind", sym.get("kind"));
        out.put("applied", true);
        return out;
    }

    /** The mock scene has no dex: the metadata is "unsupported", said as such (never "absent"). */
    @Override
    public Map<String, Object> methodParameters(String methodSymbol) throws OpException {
        Map<String, Object> sym = resolve(methodSymbol);
        if (!"method".equals(sym.get("kind"))) throw OpException.syntax("code.params takes a METHOD", "symbol=" + methodSymbol);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "method-parameters-v1");
        out.put("method", sym.getOrDefault("fqcn", sym.get("name")));
        out.put("annotation", Map.of("source", "dex_method_parameters_annotation", "status", "unsupported", "why", "the mock engine has no dex to read"));
        out.put("debugInfo", Map.of("source", "dex_debug_info_parameter_names", "status", "unsupported", "why", "the mock engine has no dex to read"));
        out.put("parameters", List.of());
        out.put("argumentMapping", "unverified");
        return out;
    }

    /** Restore the original name: drop the mock's rename record. */
    @Override
    public Map<String, Object> restoreOriginalName(String symbol, String expectedOriginal, String actor) throws OpException {
        Map<String, Object> sym = resolve(symbol);
        String key = String.valueOf(sym.get("origSig"));
        String orig = String.valueOf(sym.getOrDefault("origName", sym.get("name")));
        if (expectedOriginal != null && !expectedOriginal.equals(orig) && !expectedOriginal.equals(sym.get("name")))
            throw OpException.syntax("the recorded original name '" + expectedOriginal + "' is not this symbol's (" + orig + ")", "symbol=" + symbol);
        String cur = renames.remove(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        out.put("addr", key); out.put("newAddr", key);
        out.put("kind", sym.get("kind"));
        out.put("oldName", cur == null ? sym.get("name") : cur);
        out.put("newName", sym.get("originalDisplayName"));
        out.put("applied", cur != null);
        out.put("restored", true);
        if (cur == null) out.put("note", "already the original name — nothing changed");
        return out;
    }

    @Override
    public Map<String, Object> comment(String symbol, String text, String actor) throws OpException {
        requireCap("comment", "comment");
        Map<String, Object> sym = resolve(symbol);
        String key = String.valueOf(sym.get("origSig"));
        String oldText = comments.getOrDefault(key, "");
        comments.put(key, text == null ? "" : text);
        boolean human = "human".equals(actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        out.put("text", text);
        out.put("oldText", oldText);
        out.put("applied", human);
        out.put("proposal", !human);
        if (!human) out.put("status", "pending");
        return out;
    }
    /** The v7 mockup smali fixture (c.smali — MessageSyncHandler listing), as
     *  "jline|offset|bytes|text" rows; the same rows the mockup ships inline. */
    private static final String[] GMAIL_SMALI = {
        "|||.class public final Lcom/google/android/gm/sync/MessageSyncHandler;",
        "|||.super Ljava/lang/Object;",
        "|||.source \"c.java\"",
        "|||",
        "|||# interfaces",
        "|||.implements Lcom/google/android/gm/sync/SyncListener;",
        "|||",
        "|||# sidecar: class renamed from c · analyst",
        "10|||.field private static final CIPHER_SPEC:Ljava/lang/String; = \"AES/GCM/NoPadding\"",
        "11|||.field private static final PROTO_TAG:Ljava/lang/String; = \"invite.v2\"",
        "|||.field private final store:Lcom/google/android/gm/provider/MailStore;",
        "|||.field private final clock:Lcom/google/android/gm/sync/SyncClock;",
        "|||.field private pending:I",
        "|||",
        "17|||.method public onMessage(Lcom/google/android/gm/sync/SyncEnvelope;)V",
        "17|||    .locals 4",
        "17|||    .param p1, \"env\"    # SyncEnvelope",
        "|||",
        "18|0000|6e 10 04 00|    invoke-virtual {p1}, Lcom/google/android/gm/sync/SyncEnvelope;->payload()[B",
        "18|0003|0c 00|    move-result-object v0",
        "19|0005|38 00 06 00|    if-eqz v0, :cond_0",
        "19|0007|20 01 00 00|    array-length v1, v0",
        "19|0009|39 01 03 00|    if-nez v1, :cond_0",
        "20|000b|0e 00|    return-void",
        "|||",
        "|||    :cond_0",
        "22|000c|1a 01 4b 00|    const-string v1, \"invite.v2\"",
        "22|000e|6e 20 05 00 41 00|    invoke-virtual {p1, v1}, Lcom/google/android/gm/sync/SyncEnvelope;->header(Ljava/lang/String;)Ljava/lang/String;",
        "|0011|0c 02|    move-result-object v2",
        "|0013|60 03 01 00|    sget-object v3, Lcom/google/android/gm/sync/MessageSyncHandler;->PROTO_TAG:Ljava/lang/String;",
        "23|0016|6e 20 06 00 32 00|    invoke-virtual {v3, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
        "|0019|0a 01|    move-result v1",
        "|001a|39 01 08 00|    if-nez v1, :cond_1",
        "24|001c|54 01 02 00|    iget-object v1, p0, Lcom/google/android/gm/sync/MessageSyncHandler;->clock:Lcom/google/android/gm/sync/SyncClock;",
        "24|001f|6e 20 07 00 41 00|    invoke-virtual {v1, p1}, Lcom/google/android/gm/sync/SyncClock;->reject(Lcom/google/android/gm/sync/SyncEnvelope;)V",
        "25|0022|0e 00|    return-void",
        "|||",
        "|||    :cond_1",
        "27|0023|6e 10 08 00|    invoke-virtual {p1}, Lcom/google/android/gm/sync/SyncEnvelope;->threadId()J",
        "|0026|0b 00|    move-result-wide v0",
        "|0028|54 02 03 00|    iget-object v2, p0, Lcom/google/android/gm/sync/MessageSyncHandler;->store:Lcom/google/android/gm/provider/MailStore;",
        "27|002b|6e 30 09 00 20 00|    invoke-virtual {v2, v0, v1}, Lcom/google/android/gm/provider/MailStore;->keyFor(J)[B",
        "|002e|0c 02|    move-result-object v2",
        "27|0030|71 10 0a 00 02 00|    invoke-static {v2}, Lcom/google/android/gm/crypto/InviteCipher;->forKey([B)Lcom/google/android/gm/crypto/InviteCipher;",
        "|0033|0c 02|    move-result-object v2",
        "28|0035|60 03 00 00|    sget-object v3, Lcom/google/android/gm/sync/MessageSyncHandler;->CIPHER_SPEC:Ljava/lang/String;",
        "28|0038|6e 40 0b 00 32 00|    invoke-virtual {v2, v3, v0}, Lcom/google/android/gm/crypto/InviteCipher;->open(Ljava/lang/String;[B)[B",
        "|003b|0c 00|    move-result-object v0",
        "29|003d|71 10 0c 00 00 00|    invoke-static {v0}, Lcom/google/android/gm/sync/Message;->parse([B)Lcom/google/android/gm/sync/Message;",
        "|0040|0c 00|    move-result-object v0",
        "30|0042|52 01 04 00|    iget v1, v0, Lcom/google/android/gm/sync/Message;->kind:I",
        "31|0045|12 12|    const/4 v2, 0x1    # INVITE",
        "|0047|32 21 0a 00|    if-ne v1, v2, :cond_2",
        "38|0049|52 01 05 00|    iget v1, p0, Lcom/google/android/gm/sync/MessageSyncHandler;->pending:I",
        "38|004c|d8 01 01 01|    add-int/lit8 v1, v1, 0x1",
        "38|004e|5b 01 05 00|    iput v1, p0, Lcom/google/android/gm/sync/MessageSyncHandler;->pending:I",
        "|0050|0e 00|    return-void",
        "|||",
        "|||    :cond_2",
        "32|0051|71 10 0d 00 00 00|    invoke-static {v0}, Lcom/google/android/gm/util/GeminiClient;->classify(Lcom/google/android/gm/sync/Message;)Ljava/lang/String;",
        "|0054|0c 01|    move-result-object v1",
        "33|0056|1a 02 4c 00|    const-string v2, \"allow\"",
        "33|0058|6e 20 06 00 21 00|    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
        "|005b|0a 01|    move-result v1",
        "|005d|38 01 08 00|    if-eqz v1, :cond_3",
        "|005f|54 01 03 00|    iget-object v1, p0, Lcom/google/android/gm/sync/MessageSyncHandler;->store:Lcom/google/android/gm/provider/MailStore;",
        "34|0062|6e 20 0e 00 01 00|    invoke-virtual {v1, v0}, Lcom/google/android/gm/provider/MailStore;->applyInvite(Lcom/google/android/gm/sync/Message;)V",
        "|||",
        "|||    :cond_3",
        "36|0065|0e 00|    return-void",
        "|||.end method",
        "|||",
        "|||# renamed from: b — AES-GCM open, 12-byte nonce prepended",
        "|||.method private decryptPayload([B)Ljava/lang/String;",
        "|||    .locals 3",
        "|||",
        "|0000|1a 00 4d 00|    const-string v0, \"AES/GCM/NoPadding\"",
        "|0002|71 10 0f 00 00 00|    invoke-static {v0}, Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;",
        "|0005|0c 00|    move-result-object v0",
        "|0007|6e 20 10 00 10 00|    invoke-virtual {v0, p1}, Ljavax/crypto/Cipher;->doFinal([B)[B",
        "|000a|0c 00|    move-result-object v0",
        "|000c|22 01 11 00|    new-instance v1, Ljava/lang/String;",
        "|000e|70 20 12 00 10 00|    invoke-direct {v1, v0}, Ljava/lang/String;-><init>([B)V",
        "|0011|11 01|    return-object v1",
        "|||.end method"
    };

    @Override
    public Map<String, Object> smali(String symbol) throws OpException {
        requireCap("smali", "code.smali");
        Map<String, Object> sym = resolve(symbol);
        if (!target.profile().equals("gmail"))
            throw unsupported("code.smali for " + sym.get("name"));
        List<Object> rows = new ArrayList<>();
        for (String raw : GMAIL_SMALI) {
            String[] p = raw.split("\\|", -1);
            String text = p[3];
            String trimmed = text.trim();
            String kind = trimmed.isEmpty() ? "blank"
                    : trimmed.startsWith(".") ? "directive"
                    : trimmed.startsWith(":") ? "label"
                    : trimmed.startsWith("#") ? "comment" : "insn";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind);
            row.put("offset", p[1]);
            row.put("bytes", p[2]);
            row.put("text", text);
            if (!p[0].isEmpty()) row.put("jline", Integer.parseInt(p[0]));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        out.put("engine", target.engine().toLowerCase());
        out.put("count", rows.size());
        out.put("rows", rows);
        return out;
    }

}
