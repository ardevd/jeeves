# Retained MethodParameters fixture — CONSTRUCTED, redistributable (no WhatsApp bytes).
# Every method below is a shape the engine's metadata extraction must preserve verbatim:
# the annotation's names and accessFlags arrays in their encoded order, nulls, duplicates,
# keywords, invalid identifiers, array lengths that do not match the prototype, methods with
# no body, wide parameters, and a same-length PERMUTED annotation (mirrors WhatsApp 2.23.4.6
# LX/0XS;-><init>: the names say intentSender,intent while the prototype and the field stores
# say Intent,IntentSender). Debug-info parameter names (.param) are the second, separate source.
# Assembled with backend/tools/assemble-fixture.sh into params-fixture.dex.
.class public abstract Lfixture/Params;
.super Ljava/lang/Object;
.source "Params.java"

.field private intent:Lfixture/Intent;
.field private intentSender:Lfixture/IntentSender;
.field private flagsMask:I
.field private flagsValues:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

# 1. annotation-only names — no debug info at all
.method public static annotOnly(ILjava/lang/String;)I
    .registers 2
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0 }
        names = { "count", "label" }
    .end annotation
    return p0
.end method

# 2. debug-only names — .param writes debug_info parameter names; no annotation
.method public static debugOnly(ILjava/lang/String;)I
    .registers 2
    .param p0, "width"
    .param p1, "title"
    return p0
.end method

# 3. both sources agree
.method public static agree(JI)J
    .registers 3
    .param p0, "stamp"
    .param p2, "delta"
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0 }
        names = { "stamp", "delta" }
    .end annotation
    return-wide p0
.end method

# 4. the two sources disagree — both are kept, neither wins
.method public conflict(Ljava/lang/String;Z)V
    .registers 3
    .param p1, "dbgName"
    .param p2, "dbgFlag"
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0 }
        names = { "annName", "annFlag" }
    .end annotation
    return-void
.end method

# 5. a null entry beside a present one — a null is a no-op, never an erasure
.method public static withNull(II)I
    .registers 2
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0 }
        names = { null, "second" }
    .end annotation
    return p0
.end method

# 6. absent: no annotation, no debug names — "absent" is not "unsupported"
.method public static absent(I)I
    .registers 1
    return p0
.end method

# 7. stale count: two formal parameters, three names, one flag (constructed, like 2Xj's 9 vs 10)
.method public static staleCount(II)I
    .registers 2
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0 }
        names = { "alpha", "beta", "gamma" }
    .end annotation
    return p0
.end method

# 8. zero formal parameters with a retained name (constructed, like 0hX.<init>)
.method public static zeroArgs()V
    .registers 0
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0 }
        names = { "message" }
    .end annotation
    return-void
.end method

# 9. wide parameters: three formals (J D I) over five registers — formal count is not word count
.method public wide(JDI)D
    .registers 6
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0, 0x0 }
        names = { "started", "ratio", "count" }
    .end annotation
    return-wide p3
.end method

# 10. no body: abstract and native methods still carry the annotation
.method public abstract abstractNamed(Ljava/lang/String;I)V
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0 }
        names = { "key", "ttl" }
    .end annotation
.end method

.method public static native nativeNamed(J)J
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0 }
        names = { "handle" }
    .end annotation
.end method

# 11. duplicates, a Java keyword, an invalid identifier — metadata values, not variable names
.method public static oddNames(IIII)I
    .registers 4
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0, 0x0, 0x0 }
        names = { "same", "same", "class", "1bad-name" }
    .end annotation
    return p0
.end method

# 12. mandated / synthetic flags beside a null name (an inner class's this$0)
.method public static flagged(Ljava/lang/Object;I)V
    .registers 2
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x8010, 0x1000 }
        names = { "this$0", null }
    .end annotation
    return-void
.end method

# 13. CONSTRUCTED same-length permutation (the 0XS shape): the annotation keeps an older order,
#     the prototype is (Intent, IntentSender, int, int) and the body stores p1 into the Intent
#     field and p2 into the IntentSender field — applying the names by position would label an
#     Intent "intentSender". The real case is WhatsApp 2.23.4.6 LX/0XS;-><init>, checked separately.
.method public permuted(Lfixture/Intent;Lfixture/IntentSender;II)V
    .registers 5
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0, 0x0, 0x0 }
        names = { "intentSender", "intent", "flagsMask", "flagsValues" }
    .end annotation
    iput-object p1, p0, Lfixture/Params;->intent:Lfixture/Intent;
    iput-object p2, p0, Lfixture/Params;->intentSender:Lfixture/IntentSender;
    iput p3, p0, Lfixture/Params;->flagsMask:I
    iput p4, p0, Lfixture/Params;->flagsValues:I
    return-void
.end method

# 14. the aligned control beside it (the 0XS writeToParcel shape): dest, flags in prototype order
.method public aligned(Lfixture/Intent;I)V
    .registers 3
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0 }
        names = { "dest", "flags" }
    .end annotation
    iput-object p1, p0, Lfixture/Params;->intent:Lfixture/Intent;
    iput p2, p0, Lfixture/Params;->flagsMask:I
    return-void
.end method

# 15. the mapping exercises: three plain arguments with retained names (individual and bulk mapping,
#     swaps, conflicts, an analyst's own name set first)
.method public mapTarget(ILjava/lang/String;Z)I
    .registers 4
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0, 0x0 }
        names = { "retainedFirst", "retainedSecond", "retainedThird" }
    .end annotation
    if-eqz p3, :skip
    add-int/lit8 p1, p1, 0x1
    :skip
    return p1
.end method

# 16. a duplicate-in-list bulk candidate: two entries the same — bulk must refuse, singles stay possible
.method public static dupList(II)I
    .registers 2
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = { 0x0, 0x0 }
        names = { "twin", "twin" }
    .end annotation
    add-int p0, p0, p1
    return p0
.end method
