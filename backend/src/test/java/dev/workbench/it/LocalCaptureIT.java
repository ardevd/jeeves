package dev.workbench.it;

import dev.workbench.course.LocalCapture;
import dev.workbench.llm.AgentLoop;
import dev.workbench.server.AgentManager;
import dev.workbench.server.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Calls capture callbacks directly, never a provider or model. */
public final class LocalCaptureIT {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getenv("WORKBENCH_HOME")).resolve("capture");
        Workspace ws=new Workspace();
        try (LocalCapture capture=new LocalCapture(root.toFile(),ws)) {
            AgentManager.Mission mission=new AgentManager.Mission();
            mission.id="fixture-m1";mission.agentId="fixture";mission.target="t1";
            mission.provider="fixture-direct";mission.model="fixture-direct:model-fixture";mission.goal="Inspect fixture";
            AgentLoop.Capture sink=capture.forMission(mission);
            String request=sink.logicalId(mission.id,1,1);
            sink.request(request,1,1,Map.of("model","model-fixture","messages",java.util.List.of(Map.of("content","private fixture prompt"))));
            sink.response(request,1,1,Map.of("model","returned-fixture","text","private fixture answer"),Map.of("status",200),1);
            sink.toolResult(request,1,"fixture-call","code",Map.of("symbol","Lfixture/Probe;->Ping(I)V"),"fixture result",true,14);
            sink.terminal("completed","fixture only");
            Map<String,Object> status=capture.status();
            if (!Boolean.FALSE.equals(status.get("automatic_upload")) || ((Number)status.get("journaled")).intValue()!=4)
                throw new AssertionError(status);
            if (Files.list(root.resolve("queue")).findAny().isPresent()) throw new AssertionError("Local mode queued automatic upload");
        }
        String journal=Files.readString(root.resolve("runs/fixture-m1/events.jsonl"));
        if (!journal.contains("fixture-direct") || !journal.contains("returned-fixture") || journal.contains("account_id")) throw new AssertionError("Local provenance lost or course principal invented");
        System.out.println("Local capture: 4 private events with provider/model/tool provenance; no provider, account or upload called");
    }
}
