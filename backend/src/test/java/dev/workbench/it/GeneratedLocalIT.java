package dev.workbench.it;

import com.pnfsoftware.jeb.core.RuntimeProjectUtil;
import com.pnfsoftware.jeb.core.units.code.android.*;
import com.pnfsoftware.jeb.core.units.code.java.*;
import com.pnfsoftware.jeb.core.util.DecompilerHelper;
import dev.workbench.engine.jeb.*;
import dev.workbench.registry.OpException;
import dev.workbench.server.NamingCoverage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Public-SDK synthetic variables, distinct inventory, explicit refusal and native persistence. */
public final class GeneratedLocalIT {
    private static final String METHOD = "Lfixture/Branches;->loop(I)I";
    private static int checks;
    private static synchronized void check(boolean ok,String why) {
        if(!ok)throw new AssertionError(why);checks++;System.out.println("PASS "+why);
    }
    private static void ready(JebEngine engine)throws Exception {
        for(;;){var info=engine.info();if("ready".equals(info.get("state")))return;
            if("error".equals(info.get("state")))throw new AssertionError(info);Thread.sleep(100);}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static List<Map<String,Object>> generated(JebEngine engine)throws Exception {
        return engine.locals(METHOD).stream().map(GeneratedLocalIT::map)
                .filter(row->Set.of("probeFirst","probeSecond").contains(row.get("name"))).toList();
    }
    private static void refuse(JebEngine engine,String name)throws Exception {
        try {engine.renameLocal(METHOD,name,"betterName","llm:fixture");throw new AssertionError("Unsupported rename succeeded");}
        catch(OpException e){check("E_UNSUPPORTED".equals(e.code)&&"JEB_NO_ACTIONABLE_ITEM".equals(e.extra.get("reason")),
                "generated local reports an explicit SDK limitation, not a collision");}
        try {engine.resolveLocal(METHOD,name);throw new AssertionError("Nonactionable variable resolved as rename target");}
        catch(OpException e){check("E_UNSUPPORTED".equals(e.code),"ownership lookup cannot treat zero as a native rename identity");}
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]),input=output.resolve("GeneratedLocal.dex");Files.copy(Path.of(args[0]),input);
        var engine=new JebEngine("generated-fixture","fixture",input.toString(),input.toFile(),output.resolve("GeneratedLocal.jdb2").toFile());
        try {
            engine.openAsync();ready(engine);engine.locals(METHOD);
            boolean injected=false;
            for(var project:JebCore.engines().getProjects())for(var dex:RuntimeProjectUtil.findUnits(project,IDexUnit.class)) {
                var method=dex.getMethod(METHOD);if(method==null||!method.isInternal())continue;
                var dec=(IDexDecompilerUnit)DecompilerHelper.getDecompiler(dex);
                var source=(IJavaSourceUnit)dec.decompileToUnit(dex.getClass("Lfixture/Branches;").getAddress());
                var root=(IJavaClass)source.getASTElement();
                var jm=root.getMethods().stream().filter(m->METHOD.equals(m.getSignature())).findFirst().orElseThrow();
                check(jm!=null,"public SDK finds the exact fixture AST");
                for(String name:List.of("probeFirst","probeSecond")) {
                    var definition=jm.getIdentifierManager().createDefinition(jm.getReturnType(),name);
                    jm.getBody().insert(0,definition);
                    check(!dec.setIdentifierName(definition.getIdentifier(),"unsupportedRename",true,true),
                            "public API refuses an identifier without a native location");
                }
                dec.notifyGenericChange();
                injected=true;
            }
            check(injected,"synthetic variables added only to the isolated authored fixture");
            var rows=generated(engine);
            check(rows.size()==2,"two distinct zero-ID variables both appear in the inventory: "+engine.locals(METHOD));
            check(rows.stream().allMatch(r->"0".equals(r.get("itemId"))&&Boolean.FALSE.equals(r.get("renameableLocal"))
                    &&"rendered-only".equals(r.get("identityKind"))&&r.containsKey("renameUnavailableReason")),
                    "inventory labels both variables as nonactionable with a reason");
            check(rows.stream().map(r->r.get("originalSymbol")).distinct().count()==2,"zero-ID variables have distinct inventory labels");
            for(var row:rows)refuse(engine,String.valueOf(row.get("name")));
            var inventory=NamingCoverage.inventory(engine,METHOD);
            check(((List<?>)inventory.get("inventoryErrors")).isEmpty(),"coverage inventories unsupported variables without hiding them");
            var variables=((List<?>)inventory.get("variables")).stream().map(GeneratedLocalIT::map).toList();
            check(variables.stream().filter(r->Set.of("probeFirst","probeSecond").contains(r.get("name")))
                    .allMatch(r->Boolean.FALSE.equals(r.get("renameableLocal"))),"coverage preserves native renameability");
            String fingerprint=String.valueOf(inventory.get("inventoryFingerprint"));
            check(Boolean.TRUE.equals(engine.save().get("saved")),"native fixture save succeeds");
            Path database=Path.of(String.valueOf(engine.info().get("jdb2")));engine.close();
            Path copy=output.resolve("independent");Files.createDirectories(copy);Path copied=copy.resolve(database.getFileName());
            Files.copy(database,copied);Files.copy(Path.of(database+".key"),Path.of(copied+".key"));
            var reopened=new JebEngine("generated-independent","native",copied.toString(),null,copied.toFile());
            try {
                reopened.openAsync();ready(reopened);
                check(!reopened.info().containsKey("recoveredEdits"),"independent reopen has no history replay");
                check(generated(reopened).size()==2,"both generated identifiers survive native reopening");
                var pool=Executors.newFixedThreadPool(8);
                try {
                    List<Callable<Boolean>> readers=new ArrayList<>();
                    for(int i=0;i<8;i++)readers.add(()->fingerprint.equals(NamingCoverage.inventory(reopened,METHOD).get("inventoryFingerprint")));
                    for(var result:pool.invokeAll(readers))check(result.get(),"parallel reader preserves names and generated inventory labels");
                } finally {pool.shutdown();}
                for(var row:generated(reopened))refuse(reopened,String.valueOf(row.get("name")));
            } finally {reopened.discardUnsaved();reopened.close();}
            System.out.println("GeneratedLocalIT: "+checks+" checks passed");
        } catch(Exception | AssertionError failure) {failure.printStackTrace(System.out);throw failure;}
        finally {engine.discardUnsaved();engine.close();JebCore.shutdown();}
    }
}
