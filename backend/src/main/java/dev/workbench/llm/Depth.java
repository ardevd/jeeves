package dev.workbench.llm;

import dev.workbench.registry.OpException;

/** Mission DEPTH (agent.spawn {depth: quick|deep}) — how far a reversing
 *  mission goes from its subject. Not a model setting: a brief the mission
 *  prompt carries (the hop limit, what to propose, when to stop), plus the
 *  turn cap and the default budget that make the brief enforceable. Null =
 *  the caller's own goal text says everything (the NEW MISSION card,
 *  triage-package, MCP clients). The GUI's "reverse with agent · quick/deep"
 *  (code menu, tree menu, RELATION ⚡, palette) is the producer. */
public final class Depth {
    public static final String QUICK = "quick", DEEP = "deep";

    private Depth() {}

    /** Explicit unattended execution. java.awt.headless also applies to the browser-backed
     *  server, so it must not be used to infer this operator-selected policy. */
    public static boolean unlimitedTurns() {
        String value = System.getenv("WORKBENCH_HEADLESS");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /** null/blank → null; quick|deep (any case) → canonical; else E_SYNTAX —
     *  a typo must not silently spawn an unbounded mission. */
    public static String normalize(String depth) throws OpException {
        if (depth == null || depth.isBlank()) return null;
        String d = depth.trim().toLowerCase();
        if (QUICK.equals(d) || DEEP.equals(d)) return d;
        throw OpException.syntax("agent.spawn depth must be quick or deep", "depth=" + depth);
    }

    /** token budget when the caller gave none. Sized ABOVE what the turn cap
     *  can spend: the http loop bills the whole prompt every turn (~7-10k once
     *  the window plateaus, AgentLoop WINDOW_RECENT_CHARS + the tool schemas),
     *  so the cap must bind before the ceiling or the mission parks PAUSED
     *  one op short of its summary (fix-review reverse r1 M6): 10 × ~10k <
     *  120k, 40 × ~10k < 500k. */
    public static int defaultBudget(String depth, int dflt) {
        if (QUICK.equals(depth)) return 120_000;
        if (DEEP.equals(depth)) return 1_500_000;   // cost-weighted; m3 spent ~0.6M in 60 turns
        return dflt;
    }

    /** http loop turns (one chat request each) */
    public static int loopTurns(String depth, int dflt) {
        if (unlimitedTurns()) return 0;
        if (QUICK.equals(depth)) return 10;
        if (DEEP.equals(depth)) return 100;   // 2026-09-18 (John): a deep run gets 100 turns
        return dflt;
    }

    /** claude -p --max-turns (a turn there is one tool call or answer) */
    public static int cliTurns(String depth, int dflt) {
        if (unlimitedTurns()) return 0;
        if (QUICK.equals(depth)) return 20;
        if (DEEP.equals(depth)) return 100;
        return dflt;
    }

    /** The instruction block appended to the mission goal — identical for the
     *  claude CLI prompt and the http loop's mission message, so both modes
     *  reverse to the same depth. Speaks of "the subject" (the goal names it:
     *  a method or a class). */
    public static String brief(String depth) { return brief(depth, null); }

    /** The subject kind a goal names: "reverse class …" / "reverse method …" (the ⚡ gesture's
     *  spelling), else the first JVM handle in it (a `->` makes it a method), else method. */
    public static String subjectKind(String goal) {
        if (goal == null) return "method";
        String g = goal.trim();
        if (g.startsWith("reverse call ")) return "call";
        if (g.startsWith("reverse class ")) return "class";
        if (g.startsWith("reverse method ")) return "method";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("L[\\w/$]+;(->\\S+)?").matcher(g);
        if (m.find()) return m.group(1) == null ? "class" : "method";
        return "method";
    }

    /** The depth brief for a subject kind — a CLASS is not "a method alone": its pass is the
     *  outline and the key methods, its naming is the class and its members (user 2026-09-20). */
    public static String brief(String depth, String goal) {
        /* briefs reordered 2026-09-18 (John): naming happens AS understanding arrives, not in a
           final "then propose" step — the agent must read its own names back */
        String kind = subjectKind(goal);
        boolean cls = "class".equals(kind);
        if ("call".equals(kind))
            /* "reverse call <callee> from <caller> at: <the call line> (…)" — the ⚡ Reverse and annotate call
               gesture (user 2026-09-20): the callee in depth, the caller's window around the call, every
               argument named, a comment on the call site itself. Runs on the deep caps and scope. */
            return " DEPTH: CALL — a call site and what it calls. The goal names the CALLEE method, the CALLER"
                    + " method, the exact call line and about where it sits in the caller: when the caller is long,"
                    + " decompile it with a window around that line instead of whole. (0) FIRST, test the callee's"
                    + " existing names against every caller: run xrefs {symbol: the callee, direction: to, kind: call}."
                    + " A name that fits only some callers is wrong — a loop called from upload and download paths is"
                    + " a transfer loop — so rename the class and the method now, with the callers as your rationale,"
                    + " before you write a comment that repeats the wrong name. (1) The callee in depth: decompile it, name it and its class,"
                    + " then every method, field and local inside it as you understand them; walk its own callees"
                    + " two hops when that is what it takes to understand it. (2) The call in context: decompile the"
                    + " caller method, find the call line, and name EVERY symbol within ten lines above and below"
                    + " it — the classes (types), the fields (a field is Lcls;->name), the methods called, and the"
                    + " locals (annotate with local: the current name inside the caller) — including every argument"
                    + " of the call, its type and its local name, and the variable the result lands in. (3) Comment"
                    + " the call site itself: run xrefs {symbol: the callee, direction: to, kind: call}, take the row"
                    + " whose method is the caller (the `from` address, Lcls;->m(...)+NNh — the one at that line),"
                    + " and annotate {symbol: that from address, comment: …} with two or three lines: what is being"
                    + " called, why here, and what this step does in the wider flow. A comment on the callee method"
                    + " and on the caller method as well, one line each. Name as you go, in the same turn you"
                    + " understand a thing; your renames apply the moment the call returns. Finish with a summary"
                    + " of the flow through this call.";
        if (QUICK.equals(depth) && cls)
            return " DEPTH: QUICK — one pass over the subject CLASS only. Read its outline, then decompile its"
                    + " key methods (constructors, the public ones, the largest) — no others unless ONE is needed to"
                    + " disambiguate. Name the class after its role by your SECOND turn at the latest, then name"
                    + " its methods and fields as you understand them, each with a one-line comment saying what it"
                    + " does. Your renames are applied the moment the call returns: never spend a turn confirming"
                    + " one. Read the names of the classes it uses and the classes that use it — do not decompile"
                    + " them. Everything outside this class is outside your write-scope: name them in your summary,"
                    + " do not rename them. Finish with a 2-3 line summary of what the class is for.";
        if (DEEP.equals(depth) && cls)
            return " DEPTH: DEEP — the subject CLASS and its neighbourhood. Read its outline, then decompile it"
                    + " method by method, naming the class, its methods and its fields as you understand them."
                    + " Then walk outward: its superclass and interfaces, the classes its fields hold, its callers"
                    + " and callees up to 3 hops, naming each method, class and field as you understand it; read"
                    + " the strings it touches; establish the feature it implements. Comment the non-obvious ones,"
                    + " tag the feature. Cover the class, not just its entry points. Finish with a summary of the"
                    + " feature and the open questions.";
        if (QUICK.equals(depth))
            return " DEPTH: QUICK — one pass over the subject only. Decompile the subject (a method: its"
                    + " body; a class: its outline and its key methods) and name it as soon as you understand"
                    + " it, with a one-line comment saying what it does. Name the subject and its class by your"
                    + " SECOND turn at the latest, then read on. A method's CLASS is part of the job:"
                    + " read the class outline and name the class after what it is (its role, not this one"
                    + " method) — leave it only if it is already meaningfully named; name the fields of that"
                    + " class the method makes obvious. Your renames are applied the moment the call returns:"
                    + " never spend a turn confirming one. Read the names and signatures of its direct callers and callees — no"
                    + " further decompiles unless ONE is needed to disambiguate. Everything outside that class"
                    + " is outside your write-scope: name callees in your summary, do not rename them. Do not"
                    + " walk the call graph beyond one hop. Finish with a 2-3 line summary.";
        if (DEEP.equals(depth))
            return " DEPTH: DEEP — the subject and its neighbourhood. Decompile the subject and name it as soon"
                    + " as you understand it. Then walk its callees, up to 3 hops, and its direct callers, naming"
                    + " each method, class and field as you understand it; read the strings it touches; establish"
                    + " the feature it implements. Comment the non-obvious ones, tag the feature. Cover the call"
                    + " tree, not just the entry point. Finish with a summary of the feature and the open questions.";
        return "";
    }
}
