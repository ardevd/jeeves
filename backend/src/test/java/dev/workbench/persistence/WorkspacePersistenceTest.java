package dev.workbench.persistence;

import dev.workbench.engine.Engine;
import dev.workbench.registry.OpException;
import dev.workbench.server.Workspace;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Injected storage/close failures in the application's own mock seam; no SDK internals. */
public final class WorkspacePersistenceTest {
    private static int checks;
    private static void check(boolean value,String why) { if(!value)throw new AssertionError(why); checks++;System.out.println("PASS "+why); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static OpException failure(Work work) throws Exception {
        try { work.run(); } catch(OpException expected) { return expected; }
        throw new AssertionError("Expected failure");
    }
    private static final class Fixture {
        final Workspace ws=new Workspace();
        final Path root=Files.createTempDirectory("jeeves-workspace-persistence-");
        final Path artifact=root.resolve("Fixture.dex"), journal=root.resolve("Fixture.dex.workbench/history.json");
        final Path parked=root.resolve("history.before-failure.json");
        volatile boolean blockAfterRename, refuseClose;
        volatile int saves, closes;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            if(!"1".equals(System.getenv("WORKBENCH_MOCK")))throw new AssertionError("mock fixture explicitly required");
            Files.writeString(artifact,"authored fixture placeholder"); ws.setWorkbenchRoot(root.resolve("home").toFile());
            var tf=Workspace.class.getDeclaredField("targets");tf.setAccessible(true);
            Map<String,Object> targets=(Map<String,Object>)tf.get(ws);
            var constructor=targets.get("t1").getClass().getDeclaredConstructor(String.class,String.class,String.class);constructor.setAccessible(true);
            targets.put("t1",constructor.newInstance("t1","Fixture.dex",artifact.toString()));
            var ef=Workspace.class.getDeclaredField("engines");ef.setAccessible(true);
            Map<String,Engine> engines=(Map<String,Engine>)ef.get(ws);Engine original=engines.get("t1");
            engines.put("t1",(Engine)Proxy.newProxyInstance(Engine.class.getClassLoader(),new Class<?>[]{Engine.class},(proxy,method,args)->{
                if("save".equals(method.getName())) { saves++;Files.writeString(root.resolve("Fixture.jdb2"),"saved fixture");return Map.of("saved",true); }
                if("close".equals(method.getName())) { closes++;if(refuseClose)throw new IllegalStateException("injected native close failure");return null; }
                try {
                    Object result=method.invoke(original,args);
                    if("rename".equals(method.getName()) && blockAfterRename) { blockAfterRename=false;block(); }
                    return result;
                } catch(InvocationTargetException ex) {throw ex.getCause();}
            }));
        }
        void block() throws Exception { Files.move(journal,parked);Files.createDirectory(journal);Files.writeString(journal.resolve("block"),"injected storage failure"); }
        void repair() throws Exception {Files.delete(journal.resolve("block"));Files.delete(journal);Files.move(parked,journal);}
        String name() throws Exception {return String.valueOf(ws.engineFor("t1").resolve("MessageSyncHandler").get("name"));}
    }
    public static void main(String[] args) throws Exception {
        Fixture f=new Fixture();f.blockAfterRename=true;
        OpException rejected=failure(()->f.ws.renameSymbol("t1","MessageSyncHandler","AcceptedInMemory","llm:a",null,null));
        check("E_IO".equals(rejected.code) && Boolean.TRUE.equals(rejected.extra.get("applied")) && Boolean.FALSE.equals(rejected.extra.get("durable")),"failed journal reports an applied but not durable edit");
        check("AcceptedInMemory".equals(f.name()),"failed history persistence retains the engine edit in memory");
        check(f.ws.historyList("t1",0).size()==1,"failed history persistence retains its recovery record in memory");
        long started=((Number)f.ws.saveTargetAsync("t1").get("startedAt")).longValue();
        Map<String,Object> status;
        do {status=f.ws.saveStatus("t1");if(Boolean.TRUE.equals(status.get("saving")))Thread.sleep(10);}while(Boolean.TRUE.equals(status.get("saving")));
        Map<?,?> last=(Map<?,?>)status.get("last");
        check(last!=null && Boolean.FALSE.equals(last.get("ok")) && ((Number)last.get("at")).longValue()>=started,"async save exposes fresh failure when history cannot flush");
        check(f.saves==0,"native save is never called after failed journal flush");
        boolean[] ran={false};failure(()->f.ws.withTargetWrite("t1",()->{ran[0]=true;return null;}));
        check(!ran[0],"comment/local transaction cannot mutate past an earlier failed journal");
        failure(()->f.ws.closeTarget("t1"));
        check(f.closes==0 && f.ws.engineFor("t1")!=null,"close refuses to detach a target whose journal cannot be persisted");
        f.repair();
        OpException collision=failure(()->f.ws.renameSymbol("t1","MessageSyncHandler","LosingName","llm:b",null,null));
        check("RENAME_CONFLICT".equals(collision.code) && "AcceptedInMemory".equals(collision.extra.get("currentName")),"failed journal did not release the accepted rename claim");
        check(Boolean.TRUE.equals(f.ws.saveTarget("t1").get("saved")) && f.saves==1,"repair then save flushes retained history and native state");
        String id=String.valueOf(((Map<?,?>)f.ws.historyList("t1",0).get(0)).get("id"));
        f.blockAfterRename=true;
        OpException undoFailure=failure(()->f.ws.historyUndo("t1",id));
        check(Boolean.TRUE.equals(undoFailure.extra.get("applied")) && Boolean.FALSE.equals(undoFailure.extra.get("durable")),"failed undo journal reports actual applied state");
        check("MessageSyncHandler".equals(f.name()) && Boolean.TRUE.equals(f.ws.historyUndone("t1",id)),"failed undo retains changed name and reversal flag in memory");
        f.repair();f.ws.saveTarget("t1");
        check(Files.readString(f.journal).contains("reversals"),"retry saves the previously failed reversal record");
        f.refuseClose=true;
        OpException closeFailure=failure(()->f.ws.closeTarget("t1"));
        check(Boolean.TRUE.equals(closeFailure.extra.get("closed")) && Boolean.FALSE.equals(closeFailure.extra.get("saved")),"native close failure reaches synchronous caller explicitly");

        Fixture order=new Fixture();
        String firstId=String.valueOf(order.ws.renameSymbol("t1","MessageSyncHandler","FirstName","llm:a",null,null).get("historyId"));
        order.ws.renameSymbol("t1","MessageSyncHandler","SecondName","llm:a",null,null);
        order.ws.historyUndo("t1",firstId); order.ws.historyRedo("t1",firstId);
        OpException reordered=failure(()->order.ws.renameSymbol("t1","MessageSyncHandler","WrongOwner","llm:b",null,null));
        check("RENAME_CONFLICT".equals(reordered.code) && "FirstName".equals(reordered.extra.get("currentName")),"out-of-order redo claims the actual latest name rather than a later-created stale row");

        Fixture race=new Fixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closing=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var edit=pool.submit(()->race.ws.withTargetWrite("t1",()->{
                entered.countDown();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw OpException.io("interrupted fixture","");}
                return race.ws.renameSymbol("t1","MessageSyncHandler","BeforeClose","llm:writer",null,null);
            }));
            check(entered.await(10,TimeUnit.SECONDS),"edit holds target gate before close starts");
            var close=pool.submit(()->{closing.countDown();race.ws.closeTarget("t1");return null;});
            check(closing.await(10,TimeUnit.SECONDS) && race.closes==0,"close cannot pass an active target edit");
            release.countDown();edit.get(10,TimeUnit.SECONDS);close.get(10,TimeUnit.SECONDS);
            check(race.closes==1 && Files.readString(race.journal).contains("BeforeClose"),"accepted edit is durable before close removes its target");
        } finally {release.countDown();pool.shutdownNow();}
        Fixture saving=new Fixture();
        CountDownLatch firstEntered=new CountDownLatch(1), secondEntered=new CountDownLatch(1), firstRelease=new CountDownLatch(1), secondRelease=new CountDownLatch(1);
        var field=Workspace.class.getDeclaredField("engines");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Engine> engines=(Map<String,Engine>)field.get(saving.ws);
        Engine original=engines.get("t1");var calls=new java.util.concurrent.atomic.AtomicInteger();
        engines.put("t1",(Engine)Proxy.newProxyInstance(Engine.class.getClassLoader(),new Class<?>[]{Engine.class},(proxy,method,values)->{
            if("save".equals(method.getName())) {
                int call=calls.incrementAndGet();(call==1?firstEntered:secondEntered).countDown();(call==1?firstRelease:secondRelease).await();
            }
            try{return method.invoke(original,values);}catch(InvocationTargetException ex){throw ex.getCause();}
        }));
        var savers=Executors.newFixedThreadPool(2);
        try {
            var first=savers.submit(()->saving.ws.saveTarget("t1"));check(firstEntered.await(10,TimeUnit.SECONDS),"first synchronous save reached the native writer");
            var second=savers.submit(()->saving.ws.saveTarget("t1"));
            // Wait for the second reservation, not an arbitrary delay.
            var pending=Workspace.class.getDeclaredField("pendingSaves");pending.setAccessible(true);
            var outcomes=Workspace.class.getDeclaredField("saveOutcomes");outcomes.setAccessible(true);
            for(;;) {synchronized(outcomes.get(saving.ws)){if(((Map<?,?>)pending.get(saving.ws)).get("t1").equals(2))break;}Thread.yield();}
            firstRelease.countDown();check(secondEntered.await(10,TimeUnit.SECONDS),"second requested save starts after first transaction");
            first.get(10,TimeUnit.SECONDS);
            check(Boolean.TRUE.equals(saving.ws.saveStatus("t1").get("saving")),"first save completion cannot falsely complete a queued second save");
            secondRelease.countDown();second.get(10,TimeUnit.SECONDS);
            check(Boolean.FALSE.equals(saving.ws.saveStatus("t1").get("saving")) && saving.saves==2,"saving becomes false only when both accepted saves finish");
        } finally {firstRelease.countDown();secondRelease.countDown();savers.shutdownNow();}
        System.out.println("Workspace persistence checks passed: "+checks);
    }
}
