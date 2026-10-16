package dev.workbench.it;

import dev.workbench.engine.jeb.JebCore;
import dev.workbench.engine.jeb.JebEngine;
import dev.workbench.registry.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Real JEB renderer regression: operand commas must never become part of a callee identity. */
public final class CallSiteCalleeIT {
    private static int checks;
    private static final String CALLER = "Lfixture/RenameFamily;->call(Lfixture/RenameFamily$Reader;I)I";
    private static final String CALLEE = "Lfixture/RenameFamily$Reader;->a(I)I";

    private static void check(boolean value, String message) {
        if (!value) {
            System.out.println("FAIL " + message);
            throw new AssertionError(message);
        }
        checks++;
        System.out.println("PASS " + message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private static void ready(JebEngine engine) throws Exception {
        for (;;) {
            Map<String, Object> info = engine.info();
            if ("ready".equals(info.get("state"))) return;
            if ("error".equals(info.get("state"))) throw new AssertionError(info.toString());
            Thread.sleep(100);
        }
    }

    private static Map<String, Object> firstCall(JebEngine engine, String method) throws Exception {
        for (Object item : (List<?>) engine.smali(method).get("rows")) {
            Map<String, Object> row = map(item);
            if (String.valueOf(row.get("text")).stripLeading().startsWith("invoke-")) {
                long offset = Long.parseLong(String.valueOf(row.get("offset")), 16);
                return engine.callSite(method, offset, Map.of("java", false));
            }
        }
        throw new AssertionError("No invocation in authored fixture method " + method);
    }

    private static Map<String, Object> callee(Map<String, Object> result, Path evidence) throws Exception {
        Files.writeString(evidence, Json.write(result));
        Map<String, Object> site = map(result.get("site"));
        check("call".equals(site.get("kind")), "instruction is classified as a call");
        return map(site.get("callee"));
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        Path artifact = output.resolve("CallSiteCalleeFixture.dex");
        Files.copy(Path.of(args[0]).toAbsolutePath(), artifact);
        JebEngine engine = null;
        try {
            engine = new JebEngine("call-site-callee", artifact.getFileName().toString(),
                    artifact.toString(), artifact.toFile(), output.resolve("CallSiteCalleeFixture.jdb2").toFile());
            engine.openAsync();
            ready(engine);

            Map<String, Object> internal = callee(firstCall(engine, CALLER), output.resolve("internal-before.json"));
            check(CALLEE.equals(internal.get("ref")), "internal callee ref is the exact descriptor, without an operand comma: " + internal);
            check(Boolean.TRUE.equals(internal.get("internal")), "defined interface method is internal");
            check(!Boolean.TRUE.equals(internal.get("external")), "defined interface method is not mislabeled external");
            check(CALLEE.equals(internal.get("origSig")), "internal callee retains original identity");
            check(engine.resolve(CALLEE).get("addr").equals(internal.get("addr")), "callee address agrees with independent symbol resolution");

            Map<String, Object> external = callee(firstCall(engine, "Lfixture/RenameFamily$Base;-><init>()V"),
                    output.resolve("external.json"));
            check("Ljava/lang/Object;-><init>()V".equals(external.get("ref")), "external constructor ref also excludes the operand comma");
            check(!Boolean.TRUE.equals(external.get("internal")), "framework constructor remains external");
            check(Boolean.TRUE.equals(external.get("external")) || Boolean.FALSE.equals(external.get("internal")),
                    "framework classification is explicitly external");

            engine.rename(CALLEE, "readValue", "human");
            Map<String, Object> renamed = callee(firstCall(engine, CALLER), output.resolve("internal-after-rename.json"));
            check(CALLEE.equals(renamed.get("ref")) && CALLEE.equals(renamed.get("origSig")),
                    "original callee identity survives a display-name change");
            check("readValue".equals(renamed.get("name")), "callee metadata reports the current method name");
            check(engine.resolve(CALLEE).get("addr").equals(renamed.get("addr")), "renamed callee address resolves correctly");
            check(Boolean.TRUE.equals(renamed.get("internal")), "renamed internal method is not mislabeled external");
            System.out.println("Call-site callee regression: " + checks + " checks passed");
        } finally {
            if (engine != null) engine.close();
            JebCore.shutdown();
        }
    }
}
