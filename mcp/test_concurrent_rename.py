#!/usr/bin/env python3
"""Five real stdio MCP clients race one mock symbol. No JEB, models, or existing server.

Run after `gradle -p backend classes` from the repository root:
    python3 mcp/test_concurrent_rename.py
The server uses a fresh temporary home and an unused loopback port. Test-only
wait bounds detect a hung test; this script does not alter production limits.
"""
import concurrent.futures
import json
import os
from pathlib import Path
import selectors
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


class Client:
    def __init__(self, actor, base, root):
        env = dict(os.environ, WORKBENCH_URL=base, WORKBENCH_ACTOR=actor,
                   WORKBENCH_TARGET="t1")
        self.log = (root / (actor.replace(":", "-") + ".log")).open("w")
        self.process = subprocess.Popen(
            [shutil.which("python3"), "-B", str(ROOT / "mcp/workbench_mcp.py")],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log,
            bufsize=0, env=env, cwd=ROOT)
        self.next_id = 1
        try:
            self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                        "clientInfo": {"name": actor, "version": "test"}})
            self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        except BaseException:
            self.close()
            raise

    def send(self, payload):
        self.process.stdin.write((json.dumps(payload) + "\n").encode())
        self.process.stdin.flush()

    def request(self, method, params):
        ident = self.next_id
        self.next_id += 1
        self.send({"jsonrpc": "2.0", "id": ident, "method": method, "params": params})
        deadline = time.monotonic() + 30
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not selector.select(remaining):
                    raise AssertionError("MCP response did not arrive within the test wait bound")
                line = self.process.stdout.readline()
                if not line:
                    raise AssertionError("MCP bridge exited during the test")
                response = json.loads(line)
                if response.get("id") == ident:
                    assert "error" not in response, response
                    return response["result"]

    def call(self, tool, args):
        reply = self.request("tools/call", {"name": tool, "arguments": args})
        assert not reply.get("isError"), reply
        return json.loads(reply["content"][0]["text"])

    def close(self):
        stop(self.process)
        self.log.close()


def main():
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
    if not java:
        raise SystemExit("JDK 21 is required")
    with tempfile.TemporaryDirectory(prefix="jeeves-mcp-rename-test-") as temp:
        root = Path(temp)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        base = "http://127.0.0.1:%d" % port
        env = dict(os.environ, WORKBENCH_MOCK="1", WORKBENCH_COURSE="0",
                   WORKBENCH_REVIEW_MODE="auto", WORKBENCH_HOME=str(root / "home"),
                   WORKBENCH_PORT=str(port))
        clients = []
        with (root / "server.log").open("w+") as log:
            server = subprocess.Popen(
                [java, "-Djava.io.tmpdir=" + temp, "-cp", str(ROOT / "backend/build/classes/java/main"),
                 "dev.workbench.server.WorkbenchServer", str(ROOT / "web")],
                cwd=ROOT, env=env, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if server.poll() is not None:
                        log.seek(0)
                        raise AssertionError("Temporary server exited: " + log.read())
                    try:
                        with urllib.request.urlopen(base + "/healthz", timeout=1) as response:
                            if json.load(response).get("ready"):
                                break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise AssertionError("Temporary server failed to become ready")
                        time.sleep(.05)
                for index in range(5):
                    clients.append(Client("llm:rename-test-%d" % index, base, root))
                tools = clients[0].request("tools/list", {})["tools"]
                annotate = next(row for row in tools if row["name"] == "annotate")
                properties = annotate["inputSchema"]["properties"]["items"]["items"]["properties"]
                assert "expectedName" in properties, properties
                print("PASS real tools/list advertises expectedName")
                start = threading.Barrier(5)

                def rename(index):
                    start.wait(timeout=30)
                    reply = clients[index].call("annotate", {"items": [{
                        "symbol": "MailSyncService", "name": "MailboxWorker%d" % index}]})
                    return index, reply["results"][0]["rename"]

                with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
                    replies = list(pool.map(rename, range(5)))
                winners = [(index, result) for index, result in replies if result["status"] == "applied"]
                assert len(winners) == 1, replies
                winning_index = winners[0][0]
                winning_name = "MailboxWorker%d" % winning_index
                print("PASS five real MCP clients produce exactly one applied rename")
                for index, result in replies:
                    if index == winning_index:
                        continue
                    assert result["status"] == "failed", result
                    details = result["errorDetails"]
                    assert details["code"] == "RENAME_CONFLICT", result
                    assert details["currentName"] == winning_name, result
                    assert details["currentActor"] == "rename-test-%d" % winning_index, result
                    assert details["unsaved"] is True and details["historyId"], result
                    assert winning_name in result["error"], result
                print("PASS all four losers receive typed conflict with exact winning name, actor, and history")
                refined = clients[winning_index].call("annotate", {"items": [{
                    "symbol": "MailSyncService", "name": "MailboxRefined", "expectedName": winning_name}]})
                assert refined["results"][0]["rename"]["status"] == "applied", refined
                stale = clients[winning_index].call("annotate", {"items": [{
                    "symbol": "MailSyncService", "name": "ShouldNotApply", "expectedName": winning_name}]})
                error = stale["results"][0]["rename"]["errorDetails"]
                assert error["code"] == "RENAME_CONFLICT" and error["currentName"] == "MailboxRefined", stale
                assert error["expectedName"] == winning_name, stale
                print("PASS expectedName reaches server and stale CAS details survive stdio transport")
                print("MCP concurrent rename checks passed: 4")
            finally:
                for client in clients:
                    client.close()
                stop(server)


if __name__ == "__main__":
    main()
