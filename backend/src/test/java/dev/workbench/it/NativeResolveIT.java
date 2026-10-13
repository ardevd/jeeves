package dev.workbench.it;

import dev.workbench.registry.OpException;
import dev.workbench.runs.NativeSymbols;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Native-symbol resolution IT (plain main-class, no JUnit): exercises the idalib name→offset bridge
 * end-to-end — {@link NativeSymbols#resolve} → idalib_resolve.py → headless IDA — against a local
 * libcrypto.so. Its load-bearing assertion is the H1 SAFETY GATE: a data global must resolve to null
 * (never an installable native bp); only a function ENTRY yields an offset. Also checks an unknown
 * name is null and that a re-resolve is stable (the in-process cache).
 *
 * Environment-gated like the rest of the suite: SKIPs (exit 0) when idalib is unavailable (E_UNSUPPORTED)
 * or the .so fixture is missing, so it is safe to run anywhere; on a licensed-IDA box with the pulled
 * fixture it is a real regression test. Fixture: $WORKBENCH_TEST_SO, else backend/.workbench/pulled/
 * libcrypto.so (the device.pull home). Run: gradle nativeResolveIT
 */
public final class NativeResolveIT {

    private static int failures = 0, checks = 0;

    public static void main(String[] args) {
        File so = fixture();
        if (so == null) {
            System.out.println("SKIP: no libcrypto.so fixture (set WORKBENCH_TEST_SO, or pull one to "
                    + "backend/.workbench/pulled/)");
            return;   // exit 0 — a missing fixture is not a failure
        }
        System.out.println("fixture: " + so.getAbsolutePath());

        Map<String, String> res;
        try {
            res = NativeSymbols.resolve(so.getAbsolutePath(),
                    List.of("aes_nohw_encrypt", "OPENSSL_armcap_P", "definitely_not_a_symbol_zzz"));
        } catch (OpException e) {
            if ("E_UNSUPPORTED".equals(e.code)) {   // no idapro python / IDADIR on this box — skip, don't fail
                System.out.println("SKIP: idalib unavailable — " + e.getMessage());
                return;
            }
            System.out.println("FAIL: resolve threw " + e.code + " — " + e.getMessage());
            System.exit(1);
            return;
        }

        // a real function entry resolves to a module-base offset
        String fn = res.get("aes_nohw_encrypt");
        check(fn != null && fn.matches("0x[0-9a-fA-F]+"), "function entry aes_nohw_encrypt → " + fn);
        if (fn != null && !"0x90a9c".equals(fn))
            System.out.println("  note: offset " + fn + " differs from the demonstrated 0x90a9c "
                    + "(a different libcrypto.so build — not a failure)");

        // H1 SAFETY GATE: OPENSSL_armcap_P is a DATA global, not a function entry. get_name_ea_simple
        // resolves it, but the resolver must reject it — installing a native bp at a data address is
        // never valid. This is the regression guard for the review's HIGH finding.
        check(res.get("OPENSSL_armcap_P") == null, "data global OPENSSL_armcap_P → null (H1: not hookable)");
        check(res.get("definitely_not_a_symbol_zzz") == null, "unknown name → null");

        // the in-process cache returns the same answer on a re-resolve
        try {
            Map<String, String> again = NativeSymbols.resolve(so.getAbsolutePath(), List.of("aes_nohw_encrypt"));
            check(fn != null && fn.equals(again.get("aes_nohw_encrypt")), "cached re-resolve is stable");
        } catch (OpException e) {
            check(false, "cached re-resolve threw " + e.code);
        }

        // build-id parse — the Java side of the safety guard (the agent reads the same note from the
        // loaded module and refuses a mismatch). The pulled copy carries a GNU build-id.
        String bid = NativeSymbols.buildId(so);
        check(bid != null && bid.matches("[0-9a-f]{16,}"), "buildId(" + so.getName() + ") = " + bid);

        System.out.println();
        if (failures > 0) {
            System.out.println("NativeResolve IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("NativeResolve IT PASSED: " + checks + " checks green");
    }

    /** The .so to resolve against: an explicit override, else the dev pull home. */
    private static File fixture() {
        String env = System.getenv("WORKBENCH_TEST_SO");
        if (env != null && !env.isBlank() && new File(env).isFile()) return new File(env);
        for (String rel : new String[] { "backend/.workbench/pulled/libcrypto.so", ".workbench/pulled/libcrypto.so" }) {
            File f = new File(rel);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }
}
