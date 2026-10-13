# Branch-destination fixture — CONSTRUCTED, redistributable. Every control-flow operand shape the
# DEX view renders: forward and backward conditional branches, the three goto widths (goto,
# goto/16, goto/32 — the assembler keeps the explicit mnemonics), packed and sparse switches with
# their payloads (a switch's own operand points at the PAYLOAD; the case targets live inside it),
# fill-array-data with its payload, and a try/catch (exception edges). The test decodes every
# operand from the row's raw bytes per the DEX spec (signed displacement in 16-bit code units
# from the instruction) and checks the label stands at exactly that byte offset.
.class public Lfixture/Branches;
.super Ljava/lang/Object;
.source "Branches.java"

# forward if-ge over the body, backward goto to the loop head
.method public static loop(I)I
    .registers 3
    const/4 v0, 0x0
    const/4 v1, 0x0
    :head
    if-ge v0, p0, :done
    add-int/2addr v1, v0
    add-int/lit8 v0, v0, 0x1
    goto :head
    :done
    return v1
.end method

# the three goto widths, forward and backward
.method public static widths(I)I
    .registers 2
    goto/16 :a
    :b
    goto/32 :c
    :a
    const/4 v0, 0x1
    goto :b
    :c
    return v0
.end method

# backward 16- and 32-bit gotos and a backward switch-case target: the SIGN of a wide displacement
# (fix-review 2026-10-08 M10 — the 8-bit goto above was the only backward branch)
.method public static backwards(I)I
    .registers 2
    const/4 v0, 0x0
    :top
    if-ge v0, p0, :exit
    add-int/lit8 v0, v0, 0x1
    goto/16 :top
    :exit
    packed-switch p0, :bdata
    goto/32 :top
    :bdata
    .packed-switch 0x5
        :top
    .end packed-switch
.end method

# packed and sparse switches: the switch operand names the payload, the payload names the cases;
# the sparse table's second case shares a target with the packed table's second
.method public static switches(I)I
    .registers 2
    packed-switch p0, :pswitch_data
    sparse-switch p0, :sswitch_data
    const/4 v0, 0x0
    return v0
    :pswitch_1
    const/4 v0, 0x1
    return v0
    :pswitch_2
    const/4 v0, 0x2
    return v0
    :sswitch_100
    const/4 v0, 0x3
    return v0
    :pswitch_data
    .packed-switch 0x1
        :pswitch_1
        :pswitch_2
    .end packed-switch
    :sswitch_data
    .sparse-switch
        0x100 -> :sswitch_100
        0x200 -> :pswitch_2
    .end sparse-switch
.end method

# fill-array-data: the operand names a data payload, not an instruction
.method public static arrays()[I
    .registers 1
    const/4 v0, 0x3
    new-array v0, v0, [I
    fill-array-data v0, :array_0
    return-object v0
    :array_0
    .array-data 4
        0x1
        0x2
        0x3
    .end array-data
.end method

# a try/catch: the handler is an exceptional edge in the CFG, not a branch operand
.method public static guarded(Ljava/lang/Object;)I
    .registers 2
    :try_start
    invoke-virtual {p0}, Ljava/lang/Object;->hashCode()I
    move-result v0
    :try_end
    .catch Ljava/lang/RuntimeException; {:try_start .. :try_end} :handler
    return v0
    :handler
    move-exception v0
    const/4 v0, -0x1
    return v0
.end method

# the real shape seen in a WhatsApp release: three if-nez a few instructions apart that
# all land on ONE forward target (the throw path) — displacements 0x22, 0x1c, 0x16 code units
.method public static threeToOne(III)V
    .registers 4
    if-nez p0, :common
    const/4 v0, 0x1
    const/4 v0, 0x2
    const/4 v0, 0x3
    const/4 v0, 0x4
    if-nez p1, :common
    const/4 v0, 0x5
    const/4 v0, 0x6
    const/4 v0, 0x7
    const/16 v0, 0x8
    if-nez p2, :common
    return-void
    :common
    new-instance v0, Ljava/lang/IllegalStateException;
    invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V
    throw v0
.end method
