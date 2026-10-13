#!/usr/bin/env python3
"""Local-only entry point for the existing Jeeves stdio MCP facade (no model calls).

Room credentials inherited from a course profile are never forwarded to a local
workbench. The course MCP remains a separate process with its own credentials.
"""
import argparse
import ipaddress
import os
from pathlib import Path
import runpy
import sys
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description="Local Jeeves MCP: stdio by default, or a read-only connection doctor.", allow_abbrev=False)
    parser.add_argument("--doctor", action="store_true", help="inspect an already-running local endpoint; starts no engine")
    args = parser.parse_args()  # Other bridge CLI modes, including its write self-test, are deliberately not exposed.
    endpoint = os.environ.get("WORKBENCH_URL", "")
    try:
        if any(ord(char) <= 32 or ord(char) == 127 for char in endpoint):
            raise ValueError("whitespace or control character")
        url = urlsplit(endpoint)
        address = ipaddress.ip_address(url.hostname or "")
        if (url.scheme != "http" or not address.is_loopback or not url.port
                or url.hostname != str(address) or getattr(address, "scope_id", None)
                or url.username is not None or url.password is not None
                or url.path not in ("", "/") or url.query or url.fragment):
            raise ValueError("not a local endpoint")
    except ValueError:
        print("Local Jeeves requires WORKBENCH_URL=http://127.0.0.1:<port> "
              "(or http://[::1]:<port>); no credentials, path, query or fragment.", file=sys.stderr)
        return 2
    for key in ("WORKBENCH_BEARER_FILE", "WORKBENCH_BEARER", "WORKBENCH_MCP_CA_FILE"):
        os.environ.pop(key, None)
    # A local endpoint must not pass through an inherited HTTP proxy.
    for key in tuple(os.environ):
        if key.lower() in ("http_proxy", "https_proxy", "all_proxy"):
            os.environ.pop(key, None)
    os.environ["NO_PROXY"] = "*"
    os.environ["no_proxy"] = "*"
    host = "[" + str(address) + "]" if address.version == 6 else str(address)
    os.environ["WORKBENCH_URL"] = "http://" + host + ":" + str(url.port)
    bridge = str(Path(__file__).resolve().with_name("workbench_mcp.py"))
    sys.argv = [bridge] + (["--doctor"] if args.doctor else [])
    runpy.run_path(bridge, run_name="__main__")
    return 0


if __name__ == "__main__":
    sys.exit(main())
