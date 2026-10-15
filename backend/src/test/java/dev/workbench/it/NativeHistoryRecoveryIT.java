package dev.workbench.it;

import dev.workbench.device.DeviceManager;
import dev.workbench.engine.jeb.JebCore;
import dev.workbench.engine.jeb.JebEngine;
import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import dev.workbench.server.AgentManager;
import dev.workbench.server.EventBus;
import dev.workbench.server.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Three fresh JVMs on the authored fixture: save/undo/halt, reopen/save/redo/halt,
 * reopen/save/native-only verification. No model, server or production database. */
public final class NativeHistoryRecoveryIT {
    private static final String CLASS="Lfixture/Params;";
    private static final String METHOD="Lfixture/Params;->annotOnly(ILjava/lang/String;)I";
    private static final String LOCAL_METHOD="Lfixture/Params;->absent(I)I";
    private static final String PARAM_METHOD="Lfixture/Params;->debugOnly(ILjava/lang/String;)I";
    private static final String OBFUSCATED="Lfixture/1cq;";
    private static int checks;
    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS "+message);
    }
    private static void ready(dev.workbench.engine.Engine e) throws Exception {
        for (;;) {
            var info=e.info();
            if ("ready".equals(info.get("state"))) return;
            if ("error".equals(info.get("state"))) throw new AssertionError(info.toString());
            Thread.sleep(100);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    private static void verify(dev.workbench.engine.Engine e, boolean applied, String originalLocal, String originalParameter) throws Exception {
        check((applied ? "SavedClass" : "Params").equals(e.resolve(CLASS).get("name")),"class matches "+(applied?"redone":"undone")+" state");
        check((applied ? "savedMethod" : "annotOnly").equals(e.resolve(METHOD).get("name")),"method matches recovered state through stable identity");
        check(String.valueOf(e.decompile(CLASS,Map.of()).get("text")).contains("durable undo redo comment")==applied,"comment matches recovered state");
        check(e.locals(LOCAL_METHOD).stream().anyMatch(x -> (applied?"savedArgument":originalLocal).equals(map(x).get("name"))),"local matches recovered state");
        String parameter=String.valueOf(map(((List<?>)e.methodParameters(PARAM_METHOD).get("parameters")).get(0)).get("name"));
        check((applied?"savedCount":originalParameter).equals(parameter),"parameter mapping matches recovered state");
        check((applied?"ReadableOriginal":"1cq").equals(e.resolve(OBFUSCATED).get("name")),"obfuscated original name restores through validated undo");
    }
    public static void main(String[] args) throws Exception {
        if (!"--child".equals(args[0])) {
            Path out=Path.of(args[1]).toAbsolutePath(); Files.createDirectories(out);
            for (int stage=0;stage<3;stage++) {
                List<String> command=new ArrayList<>();
                command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
                command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                command.addAll(List.of("-cp",System.getProperty("java.class.path"),NativeHistoryRecoveryIT.class.getName(),"--child",String.valueOf(stage),Path.of(args[0]).toAbsolutePath().toString(),out.toString()));
                Process child=new ProcessBuilder(command).inheritIO().start();
                int status=child.waitFor();
                check(status==(stage<2?17+stage:0),"fresh child "+stage+" completed with expected exit "+status);
                if (stage<2) {
                    Path database=Path.of(Files.readString(out.resolve("database-path.txt")));
                    if (!database.normalize().startsWith(out.normalize())) throw new AssertionError("fixture DB escaped test output");
                    // This exact test child has exited. Never inspect or remove another owner's lock.
                    Files.deleteIfExists(Path.of(database+".lock"));
                }
            }
            System.out.println("Fresh-process undo/redo recovery sequence passed"); return;
        }
        int stage=Integer.parseInt(args[1]);
        Path fixture=Path.of(args[2]).toAbsolutePath(), out=Path.of(args[3]).toAbsolutePath();
        Files.createDirectories(out);
        Path input=out.resolve("UndoRedoFixture.dex"); if (!Files.exists(input)) Files.copy(fixture,input);
        Workspace ws=new Workspace(); ws.setWorkbenchRoot(out.resolve("home").toFile());
        ws.setAgents(new AgentManager(ws,new EventBus(),out.resolve("home").toFile()));
        ws.setDevices(new DeviceManager(out.resolve("home").toFile()));
        JebEngine nativeOnly=null;
        try {
            ws.openProject("UndoRedoFixture");
            String tid=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id")); ready(ws.engineFor(tid));
            Path database=Path.of(String.valueOf(ws.engineFor(tid).info().get("jdb2")));
            Files.writeString(out.resolve("database-path.txt"),database.toString());
            Map<String,Object> receipt;
            if (stage==0) {
                receipt=new LinkedHashMap<>();
                String originalLocal=String.valueOf(map(ws.engineFor(tid).locals(LOCAL_METHOD).get(0)).get("name"));
                receipt.put("originalLocal",originalLocal);
                String originalParameter=String.valueOf(map(((List<?>)ws.engineFor(tid).methodParameters(PARAM_METHOD).get("parameters")).get(0)).get("name"));
                receipt.put("originalParameter",originalParameter);
                List<String> ids=new ArrayList<>();
                ids.add(String.valueOf(ws.renameSymbol(tid,METHOD,"savedMethod","llm:owner",null,null).get("historyId")));
                ids.add(String.valueOf(ws.renameSymbol(tid,CLASS,"SavedClass","llm:owner",null,null).get("historyId")));
                ws.withTargetWrite(tid,()->{
                    var result=ws.engineFor(tid).comment(CLASS,"durable undo redo comment","llm:owner");
                    Map<String,Object> entry=new LinkedHashMap<>(result); entry.put("op","comment"); entry.put("actor","llm:owner");
                    entry.put("newAddr",result.get("addr")); entry.put("newText","durable undo redo comment");
                    ids.add(String.valueOf(ws.recordHistory(tid,entry).get("id"))); return null;
                });
                ws.withTargetWrite(tid,()->{
                    var local=ws.engineFor(tid).resolveLocal(LOCAL_METHOD,originalLocal);
                    var result=ws.engineFor(tid).renameLocal(LOCAL_METHOD,originalLocal,"savedArgument","llm:owner");
                    Map<String,Object> entry=new LinkedHashMap<>(result); entry.put("op","rename"); entry.put("actor","llm:owner");
                    entry.put("originalSymbol",local.get("originalSymbol")); entry.put("originalMethod",local.get("originalMethod"));
                    ids.add(String.valueOf(ws.recordHistory(tid,entry).get("id"))); return null;
                });
                ids.add(String.valueOf(ws.renameParameters(tid,PARAM_METHOD,List.of(new LinkedHashMap<>(Map.of("index",0,"name","savedCount"))),"analyst","human").get("historyId")));
                ids.add(String.valueOf(ws.renameSymbol(tid,OBFUSCATED,"ReadableOriginal","llm:owner",null,null).get("historyId")));
                receipt.put("ids",ids); Files.writeString(out.resolve("receipt.json"),Json.write(receipt));
                check(Boolean.TRUE.equals(ws.saveTarget(tid).get("saved")),"baseline changes saved natively");
                check(Files.exists(Path.of(database+".lock")),"original native lock retained after save");
                // Deliberately undo the owner class before its member/comment/local entries.
                for (int i : List.of(1,0,2,3,4,5)) ws.historyUndo(tid,ids.get(i));
                verify(ws.engineFor(tid),false,originalLocal,originalParameter);
                check(ws.historySince(tid,ids.size()).size()==ids.size(),"undo revisions are durable events distinct from original entries");
                check(((Number)ws.targetChanges(tid).get("symbols")).intValue()>0,"pending undo is visible as an unsaved change");
                System.out.println("Child undo checks: "+checks); Runtime.getRuntime().halt(17);
            }
            receipt=map(Json.parse(Files.readString(out.resolve("receipt.json"))));
            String originalLocal=String.valueOf(receipt.get("originalLocal"));
            String originalParameter=String.valueOf(receipt.get("originalParameter"));
            @SuppressWarnings("unchecked") List<String> ids=(List<String>)receipt.get("ids");
            if (stage==1) {
                verify(ws.engineFor(tid),false,originalLocal,originalParameter);
                check(((Number)ws.engineFor(tid).info().get("recoveredEdits")).intValue()==ids.size(),"fresh process replayed all saved-entry undo events");
                check(ws.historyRevision(tid)==ids.size()*2,"reversal revision survives restart without id reuse");
                check(Boolean.TRUE.equals(ws.saveTarget(tid).get("saved")),"undone state saved natively before redo");
                for (String id:ids) ws.historyRedo(tid,id);
                verify(ws.engineFor(tid),true,originalLocal,originalParameter);
                check(ws.historyRevision(tid)==ids.size()*3,"redo consumes new durable revisions");
                System.out.println("Child redo checks: "+checks); Runtime.getRuntime().halt(18);
            }
            verify(ws.engineFor(tid),true,originalLocal,originalParameter);
            check(((Number)ws.engineFor(tid).info().get("recoveredEdits")).intValue()==ids.size(),"fresh process replayed all redo events after a saved undo");
            check(ws.historyRevision(tid)==ids.size()*3,"redo revisions survive fresh process");
            try { ws.renameSymbol(tid,METHOD,"otherGuess","llm:other",null,null); throw new AssertionError("recovered redo lost rename claim"); }
            catch (OpException expected) { check("RENAME_CONFLICT".equals(expected.code) && "savedMethod".equals(expected.extra.get("currentName")),"recovered unsaved redo restores ownership with current name"); }
            check(Boolean.TRUE.equals(ws.saveTarget(tid).get("saved")),"recovered redone state saved");
            ws.historyUndo(tid,ids.get(1));
            ws.closeTarget(tid,false);
            String reopened=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id")); ready(ws.engineFor(reopened));
            check("SavedClass".equals(ws.engineFor(reopened).resolve(CLASS).get("name")),"discarded pending undo does not replay on reopen");
            check(Boolean.FALSE.equals(ws.historyUndone(reopened,ids.get(1))),"discard restores the saved applied history flag");
            check(ws.historyRevision(reopened)==ids.size()*3+1,"discard retains reversal audit revision");
            ws.closeTarget(reopened);
            nativeOnly=new JebEngine("verify",database.getFileName().toString(),database.toString(),null,database.toFile());
            nativeOnly.openAsync(); ready(nativeOnly);
            verify(nativeOnly,true,originalLocal,originalParameter);
            System.out.println("Child final checks: "+checks);
        } catch (Throwable failure) { failure.printStackTrace(System.out); throw failure; }
        finally { if(nativeOnly!=null)nativeOnly.close(); ws.closeAll(); JebCore.shutdown(); }
    }
}
