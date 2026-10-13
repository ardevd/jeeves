#!/usr/bin/env python3
"""
Dalvik signature -> Frida target parser.

The backend (the only thing that talks to JEB) hands us a method's ORIGINAL Dalvik signature, taken
straight from JEB's getSignature(false) -- e.g. `Lx/y/z;->a(I[BLjava/lang/String;)V`. The device runs
the app's original (obfuscated) names, so this original signature IS the runtime target. This module
turns it into what Frida's Java API needs:

    Java.use('x.y.z')['a'].overload('int', 'byte[]', 'java.lang.String')

Pure + dependency-free + exhaustively unit-tested (test_dalvik.py) so the fiddly descriptor->Java-name
translation can't drift. No Frida import here -- this runs anywhere (including the backend test gate).

References: Dalvik TypeDescriptor grammar (Android docs). Frida `.overload()` takes Java *source* type
names: primitives `int`/`boolean`/...; arrays `int[]`, `java.lang.String[]`; objects fully-qualified
dotted (`java.lang.String`), inner classes keep `$` (`com.foo.Outer$Inner`).
"""

# Dalvik primitive descriptor -> Java source name.
_PRIM = {
    "V": "void", "Z": "boolean", "B": "byte", "S": "short",
    "C": "char", "I": "int", "J": "long", "F": "float", "D": "double",
}


def descriptor_to_java(desc):
    """One Dalvik type descriptor -> its Java source name.
    `I`->`int`, `[B`->`byte[]`, `[[I`->`int[][]`, `Ljava/lang/String;`->`java.lang.String`,
    `[Lcom/foo/Bar;`->`com.foo.Bar[]`, `Lcom/foo/Outer$Inner;`->`com.foo.Outer$Inner`.
    """
    if not desc:
        raise ValueError("empty descriptor")
    dims = 0
    i = 0
    while i < len(desc) and desc[i] == "[":
        dims += 1
        i += 1
    base = desc[i:]
    if not base:
        raise ValueError("array with no element type: %r" % desc)
    head = base[0]
    if head == "L":
        if not base.endswith(";"):
            raise ValueError("object descriptor missing ';': %r" % desc)
        java = base[1:-1].replace("/", ".")        # strip L and ;, slashes -> dots; keep $ for inner
        if not java:
            raise ValueError("empty class name: %r" % desc)
    elif head in _PRIM:
        if len(base) != 1:
            raise ValueError("trailing chars after primitive: %r" % desc)
        java = _PRIM[head]
    else:
        raise ValueError("unknown type descriptor: %r" % desc)
    return java + "[]" * dims


def split_param_descriptors(params):
    """Split a concatenated parameter descriptor blob (no separators) into individual descriptors.
    `I[BLjava/lang/String;` -> ['I', '[B', 'Ljava/lang/String;']. Empty -> []."""
    out = []
    i = 0
    n = len(params)
    while i < n:
        start = i
        while i < n and params[i] == "[":     # array dims
            i += 1
        if i >= n:
            raise ValueError("dangling '[' in params: %r" % params)
        c = params[i]
        if c == "L":                          # object: consume up to ';'
            end = params.find(";", i)
            if end < 0:
                raise ValueError("unterminated object in params: %r" % params)
            i = end + 1
        elif c in _PRIM and c != "V":         # primitive (V is not a valid param)
            i += 1
        else:
            raise ValueError("bad param descriptor at %d in %r" % (i, params))
        out.append(params[start:i])
    return out


def class_sig_to_dotted(sig):
    """`Lcom/foo/Bar;` -> `com.foo.Bar` (the Frida Java.use() name). Accepts an already-dotted name."""
    sig = sig.strip()
    if sig.startswith("L") and sig.endswith(";"):
        return sig[1:-1].replace("/", ".")
    return sig.replace("/", ".")               # tolerate a bare/loose form


def _frida_method_name(name):
    """JEB method name -> the name used on the Frida wrapper. Constructors become $init."""
    if name == "<init>":
        return "$init"
    if name == "<clinit>":
        return "$clinit"                       # static initializer: not normally hookable (bridge skips)
    return name


def parse_method_signature(sig):
    """Full Dalvik method signature -> a Frida target.
    `Lx/y/z;->a(I[BLjava/lang/String;)V` ->
      {className:'x.y.z', methodName:'a', fridaMethod:'a', paramTypes:['int','byte[]','java.lang.String'],
       retType:'void', isCtor:False, isStaticInit:False}
    """
    if "->" not in sig:
        raise ValueError("not a method signature (no '->'): %r" % sig)
    owner, rest = sig.split("->", 1)
    className = class_sig_to_dotted(owner)
    lp = rest.find("(")
    rp = rest.find(")", lp + 1)
    if lp < 0 or rp < 0:
        raise ValueError("malformed method signature (no param parens): %r" % sig)
    name = rest[:lp]
    params = rest[lp + 1:rp]
    ret = rest[rp + 1:]
    if not name:
        raise ValueError("empty method name: %r" % sig)
    param_types = [descriptor_to_java(d) for d in split_param_descriptors(params)]
    ret_type = descriptor_to_java(ret) if ret else "void"
    return {
        "className": className,
        "methodName": name,
        "fridaMethod": _frida_method_name(name),
        "paramTypes": param_types,
        "retType": ret_type,
        "isCtor": name == "<init>",
        "isStaticInit": name == "<clinit>",
    }


def is_method_signature(sig):
    return isinstance(sig, str) and "->" in sig and "(" in sig


if __name__ == "__main__":   # tiny manual probe: python3 dalvik.py 'Lx/y/z;->a(I)V'
    import json
    import sys
    for a in sys.argv[1:]:
        print(json.dumps(parse_method_signature(a), indent=2))
