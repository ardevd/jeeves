#!/usr/bin/env python3
"""Offline, read-only reference validation for the canonical Jeeves run bundle.

Requires jsonschema==4.26.0; MCP itself remains standard-library only. No hook,
phone, engine, process, URL reference or uploaded script is ever executed here.
"""
import argparse
from datetime import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
MAX_MANIFEST = 1 << 20
MAX_FILE = 16 << 20
MAX_TOTAL = 128 << 20
MAX_EVENT = 64 << 10


class InvalidBundle(ValueError):
    pass


def require(test, message):
    if not test:
        raise InvalidBundle(message)


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result

    def constant(_):
        raise InvalidBundle("non-finite JSON number")

    def number(value):
        parsed = float(value)
        require(math.isfinite(parsed), "non-finite JSON number")
        return parsed

    try:
        parsed = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=constant, parse_float=number)
        pending = [(parsed, 0)]
        while pending:
            value, depth = pending.pop()
            require(depth <= 64, "JSON nesting exceeds 64 levels")
            if isinstance(value, str):
                require(not any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in value),
                        "control character or surrogate in JSON string")
            children = (*value.keys(), *value.values()) if isinstance(value, dict) else value if isinstance(value, list) else ()
            pending.extend((child, depth + 1) for child in children)
        return parsed
    except (ValueError, UnicodeError, RecursionError) as error:
        raise InvalidBundle("invalid UTF-8 JSON: " + type(error).__name__) from None


def portable_parts(name):
    require(isinstance(name, str) and 0 < len(name) <= 200, "invalid relative path")
    parts = name.split("/")
    for part in parts:
        require(part not in ("", ".", "..") and not part.endswith((".", " ")),
                "empty/dot/trailing-dot path component")
        require(all(c.isascii() and (c.isalnum() or c in "._-") for c in part),
                "non-portable path character")
        stem = part.split(".")[0].upper()
        require(stem not in {"CON", "PRN", "AUX", "NUL"}
                and stem not in {f"{kind}{n}" for kind in ("COM", "LPT") for n in range(1, 10)},
                "reserved path component")
    return parts


def read_member(root_fd, name, limit):
    """Resolve beneath an already-open root, refusing symlinks at every level."""
    parts = portable_parts(name)
    fd = os.dup(root_fd)
    try:
        for part in parts[:-1]:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        # NONBLOCK prevents a malicious FIFO from hanging validation before fstat.
        leaf = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        with os.fdopen(leaf, "rb") as stream:
            before = os.fstat(stream.fileno())
            require(stat.S_ISREG(before.st_mode), "bundle member is not a regular file")
            require(before.st_nlink == 1, "bundle member is hard-linked")
            require(before.st_size <= limit, "bundle member exceeds byte limit")
            raw = stream.read(limit + 1)
            after = os.fstat(stream.fileno())
            require(len(raw) <= limit, "bundle member exceeds byte limit")
            require((before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                    == (after.st_size, after.st_mtime_ns, after.st_ctime_ns), "member changed during read")
            return raw
    finally:
        os.close(fd)


def schemas():
    result = []
    for name in ("bundle", "event"):
        schema = strict_json((ROOT / "schemas" / f"jeeves-run-{name}-v1.schema.json").read_bytes())
        Draft202012Validator.check_schema(schema)
        result.append(Draft202012Validator(schema))
    return result


def checked_schema(validator, value):
    # Do not echo uploaded strings or secrets into diagnostic output.
    error = next(validator.iter_errors(value), None)
    require(error is None, "JSON Schema violation" if error is None else f"JSON Schema violation ({error.validator})")


def timestamp(value):
    """Strict UTC with optional 1–9 fractional digits, consistent across Python versions."""
    match = re.fullmatch(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z", value)
    require(match is not None, "invalid UTC timestamp")
    try:
        whole = datetime(*(int(part) for part in match.groups()[:6]))
    except ValueError:
        raise InvalidBundle("invalid UTC timestamp") from None
    nanos = int((match.group(7) or "0").ljust(9, "0"))
    return whole, nanos


def validate_provenance(doc, fixture):
    pending = [doc]
    provenance_keys = {"identity_basis", "state_basis", "basis", "provenance", "split_provenance"}
    while pending:
        item = pending.pop()
        if isinstance(item, dict):
            for key, value in item.items():
                if key in provenance_keys:
                    require((value == "fixture") == fixture, "fixture and non-fixture provenance cannot be mixed")
                pending.append(value)
        elif isinstance(item, list):
            pending.extend(item)
    require((doc["privacy"]["sensitive_content_review"] == "fixture-reviewed") == fixture,
            "fixture and non-fixture privacy review cannot be mixed")
    if fixture:
        require(doc["producer"]["engine"] is None and not doc["research"]["contributions"],
                "synthetic fixture cannot claim engine or model capture")
    if doc["producer"]["mode"] == "local":
        require(doc["session"]["ownership"] == "coordinated-local", "local controller requires coordinated-local ownership")


def validate(root):
    manifest_schema, event_schema = schemas()
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        raw = read_member(root_fd, "manifest.json", MAX_MANIFEST)
        doc = strict_json(raw)
        checked_schema(manifest_schema, doc)
        capture = doc["capture"]
        require(timestamp(capture["ended_at"]) >= timestamp(capture["started_at"]), "end precedes start")
        require(len({h["id"] for h in doc["hooks"]}) == len(doc["hooks"]), "duplicate hook id")
        hooks = {h["id"]: h for h in doc["hooks"]}
        processes = {p["id"] for p in doc["session"]["processes"]}
        require(len(processes) == len(doc["session"]["processes"]), "duplicate process id")
        installed = {s for h in hooks.values() if h["state"] == "installed" for s in h["origSigs"]}
        require(doc["coverage"]["installed_methods"] == len(installed), "installed-method coverage mismatch")
        fixture = doc["producer"]["mode"] == "fixture"
        validate_provenance(doc, fixture)
        if fixture:
            require(doc["target"]["artifact"]["kind"] == "fixture" and doc["device"]["platform"] == "fixture"
                    and doc["session"]["ownership"] == "fixture" and doc["privacy"]["projection"] == "fixture",
                    "fixture identity must remain explicit")
        else:
            require(doc["target"]["artifact"]["kind"] == "apk" and doc["device"]["platform"] == "android"
                    and doc["privacy"]["projection"] == "selected", "live/imported bundle cannot masquerade as fixture")
        if doc["producer"]["mode"] == "external":
            require(doc["session"]["ownership"] == "external-declared", "external ownership must remain declared")
        cleanup = doc["cleanup"]
        if cleanup["state"] == "complete":
            require(all(r["state"] == "released" for r in cleanup["resources"]), "cleanup complete with unreleased resource")
        if cleanup["state"] == "not-applicable":
            require(not cleanup["resources"], "not-applicable cleanup has owned resources")
        require(all(h["error"] is not None for h in hooks.values() if h["state"] == "failed"), "failed hook lacks error")
        seen_paths = {"manifest.json"}
        components = {}
        total = len(raw)
        all_count, first, last, previous_ns, gaps = 0, None, None, None, 0
        observed = set()
        paths = {}
        for entry in doc["files"]:
            name = entry["path"]
            parts = portable_parts(name)
            for index in range(1, len(parts) + 1):
                prefix = "/".join(parts[:index])
                kind = "file" if index == len(parts) else "directory"
                old = components.setdefault(prefix.casefold(), (prefix, kind))
                require(old == (prefix, kind), "case-colliding path component or file/directory collision")
            require(name.casefold() not in seen_paths, "duplicate or case-colliding file path")
            seen_paths.add(name.casefold())
            paths[name] = entry
            total += entry["size"]
            require(total <= MAX_TOTAL, "bundle exceeds total byte limit")
            content = read_member(root_fd, name, MAX_FILE)
            require(len(content) == entry["size"], "file size mismatch")
            require(hashlib.sha256(content).hexdigest() == entry["sha256"], "file digest mismatch")
            if entry["role"] != "events":
                require(all(entry[k] is None for k in ("event_count", "first_seq", "last_seq")), "event metadata on non-event file")
                continue
            require(not content or content.endswith(b"\n"), "unclosed JSONL segment")
            require(b"\r" not in content, "JSONL requires LF separators; CR is forbidden")
            segment_count, segment_first, segment_last = 0, None, None
            for line in content[:-1].split(b"\n") if content else ():
                require(0 < len(line) <= MAX_EVENT, "empty/oversize event line")
                event = strict_json(line)
                checked_schema(event_schema, event)
                require(event["run_id"] == doc["run_id"], "event belongs to another run")
                seq, ns = event["seq"], int(event["monotonic_ns"])
                require(last is None or seq > last, "sequence must strictly increase across segments")
                require(previous_ns is None or ns >= previous_ns, "monotonic clock moved backwards")
                gaps += (seq - last - 1) if last is not None else seq - 1
                first = first if first is not None else seq
                segment_first = segment_first if segment_first is not None else seq
                last, previous_ns, segment_last = seq, ns, seq
                all_count += 1
                segment_count += 1
                require(all_count <= 1000000, "event count exceeds limit")
                if event["process_id"] is not None:
                    require(event["process_id"] in processes, "event process absent from session inventory")
                hook = hooks.get(event["hook_id"])
                require(event["hook_id"] is None or hook is not None, "event references unknown hook")
                if event["kind"] in ("enter", "leave", "exception"):
                    require(hook is not None and hook["state"] == "installed", "observation requires an installed hook")
                    require(event["process_id"] is not None, "observation lacks process identity")
                    require((event["origSig"] is None) != (event["native"] is None), "observation requires exactly one original or native site")
                    if event["origSig"] is not None:
                        require(event["origSig"] in hook["origSigs"], "original symbol absent from installed hook (case-sensitive)")
                        observed.add(event["origSig"])
                    else:
                        require(event["native"] in hook["native_sites"], "native site absent from installed hook")
            require((segment_count, segment_first, segment_last)
                    == (entry["event_count"], entry["first_seq"], entry["last_seq"]), "segment event metadata mismatch")
        require((all_count, first, last, gaps) == (capture["event_count"], capture["first_seq"], capture["last_seq"], capture["sequence_gaps"]), "capture event metadata mismatch")
        require(doc["coverage"]["observed_methods"] == len(observed), "observed-method coverage mismatch")
        loss = capture["loss"]
        require(loss["unknown_loss"] or loss["known_dropped"] >= gaps, "sequence gaps lack loss declaration")
        if capture["completion"] == "complete":
            require(not loss["unknown_loss"] and not loss["known_dropped"] and not gaps
                    and all(h["state"] == "installed" for h in hooks.values()), "complete capture has loss or missing hooks")
            require(capture["stop_reason"] not in ("error", "owner-crash"), "complete capture ended in failure")
        for contribution in doc["research"]["contributions"]:
            require(contribution["file"] in paths and paths[contribution["file"]]["role"] == "research", "research contribution has no selected research file")
        return {"schema": "jeeves-bundle-validation-v1", "valid": True,
                "run_id": doc["run_id"], "manifest_sha256": hashlib.sha256(raw).hexdigest(),
                "files": len(paths) + 1, "bytes": total, "events": all_count,
                "completion": capture["completion"], "cleanup": cleanup["state"],
                "producer_mode": doc["producer"]["mode"], "scientific_qualification": False,
                "executed_scripts": False, "uploaded": False}
    finally:
        os.close(root_fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    try:
        report = validate(args.bundle)
    except (InvalidBundle, OSError) as error:
        # OSError may contain private absolute paths: report only the class.
        report = {"valid": False, "error": str(error) if isinstance(error, InvalidBundle) else type(error).__name__}
    except Exception as error:
        report = {"valid": False, "error": type(error).__name__}
    print(json.dumps(report, sort_keys=True))
    return 0 if report["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
