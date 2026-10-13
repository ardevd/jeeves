#!/usr/bin/env python3
"""Resolve native FUNCTION NAMES to module-base OFFSETS via idalib (headless IDA), for native
breakpoints. The workbench's native bp normally takes an EXPORT (frida resolves it) or a raw
OFFSET (hand-fed from a disassembler). This bridges the gap: given a local copy of a shared
library and a function name, it returns the offset from the module base — so a bp can name a
NON-EXPORTED function and the backend fills in the offset automatically.

A shared object is position-independent; IDA loads it at imagebase, so the file offset we want is
(effective address - imagebase). For a .so IDA's imagebase is typically 0, but we subtract it
regardless so the answer is always a true module-base offset (what frida's module.base + offset needs).

Invoked by the Java bridge (NativeSymbols) as a one-shot process — NOT the persistent frida sidecar
model — because name resolution is a deliberate, infrequent action and idalib caches its analysis in
an .i64 beside the .so, so re-opens are cheap. Chatty idalib/IDA startup + analysis logs go to
stdout/stderr; the machine-readable result is written to --out as JSON, so the caller never has to
parse it out of the banner noise.

Usage:  idalib_resolve.py --so <path> --names foo,bar,baz --out <result.json> [--save]
Result JSON: {"ok": true, "imagebase": "0x0", "results": {"foo": "0x90a9c", "bar": null}}
             {"ok": false, "error": "..."}
A name maps to null unless it resolves to a FUNCTION ENTRY. get_name_ea_simple resolves ANY known name
— a data global, a code label, an address inside a function — none of which are safe to hook (a native
bp at a non-exec or mid-instruction address either fails to install or corrupts the target under test).
So we accept a name only when ida_funcs.get_func(ea) exists AND its start_ea == ea (the function's entry).
Note: this matches IDA's STORED (mangled) name — a demangled C++ name from the UI won't resolve.
"""
import argparse
import json
import os
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--so", required=True, help="path to the shared library to analyze")
    ap.add_argument("--names", required=True, help="comma-separated function names to resolve")
    ap.add_argument("--out", required=True, help="path to write the JSON result to")
    ap.add_argument("--save", action="store_true", help="force persisting the .i64 even if one exists")
    args = ap.parse_args()

    names = [n.strip() for n in args.names.split(",") if n.strip()]
    # Persist a FRESH analysis so the next open is cheap, but don't rewrite an existing .i64 (avoids a
    # lock fight with the idalib MCP supervisor, which may hold the same idb open). --save forces it.
    # a 64-bit .so yields <so>.i64, a 32-bit .so yields <so>.idb — either means "already analyzed"
    have_idb = os.path.exists(args.so + ".i64") or os.path.exists(args.so + ".idb")
    save = args.save or not have_idb
    result = {"ok": False}
    try:
        import idapro  # the idalib entry point (IDA 9.x); needs IDADIR in the environment
        rc = idapro.open_database(args.so, run_auto_analysis=True)
        if rc != 0:
            raise RuntimeError("idapro.open_database rc=%d for %s" % (rc, args.so))
        try:
            import idaapi
            import idc
            import ida_funcs
            base = idaapi.get_imagebase()
            out = {}
            for name in names:
                ea = idc.get_name_ea_simple(name)      # BADADDR when the name is unknown
                if ea == idaapi.BADADDR:
                    out[name] = None
                    continue
                f = ida_funcs.get_func(ea)             # only a function ENTRY is safe to hook
                out[name] = hex(ea - base) if (f is not None and f.start_ea == ea) else None
            result = {"ok": True, "imagebase": hex(base), "results": out}
        finally:
            idapro.close_database(save)
    except Exception as e:                              # noqa: BLE001 — surface any failure as JSON
        result = {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f)
    # also echo to stdout for interactive/debug use (the caller reads --out, not this)
    print("@@RESULT@@" + json.dumps(result))
    sys.exit(0 if result.get("ok") else 1)


if __name__ == "__main__":
    main()
