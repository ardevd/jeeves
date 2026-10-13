# An obfuscator-style class name that is NOT a Java identifier (a digit first) — the dex allows it,
# javac never writes it, WhatsApp is full of them (LX/1cq; = the message key). A rename to an analyst's
# name must undo back to "1cq" through the validated restoration path, while "1cq" stays refused as a
# NEW analyst name. Members too: method a, field b.
.class public Lfixture/1cq;
.super Ljava/lang/Object;

.field public b:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public a(I)I
    .registers 2
    iput p1, p0, Lfixture/1cq;->b:I
    return p1
.end method
