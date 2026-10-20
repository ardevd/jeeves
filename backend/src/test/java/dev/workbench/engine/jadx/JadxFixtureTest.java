package dev.workbench.engine.jadx;

import java.nio.file.*;
import java.util.*;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;

/** Synthetic DEX assertions with independently specified instruction offsets. */
public final class JadxFixtureTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jadx-contract-");
        Path src = Files.createDirectories(root.resolve("smali"));
        Files.writeString(src.resolve("Base.smali"), """
                .class public abstract Lfixture/Base;
                .super Ljava/lang/Object;
                .field protected count:I
                .field public static final LABEL:Ljava/lang/String; = "constant-only"
                .method public abstract work()V
                .end method
                """);
        Files.writeString(src.resolve("Child.smali"), """
                .class public Lfixture/Child;
                .super Lfixture/Base;
                .method public work()V
                    .registers 1
                    return-void
                .end method
                .method public static run()V
                    .registers 1
                    const-string v0, "https://example.test/api"
                    invoke-static {v0}, Lfixture/Child;->sink(Ljava/lang/String;)V
                    invoke-static {v0}, Lfixture/Child;->sink(Ljava/lang/String;)V
                    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
                    return-void
                .end method
                .method public static sink(Ljava/lang/String;)V
                    .registers 1
                    return-void
                .end method
                """);
        SmaliOptions options = new SmaliOptions(); options.outputDexFile = root.resolve("classes.dex").toString(); options.jobs = 1;
        check(Smali.assemble(options, src.toString()), "assemble fixture");
        var engine = new JadxEngine("fixture", "fixture", options.outputDexFile, new java.io.File(options.outputDexFile), root.resolve("sidecar").toFile());
        try {
            engine.openAsync(); long deadline = System.nanoTime() + 30_000_000_000L;
            while ("loading".equals(engine.info().get("state")) && System.nanoTime() < deadline) Thread.sleep(20);
            check("ready".equals(engine.info().get("state")), "fixture ready");
            String sink = "Lfixture/Child;->sink(Ljava/lang/String;)V";
            List<Object> refs = engine.xrefsTo(sink);
            check(refs.size() == 2, "both invoke sites retained");
            check(((Map<?, ?>) refs.get(0)).get("offset").equals(2) && ((Map<?, ?>) refs.get(1)).get("offset").equals(5), "raw DEX code-unit offsets 2 and 5");
            List<Object> callers = engine.callers(sink);
            check(callers.size() == 1 && ((List<?>) ((Map<?, ?>) callers.getFirst()).get("sites")).size() == 2, "caller dedup retains sites");
            check(engine.implementations("Lfixture/Base;->work()V").size() == 1, "abstract method implementation");
            Map<?, ?> inherited = (Map<?, ?>) engine.outline("Lfixture/Child;", true).get("inherited");
            check(((List<?>) inherited.get("fields")).size() == 2, "inherited field inventory");
            List<?> strings = (List<?>) engine.strings("example.test", List.of(), false, 0, 100, null).get("rows");
            check(strings.size() == 1, "string inventory");
            List<?> constants = (List<?>) engine.strings("constant-only", List.of(), false, 0, 100, null).get("rows");
            check(constants.size() == 1, "static string constant inventory");
            String constantId = ((Map<?, ?>) constants.getFirst()).get("id").toString();
            Map<?, ?> constantRef = (Map<?, ?>) engine.stringXrefs(constantId).getFirst();
            check("STATIC_VALUE".equals(constantRef.get("type")) && !constantRef.containsKey("offset") && !constantRef.containsKey("addr"),
                    "static constant has no invented instruction offset or empty addr");
            check("fixture.Base.LABEL".equals(((Map<?, ?>) constants.getFirst()).get("firstReferrer")),
                    "static constant firstReferrer is formatted owner.name");
            String external = "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V";
            check(Boolean.TRUE.equals(engine.resolveStrict(external).get("external")), "external method resolution");
            check(engine.callers(external).size() == 1, "external method callers");
            String stringId = ((Map<?, ?>) strings.getFirst()).get("id").toString();
            Map<?, ?> stringRef = (Map<?, ?>) engine.stringXrefs(stringId).getFirst();
            check(stringRef.get("offset").equals(0) && !stringRef.containsKey("addr")
                    && "Lfixture/Child;->run()V".equals(stringRef.get("method")),
                    "string site offset and method without empty addr");
            List<Object> searchStr = engine.search("example.test", Map.of("kind", "strings"));
            check(!searchStr.isEmpty(), "search strings hit");
            Map<?, ?> strHit = (Map<?, ?>) ((List<?>) ((Map<?, ?>) searchStr.getFirst()).get("hits")).getFirst();
            check("Lfixture/Child;->run()V".equals(strHit.get("addr")) && strHit.get("context") instanceof List<?> cl && !cl.isEmpty(),
                    "search strings hit carries navigable addr and context");
            List<Object> callees = engine.callees("Lfixture/Child;->run()V");
            check(callees.stream().anyMatch(c -> "sink".equals(((Map<?, ?>) c).get("name")) && "fixture.Child".equals(((Map<?, ?>) c).get("owner"))),
                    "callees row carries target name and owner");
            Map<String, Object> rendered = engine.decompile("Lfixture/Child;", Map.of("tokens", true));
            String text = rendered.get("text").toString();
            for (Object obj : (List<?>) rendered.get("tokens")) {
                Map<?, ?> token = (Map<?, ?>) obj;
                String line = text.split("\n", -1)[((Number) token.get("line")).intValue() - 1];
                int start = ((Number) token.get("cs")).intValue(), end = ((Number) token.get("ce")).intValue();
                check(start >= 0 && end > start && end <= line.length(), "token span within rendered text");
            }
            int at = text.indexOf("sink(");
            check(at > 0 && "method".equals(engine.hover("Lfixture/Child;", at).get("kind")), "hover uses method annotation");
        } finally { engine.close(); }
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); System.out.println("PASS " + label); }
}
