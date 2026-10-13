package dev.workbench.engine.jeb;

import com.pnfsoftware.jeb.core.units.code.android.IDexUnit;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDalvikInstruction;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexClass;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexCodeItem;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethod;
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethodData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-layer string classification (WORKBENCH_SPEC §6.4, VIEWS_SPEC §1).
 *
 * Lexical layer (engine-neutral, index time): URI schemes, android.permission.*,
 * content://, cert/key PEM headers, file paths, a crypto-algorithm dictionary,
 * base64/hex via charset + length + entropy.
 *
 * Usage layer (JEB): one pass per referrer method — track const-string loads
 * (dest register → pool index), then for each following invoke within a small
 * window check whether a tracked register is an argument; the invoke target
 * decides the type (Cipher.getInstance ⇒ crypto usage-confirmed, Uri.parse /
 * Intent.setData ⇒ url, PackageManager permission calls ⇒ permission,
 * Intent action ⇒ intent), regardless of what the bytes look like.
 *
 * Every verdict is {name, confidence: lexical|usage, why} — lexical-only guesses
 * render dashed with '?' in the UI; usage verdicts state rule + call-site count.
 */
final class StringClassifier {

    private StringClassifier() {}

    // ------------------------------------------------------------ lexical layer

    private static final String[] CRYPTO_EXACT = {
            "AES", "DES", "DESede", "RSA", "EC", "ECDSA", "ECDH", "DH", "DSA", "RC4", "Blowfish",
            "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512", "MD5", "MD2",
            "HmacSHA1", "HmacSHA256", "HmacSHA384", "HmacSHA512", "HmacMD5",
            "PBKDF2WithHmacSHA1", "PBKDF2WithHmacSHA256", "HKDF", "SCrypt", "BCrypt",
            "ChaCha20", "ChaCha20-Poly1305", "Ed25519", "EdDSA", "X25519", "GCM", "CCM" };
    private static final String[] CRYPTO_PREFIX = {
            "AES/", "DES/", "DESede/", "RSA/", "SHA", "HmacSHA", "PBEWith", "PBKDF2" };
    private static final String[] CRYPTO_HEADERS = {
            "BEGIN CERTIFICATE", "BEGIN PUBLIC KEY", "BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY",
            "BEGIN EC PRIVATE KEY", "BEGIN ENCRYPTED PRIVATE KEY", "BEGIN PKCS7" };

    /** Lexical types for a value: ordered, best first. Never empty ("plain" last). */
    static List<Map<String, Object>> lexical(String v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v == null || v.isEmpty()) {
            add(out, "plain", "lexical", "plain");
            return out;
        }
        if (v.startsWith("android.permission."))
            add(out, "permission", "lexical", "permission · manifest-constant shape");
        if (v.startsWith("content://"))
            add(out, "content-uri", "lexical", "content-uri · provider contract shape");
        else if (v.matches("^[a-z][a-z0-9+.-]*://\\S+$"))
            add(out, "url", "lexical", "url · URI scheme");
        if (v.startsWith("android.intent.") || v.contains(".intent.action.") || v.contains(".intent.category."))
            add(out, "intent", "lexical", "intent · android.intent.* constant");
        for (String h : CRYPTO_HEADERS)
            if (v.contains(h)) {
                add(out, "crypto", "lexical", "crypto · PEM header: " + h);
                break;
            }
        for (String e : CRYPTO_EXACT)
            if (v.equals(e) || (v.length() > e.length() && v.startsWith(e + "/"))) {
                add(out, "crypto", "lexical", "crypto · algorithm dictionary: " + e);
                break;
            }
        if (!out.stream().anyMatch(t -> t.get("name").equals("crypto")))
            for (String p : CRYPTO_PREFIX)
                if (v.startsWith(p)) {
                    add(out, "crypto", "lexical", "crypto · algorithm dictionary: " + p + "…");
                    break;
                }
        if (v.matches("^(/[\\w.@+-]+)+/?$") && v.length() >= 4)
            add(out, "path", "lexical", "path · unix file path shape");
        if (v.matches("[0-9a-fA-F]+") && v.length() >= 16 && v.length() % 2 == 0
                && shannon(v) > 2.2)
            add(out, "hex", "lexical", "hex? · charset+length+entropy (" + v.length() + " chars)");
        else if (v.matches("[A-Za-z0-9+/]+={0,2}") && v.length() >= 16 && v.length() % 4 == 0
                && shannon(v) > 3.0 && !v.matches("[a-z]+"))
            add(out, "base64", "lexical", "base64? · charset+length+entropy (" + v.length() + " chars)");
        if (out.isEmpty()) add(out, "plain", "lexical", "plain");
        return out;
    }

    /** Shannon entropy per character — a uniform-charset test for base64/hex guesses. */
    private static double shannon(String s) {
        int[] freq = new int[256];
        for (int i = 0; i < s.length(); i++) freq[s.charAt(i) & 0xFF]++;
        double h = 0;
        for (int f : freq) {
            if (f == 0) continue;
            double p = f / (double) s.length();
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    private static void add(List<Map<String, Object>> out, String name, String confidence, String why) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("confidence", confidence);
        t.put("why", why);
        out.add(t);
    }

    // ------------------------------------------------------------ usage layer

    /** An invoke rule: target-signature substring → {type, display name}. */
    private static final String[][] USAGE_RULES = {
            { "Ljavax/crypto/Cipher;->getInstance", "crypto", "Cipher.getInstance" },
            { "Ljavax/crypto/KeyGenerator;->getInstance", "crypto", "KeyGenerator.getInstance" },
            { "Ljavax/crypto/SecretKeyFactory;->getInstance", "crypto", "SecretKeyFactory.getInstance" },
            { "Ljavax/crypto/Mac;->getInstance", "crypto", "Mac.getInstance" },
            { "Ljava/security/MessageDigest;->getInstance", "crypto", "MessageDigest.getInstance" },
            { "Ljava/security/Signature;->getInstance", "crypto", "Signature.getInstance" },
            { "Ljava/security/KeyStore;->getInstance", "crypto", "KeyStore.getInstance" },
            { "Ljava/security/SecureRandom;->getInstance", "crypto", "SecureRandom.getInstance" },
            { "Landroid/net/Uri;->parse", "url", "Uri.parse" },
            { "Landroid/content/Intent;->setData", "url", "Intent.setData" },
            { "Landroid/webkit/WebView;->loadUrl", "url", "WebView.loadUrl" },
            { "Ljava/net/URL;-><init>", "url", "URL.<init>" },
            { "Ljava/net/URI;-><init>", "url", "URI.<init>" },
            { "Landroid/content/pm/PackageManager;->checkPermission", "permission", "PackageManager.checkPermission" },
            { "Landroid/content/Context;->checkSelfPermission", "permission", "Context.checkSelfPermission" },
            { "Landroid/app/Activity;->requestPermissions", "permission", "Activity.requestPermissions" },
            { "Landroidx/core/app/ActivityCompat;->requestPermissions", "permission", "ActivityCompat.requestPermissions" },
            { "Landroidx/core/content/ContextCompat;->checkSelfPermission", "permission", "ContextCompat.checkSelfPermission" },
            { "Landroid/content/Intent;->setAction", "intent", "Intent.setAction" },
            { "Landroid/content/Intent;-><init>(Ljava/lang/String;", "intent", "Intent.<init>(action)" },
    };
    private static final int WINDOW = 8;      // max instructions between const-string and its invoke

    /**
     * One pass over a method's instructions: const-string loads (dest register → pool
     * index), then invokes within WINDOW using a tracked register. Emits facts
     * {stringIndex → {type, detail, argPos}}. Runs on the engine lock (reads DEX).
     */
    static Map<Integer, String[]> usageFactsForMethod(IDexUnit dex, IDexMethod m) {
        Map<Integer, String[]> facts = new LinkedHashMap<>();
        IDexMethodData md = m.getData();
        IDexCodeItem ci = md == null ? null : md.getCodeItem();
        if (ci == null) return facts;
        List<? extends IDalvikInstruction> insns = ci.getInstructions();
        if (insns == null) return facts;
        // reg → {stringIndex, instructionsAgo}
        Map<Long, int[]> pending = new LinkedHashMap<>();
        int pos = 0;
        for (IDalvikInstruction insn : insns) {
            int op = insn.getOpcode();
            if (op == 0x1a || op == 0x1b) {                       // const-string(/jumbo)
                Long dest = null;
                int strIdx = -1;
                for (int i = 0; i < insn.getParameterCount(); i++) {
                    int t = insn.getParameterType(i);
                    if (t == IDalvikInstruction.TYPE_REG && dest == null) dest = insn.getParameterValue(i);
                    if (t == IDalvikInstruction.TYPE_IDX) strIdx = (int) insn.getParameterValue(i);
                }
                if (dest != null && strIdx >= 0) pending.put(dest, new int[] { strIdx, pos });
            } else if (op >= 0x6e && op <= 0x72) {                // invoke-* family
                // collect registers used + the method pool index
                int methodIdx = -1;
                List<Long> regs = new ArrayList<>();
                for (int i = 0; i < insn.getParameterCount(); i++) {
                    int t = insn.getParameterType(i);
                    if (t == IDalvikInstruction.TYPE_IDX) methodIdx = (int) insn.getParameterValue(i);
                    else if (t == IDalvikInstruction.TYPE_REG) regs.add(insn.getParameterValue(i));
                    else if (t == IDalvikInstruction.TYPE_RGR) {
                        long v = insn.getParameterValue(i);       // packed: count<<16 | firstReg? treat low/high
                        int first = (int) (v & 0xFFFF), count = (int) ((v >>> 16) & 0xFFFF);
                        for (long rr = first; rr < first + Math.max(1, count); rr++) regs.add(rr);
                    }
                }
                if (methodIdx >= 0) {
                    String target = null;
                    try {
                        IDexMethod callee = dex.getMethods().get(methodIdx);
                        if (callee != null) target = callee.getSignature(false);
                    } catch (Exception ignored) {}
                    if (target != null) {
                        for (String[] rule : USAGE_RULES) {
                            if (!target.startsWith(rule[0]) && !target.contains(rule[0])) continue;
                            // arg position of the tracked register (static: as listed;
                            // instance: reg 0 is `this`, so positions shift by 1)
                            boolean instance = op != 0x71;         // 0x71 = invoke-static
                            for (int ri = 0; ri < regs.size(); ri++) {
                                int[] fact = pending.get(regs.get(ri));
                                if (fact == null || pos - fact[1] > WINDOW) continue;
                                int argPos = instance ? ri - 1 : ri;
                                if (argPos < 0) continue;          // the string is `this` — not an arg
                                facts.putIfAbsent(fact[0],
                                        new String[] { rule[1], "arg " + argPos + " of " + rule[2] });
                            }
                        }
                    }
                }
            }
            pos++;
        }
        return facts;
    }

    /** Types for a value given usage facts (usage verdicts outrank lexical). */
    static List<Map<String, Object>> classify(String value, List<String[]> usage) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (usage != null)
            for (String[] u : usage) {
                String type = u[0], detail = u[1];
                if (out.stream().noneMatch(t -> t.get("name").equals(type)))
                    add(out, type, "usage", type + " · usage-confirmed: " + detail);
            }
        for (Map<String, Object> lex : lexical(value))
            if (out.stream().noneMatch(t -> t.get("name").equals(lex.get("name"))))
                out.add(lex);
        return out;
    }
}
