package dev.workbench.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fcm-attack-surface seed (GRAPHS_SPEC §3 schema): converts the v7 fixture
 * (previously hardcoded in web/js/views/map.js) into a REAL map record in the
 * project sidecar on first boot. Symbol refs point at MockEngine targets where
 * they resolve (frontdoor→MailSyncService.handleIntent, natdec→nativeDecrypt,
 * mdm-svc→MessageSyncHandler.onMessage), and stay unresolved elsewhere (lenient
 * — the fixture's GMS classes exist in no open target).
 */
final class MapSeed {

    private MapSeed() {}

    private static Map<String, Object> node(String id, String kind, String label, String sub,
            int layer, String verdict, Boolean proven, String target, String symbolAddr,
            String... tags) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("kind", kind);
        n.put("label", label);
        if (sub != null) n.put("sublabel", sub);
        n.put("layer", layer);
        n.put("verdict", verdict);
        if (proven != null) n.put("proven", proven);
        if (target != null) n.put("target", target);
        if (symbolAddr != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("addr", symbolAddr);
            n.put("symbol", s);
        }
        if (tags.length > 0) n.put("tags", List.of(tags));
        return n;
    }

    private static Map<String, Object> edge(String from, String to, String kind, String label,
            String conf, String gate) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("from", from);
        e.put("to", to);
        e.put("kind", kind);
        if (label != null) e.put("label", label);
        if (conf != null) e.put("confidence", conf);
        if (gate != null) e.put("gate", gate);
        return e;
    }

    private static Map<String, Object> field(String node, String name, String verdict,
            String evidenceRef) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("node", node);
        f.put("field", name);
        f.put("verdict", verdict);
        if (evidenceRef != null) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("run", evidenceRef);
            f.put("evidence", List.of(ev));
        }
        return f;
    }

    private static Map<String, Object> chain(String node, String prefix) {
        String[][] steps = {
                { "entry", "L…;->onHandleIntent(Landroid/content/Intent;)V" },
                { "base64-decode", "L…;->h(Landroid/content/Intent;)V:147" },
                { "proto-merge-raw", "L…;->mergeFrom([B)V" },
                { "loose-extras", "L…;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;" },
                { "parse-guard", "L…;->a(I)Z:88" },
                { "expiry-gate", "L…;->b(J)Z:102" },
                { "identity-gate", "L…;->a(Ljava/lang/String;)Z:121" },
                { "dispatch", "L…;->a(" + prefix + ")V" },
        };
        List<Object> out = new ArrayList<>();
        for (String[] s : steps) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("label", s[0]);
            Map<String, Object> sym = new LinkedHashMap<>();
            sym.put("addr", s[1]);
            st.put("symbol", sym);
            out.add(st);
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("node", node);
        c.put("steps", out);
        return c;
    }

    static Map<String, Object> fcm() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        m.put("id", "fcm-attack-surface");
        m.put("title", "FCM push attack surface");
        m.put("build", "gms-26.25.32");

        List<Object> nodes = new ArrayList<>();
        nodes.add(node("mtalk", "endpoint", "mtalk.google.com:5228",
                "TLS · outbound, long-lived · com.google.android.gms.persistent", 0, "none", null, null, null));
        nodes.add(node("udp", "endpoint", "UDP:5228 doorbell", "wake · NAT keepalive",
                0, "none", null, null, null));
        nodes.add(node("frontdoor", "frontdoor", "GcmInternalReceiverIntentOperation.onHandleIntent",
                "demux by intent extras: from · gcms · gcmb · subtype", 1, "none", null, "gmail.apk",
                "com.google.android.gm.sync.MailSyncService.handleIntent"));
        nodes.add(node("allowlist", "allowlist", "GcmDestinationAllowlist",
                "48 destination FQCNs · 22 svc · 26 rcvr", 2, "none", null, null, null));
        nodes.add(node("mdm-svc", "handler", "mdm.services.GcmReceiverService", "MDM remote policy",
                3, "authored", true, "gmail.apk", "com.google.android.gm.sync.MessageSyncHandler.onMessage",
                "push", "mdm"));
        nodes.add(node("authzen", "handler", "auth.authzen.GcmReceiverService", "AuthZen device auth",
                3, "authored", null, "gmail.apk", "com.google.android.gm.crypto.InviteCipher.open", "push"));
        nodes.add(node("chromesync", "handler", "chromesync.GcmReceiverService", "Sync trigger",
                3, "mixed", null, null, null, "push", "sync"));
        nodes.add(node("gcmproc", "handler", "gcm.gcmproc.GcmReceiverService", "proc runner",
                3, "identity", null, null, null, "push"));
        nodes.add(node("checkin", "handler", "checkin.CheckinService", "checkin trigger",
                3, "identity", null, null, null, "push"));
        nodes.add(node("chimera", "handler", "chimera.GcmModuleIntentOperation",
                "Chimera module — unresolved", 3, "gap", null, null, null, "push"));
        nodes.add(node("lockscreen", "handler", "mdm.services.LockscreenMessageService",
                "LOCK / wipe sinks", 3, "authored", true, "gmail.apk",
                "com.google.android.gm.sync.MessageSyncHandler.handleInvite", "push", "mdm"));
        nodes.add(node("locreport", "handler", "location.reporting.GcmPushService",
                "location reporting", 3, "mixed", null, null, null, "push", "location"));
        nodes.add(node("usage", "handler", "usagereporting.GcmBroadcastReceiver",
                "no parse path found", 3, "dead", null, null, null, "push"));
        nodes.add(node("fednlp", "handler", "federatednlp.GcmReceiverService", "FLEDGE/FL",
                3, "identity", null, null, null, "push"));
        nodes.add(node("natdec", "native", "nativeDecrypt", "AES-GCM unwrap in native",
                3, "none", null, "libgmail.so", "nativeDecrypt"));
        m.put("nodes", nodes);

        List<Object> edges = new ArrayList<>();
        edges.add(edge("mtalk", "frontdoor", "transport", "TCP/TLS", "static", null));
        edges.add(edge("udp", "frontdoor", "transport", "doorbell", "low", null));
        edges.add(edge("frontdoor", "allowlist", "dispatch", "gcms extra", "static", "allowlist"));
        edges.add(edge("frontdoor", "allowlist", "dispatch", "gcmb extra", "static", "allowlist"));
        edges.add(edge("allowlist", "mdm-svc", "dispatch", "svc", "dyn", null));
        edges.add(edge("allowlist", "authzen", "dispatch", "svc", "static", null));
        edges.add(edge("allowlist", "chromesync", "dispatch", "svc", "static", null));
        edges.add(edge("allowlist", "gcmproc", "dispatch", "svc", "static", null));
        edges.add(edge("allowlist", "checkin", "dispatch", "svc", "static", null));
        edges.add(edge("allowlist", "chimera", "dispatch", "hub?", "low", null));
        edges.add(edge("allowlist", "lockscreen", "dispatch", "svc", "dyn", null));
        edges.add(edge("allowlist", "locreport", "dispatch", "svc", "static", null));
        edges.add(edge("allowlist", "usage", "dispatch", "rcvr", "low", null));
        edges.add(edge("allowlist", "fednlp", "dispatch", "svc", "static", null));
        edges.add(edge("lockscreen", "natdec", "call", "native", "low", null));
        m.put("edges", edges);

        List<Object> fields = new ArrayList<>();
        fields.add(field("mdm-svc", "new_password", "verbatim", "run-07-21"));
        fields.add(field("mdm-svc", "lock_message", "verbatim", "run-07-21"));
        fields.add(field("mdm-svc", "device_id", "identity", "static"));
        fields.add(field("authzen", "tx_request", "verbatim", "run-07-22"));
        fields.add(field("authzen", "aid", "constrained", "static"));
        fields.add(field("chromesync", "sync_hint", "constrained", "static"));
        fields.add(field("chromesync", "account", "identity", "proto.e"));
        m.put("fields", fields);

        List<Object> chains = new ArrayList<>();
        chains.add(chain("mdm-svc", "Lmdm/services/MdmRemotePolicy;"));
        chains.add(chain("authzen", "Lauth/authzen/TxRequest;"));
        chains.add(chain("chromesync", "Lchromesync/SyncRequest;"));
        m.put("chains", chains);

        List<Object> gates = new ArrayList<>();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", "allowlist");
        g.put("label", "GcmDestinationAllowlist (48 FQCNs)");
        g.put("position", "pre-action");
        gates.add(g);
        m.put("gates", gates);
        return m;
    }
}
