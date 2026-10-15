package dev.workbench.persistence;

import dev.workbench.server.HistoryJournal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted event ordering and checkpoint/discard semantics, independent of the SDK. */
public final class HistoryJournalTest {
    private static int checks;
    private static void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
        checks++; System.out.println("PASS " + why);
    }
    private static Instant at(int n) { return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n); }
    private static Map<String,Object> entry(int n, String from, String to) {
        Map<String,Object> e = new LinkedHashMap<>();
        e.put("id","t1-h"+n); e.put("ts",at(n).toString()); e.put("op","rename"); e.put("kind","class");
        e.put("addr","Lfixture/"+from+";"); e.put("newAddr","Lfixture/"+to+";"); e.put("originalSymbol","Lfixture/Original;");
        e.put("oldName",from); e.put("newName",to); e.put("actor","llm:owner"); e.put("undone",false); e.put("journalVersion",2);
        return e;
    }
    private static void reverse(Map<String,Object> e, int n, String op) {
        HistoryJournal.appendReversal(e,Map.of("id","t1-h"+n,"ts",at(n).toString(),"op",op,"actor","human","of",e.get("id")));
        e.put("undone","undo".equals(op));
    }
    public static void main(String[] args) {
        Map<String,Object> first=entry(1,"Original","First");
        reverse(first,3,"undo");
        var undo=HistoryJournal.replay(List.of(first),at(2));
        check(undo.size()==1 && "Original".equals(undo.get(0).get("newName")),"saved rename followed by unsaved undo replays its inverse");
        check("Lfixture/Original;".equals(undo.get(0).get("addr")),"inverse addresses the stable symbol rather than stale display name");
        reverse(first,5,"redo");
        var redo=HistoryJournal.replay(List.of(first),at(4));
        check(redo.size()==1 && "First".equals(redo.get(0).get("newName")),"saved undo followed by unsaved redo replays the forward write");
        check(HistoryJournal.lastChange(first).equals(at(5)),"redo advances the timestamp used to rebuild unsaved ownership");
        Map<String,Object> second=entry(4,"Original","Second");
        var chronological=HistoryJournal.replay(List.of(first,second),at(2));
        check(chronological.stream().map(x->x.get("newName")).toList().equals(List.of("Original","Second","First")),"interleaved entries and reversals replay in execution order");
        check(HistoryJournal.replay(List.of(first),Instant.MIN).size()==3,"rekey reconstruction includes initial write, undo and redo");
        var discard=HistoryJournal.discardAfter(List.of(first),at(4));
        check(Boolean.TRUE.equals(discard.get(0).get("undone")),"discarding a pending redo restores the saved undone flag");
        check(HistoryJournal.replay(discard,at(4)).isEmpty(),"discarded redo cannot resurrect on reopen");
        check(HistoryJournal.replay(discard,Instant.MIN).size()==2,"discard keeps earlier saved apply and undo for rekey reconstruction");
        var discardUndo=HistoryJournal.discardAfter(List.of(first),at(2));
        check(!Boolean.TRUE.equals(discardUndo.get(0).get("undone")),"discarding pending reversals restores the saved applied flag");
        check(HistoryJournal.replay(discardUndo,at(2)).isEmpty(),"discard removes both unsaved reversal actions");
        Map<String,Object> legacy=entry(1,"Original","Legacy"); legacy.remove("journalVersion"); legacy.put("undone",true);
        check(HistoryJournal.replay(List.of(legacy),Instant.MIN).isEmpty(),"legacy undone entries stay skipped before new reversal metadata exists");
        reverse(legacy,3,"redo");
        check(HistoryJournal.replay(List.of(legacy),Instant.MIN).size()==1,"first redo of a legacy undone entry does not invent an initial applied write");
        Map<String,Object> comment=entry(1,"","" ); comment.put("op","comment"); comment.put("oldText","prior"); comment.put("newText","new");
        reverse(comment,3,"undo");
        check("prior".equals(HistoryJournal.replay(List.of(comment),at(2)).get(0).get("newText")),"comment undo restores exact previous text");
        Map<String,Object> params=entry(1,"","" ); params.put("kind","parameters");
        params.put("changes",List.of(Map.of("index",0,"oldName","v0","newName","count","oldSource","engine"),Map.of("index",1,"oldName","savedLabel","newName","label","oldSource","analyst")));
        reverse(params,3,"undo");
        var parameterEdits=(List<?>)HistoryJournal.replay(List.of(params),at(2)).get(0).get("parameterMutations");
        check(parameterEdits.equals(List.of(Map.of("index",0,"clear",true),Map.of("index",1,"name","savedLabel"))),"parameter undo clears engine defaults and restores analyst assignments");
        Map<String,Object> local=entry(1,"arg1","count"); local.put("kind","local"); local.put("originalMethod","Lfixture/Original;->run(I)I");
        reverse(local,3,"undo");
        var localUndo=HistoryJournal.replay(List.of(local),at(2)).get(0);
        check("Lfixture/Original;->run(I)I".equals(localUndo.get("addr")) && "count".equals(localUndo.get("oldName")) && "arg1".equals(localUndo.get("newName")),"local undo selects current local in canonical method");
        Map<String,Object> precise=entry(1,"Original","Precise"); precise.put("ts",at(2).plusNanos(5).toString());
        check(HistoryJournal.replay(List.of(precise),at(2)).size()==1,"an edit later in the same millisecond is not truncated away");
        System.out.println("History journal checks passed: "+checks);
    }
}
