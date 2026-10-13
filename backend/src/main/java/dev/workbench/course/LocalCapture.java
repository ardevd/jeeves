package dev.workbench.course;

import dev.workbench.llm.AgentLoop;
import dev.workbench.server.AgentManager;
import dev.workbench.server.Workspace;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in standalone use of the existing capture journal. No principal, service key or exporter. */
public final class LocalCapture implements AutoCloseable {
    private final Capture capture;
    private final Workspace ws;
    private final AtomicLong annotationLoss=new AtomicLong();
    private final ThreadPoolExecutor history=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1024),r->{ Thread t=new Thread(r,"local-research-history"); t.setDaemon(true); return t; });

    public LocalCapture(File root,Workspace ws) throws IOException {
        this.ws=ws;
        capture=new Capture(root,null,"off",false,false);
    }
    private Capture.RunIdentity identity(String tid,String actor,String original,String provider,String model) {
        return new Capture.RunIdentity("local-unattributed",actor,"local-research",ws.executableSha(tid),null,
                original,tid+"@h"+ws.historyRevision(tid),ws.targetNameOf(tid),provider,model);
    }
    public AgentLoop.Capture forMission(AgentManager.Mission m) {
        String subject=AgentLoop.subjectOf(m.goal);
        if (subject!=null && m.target!=null) try {
            Map<String,Object> resolved=ws.resolveSymbol(m.target,subject);
            if (resolved.get("origSig")!=null && !Boolean.TRUE.equals(resolved.get("contradiction"))) subject=String.valueOf(resolved.get("origSig"));
        } catch (Exception ignored) {}
        String model=m.model;
        if (model!=null && m.provider!=null && model.startsWith(m.provider+":")) model=model.substring(m.provider.length()+1);
        return capture.forMission(m,identity(m.target,"llm:"+m.agentId,subject,m.provider,model));
    }
    public void onHistory(String tid,Map<String,Object> entry) {
        // Do not acquire agent/workspace locks on the Workspace listener thread.
        Map<String,Object> snapshot=new LinkedHashMap<>(entry);
        try { history.execute(()->{
            try {
                String original=null;
                try {
                    Map<String,Object> resolved=ws.engineFor(tid).resolve(String.valueOf(snapshot.getOrDefault("newAddr",snapshot.get("addr"))));
                    if (resolved.get("origSig")!=null) original=String.valueOf(resolved.get("origSig"));
                } catch (Exception ignored) {}
                capture.annotation("annotations-"+tid,identity(tid,String.valueOf(snapshot.get("actor")),original,null,null),snapshot,original);
            } catch (Exception error) { annotationLoss.incrementAndGet(); }
        }); } catch (java.util.concurrent.RejectedExecutionException error) { annotationLoss.incrementAndGet(); }
    }
    public Map<String,Object> status() {
        Map<String,Object> out=new LinkedHashMap<>(capture.status());
        out.put("enabled",true);out.put("capture_location","local");out.put("automatic_upload",false);
        out.put("attribution","local-unverified; course attribution comes from authenticated submission");
        out.put("scope","Jeeves agent requests/responses/tools and annotations; no external assistant conversations");
        out.put("runtime",Workspace.runtimeIdentity());out.put("annotation_queue",history.getQueue().size());
        out.put("annotation_loss",annotationLoss.get());
        return out;
    }
    public void close() {
        history.shutdown();
        try { if (!history.awaitTermination(5,TimeUnit.SECONDS)) annotationLoss.addAndGet(history.shutdownNow().size()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); annotationLoss.addAndGet(history.shutdownNow().size()); }
        capture.close();
    }
}
