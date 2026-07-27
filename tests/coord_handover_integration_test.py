#!/usr/bin/env python3
"""Systemd-style pre-bound socket handover with mixed reads and writes."""

from __future__ import annotations

import collections
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DAEMON = ROOT / "bin" / "fram-daemon"
SUBJECT = "@m6-handover"
PREDICATE = "note"


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def edn_string(value: str) -> str:
    return json.dumps(value)


def request_text(log: Path, inner: str) -> bytes:
    return (
        "{:op :for-log"
        f" :expected-log {edn_string(str(log.resolve()))}"
        " :fmt :json"
        f" :request {inner}}}\n"
    ).encode()


def wire_request(port: int, log: Path, inner: str, timeout: float = 45.0) -> dict[str, Any]:
    with socket.create_connection(("127.0.0.1", port), timeout=5.0) as client:
        client.settimeout(timeout)
        client.sendall(request_text(log, inner))
        client.shutdown(socket.SHUT_WR)
        chunks: list[bytes] = []
        while True:
            chunk = client.recv(65536)
            if not chunk:
                break
            chunks.append(chunk)
            if b"\n" in chunk:
                break
    raw = b"".join(chunks)
    check(raw.endswith(b"\n"), f"response is not newline-terminated: {raw!r}")
    return json.loads(raw)


class Daemon:
    def __init__(self, listener_fd: int, port: int, log: Path) -> None:
        env = dict(os.environ)
        env.update(
            {
                "FRAM_LISTEN_FD": str(listener_fd),
                "FRAM_REQUIRE_LOG_FENCE": "1",
                "FRAM_SNAPSHOT_INTERVAL_MS": "900000",
            }
        )
        self.process = subprocess.Popen(
            [str(DAEMON), "serve-flat", str(port), str(log)],
            cwd=ROOT,
            env=env,
            pass_fds=(listener_fd,),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        self.lines: queue.Queue[tuple[str, str]] = queue.Queue()
        self.output: dict[str, list[str]] = {"stdout": [], "stderr": []}
        for name, stream in (
            ("stdout", self.process.stdout),
            ("stderr", self.process.stderr),
        ):
            check(stream is not None, f"daemon {name} pipe unavailable")
            threading.Thread(
                target=self._collect,
                args=(name, stream),
                daemon=True,
            ).start()

    def _collect(self, name: str, stream: Any) -> None:
        for line in stream:
            self.output[name].append(line)
            self.lines.put((name, line))

    def diagnostics(self) -> str:
        return "".join(
            f"[{name}] {line}"
            for name in ("stdout", "stderr")
            for line in self.output[name]
        )

    def wait_ready(self, timeout: float = 45.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise AssertionError(
                    f"daemon exited before ready ({self.process.returncode})\n"
                    f"{self.diagnostics()}"
                )
            try:
                _, line = self.lines.get(timeout=0.1)
            except queue.Empty:
                continue
            if "reified coordinator listening on " in line:
                return
        raise AssertionError(f"daemon did not become ready\n{self.diagnostics()}")

    def terminate(self, timeout: float = 30.0) -> None:
        self.process.terminate()
        try:
            rc = self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
            raise AssertionError(f"daemon did not drain on SIGTERM\n{self.diagnostics()}")
        check(rc == 0, f"daemon exited {rc} on SIGTERM\n{self.diagnostics()}")


def main() -> None:
    work = Path(tempfile.mkdtemp(prefix="fram-m6-handover-"))
    listener: socket.socket | None = None
    first: Daemon | None = None
    second: Daemon | None = None
    try:
        log = work / "facts.log"
        log.touch()
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(4096)
        if listener.fileno() != 3:
            os.dup2(listener.fileno(), 3)
            listener.close()
            listener = socket.socket(fileno=3)
        listener.set_inheritable(True)
        port = listener.getsockname()[1]

        first = Daemon(listener.fileno(), port, log)
        first.wait_ready()

        attempted: list[str] = []
        acknowledged: list[str] = []
        ack_versions: list[int] = []
        reads = 0
        refused = 0
        failures: list[BaseException] = []
        lock = threading.Lock()
        stop = threading.Event()

        def hammer() -> None:
            nonlocal reads, refused
            serial = 0
            while not stop.is_set():
                try:
                    if serial % 2 == 0:
                        value = f"write-{serial:05d}"
                        with lock:
                            attempted.append(value)
                        response = wire_request(
                            port,
                            log,
                            "{:op :assert"
                            f" :te {edn_string(SUBJECT)}"
                            f" :p {edn_string(PREDICATE)}"
                            f" :r {edn_string(value)}"
                            " :base 0}",
                        )
                        check("ok" in response, f"write rejected: {response}")
                        with lock:
                            acknowledged.append(value)
                            ack_versions.append(int(response["ok"]))
                    else:
                        response = wire_request(port, log, "{:op :version}")
                        check("version" in response, f"version read failed: {response}")
                        with lock:
                            reads += 1
                    serial += 1
                    time.sleep(0.003)
                except ConnectionRefusedError as error:
                    with lock:
                        refused += 1
                    failures.append(error)
                    return
                except BaseException as error:
                    failures.append(error)
                    return

        worker = threading.Thread(target=hammer, name="handover-hammer")
        worker.start()
        deadline = time.monotonic() + 30
        while True:
            with lock:
                enough = len(acknowledged) >= 30 and reads >= 25
            if enough:
                break
            check(worker.is_alive(), f"hammer stopped before cutover: {failures}")
            check(time.monotonic() < deadline, "hammer did not reach pre-cutover load")
            time.sleep(0.01)

        first.terminate()
        with lock:
            old_values = set(acknowledged)
            old_version = max(ack_versions)

        queued: list[dict[str, Any]] = []
        queued_failures: list[BaseException] = []

        def queued_status() -> None:
            try:
                queued.append(wire_request(port, log, "{:op :status}"))
            except BaseException as error:
                queued_failures.append(error)

        waiter = threading.Thread(target=queued_status, name="queued-status")
        waiter.start()
        waiter.join(timeout=0.2)
        check(waiter.is_alive(), "queued client completed while no daemon was running")

        second = Daemon(listener.fileno(), port, log)
        second.wait_ready()
        waiter.join(timeout=30)
        check(not waiter.is_alive(), "queued client did not complete after replacement")
        check(not queued_failures, f"queued client failed: {queued_failures}")
        check(len(queued) == 1, f"queued client returned {len(queued)} responses")
        status = queued[0]
        check(
            int(status.get("version", -1)) >= old_version,
            f"replacement accepted before replaying old version {old_version}: {status}",
        )
        check(
            status.get("boot", {}).get("mode") == "snapshot",
            f"replacement did not complete snapshot+tail replay before accept: {status}",
        )

        deadline = time.monotonic() + 30
        while True:
            with lock:
                enough = len(acknowledged) >= len(old_values) + 30 and reads >= 50
            if enough:
                break
            check(worker.is_alive(), f"hammer stopped after cutover: {failures}")
            check(time.monotonic() < deadline, "hammer did not reach post-cutover load")
            time.sleep(0.01)
        stop.set()
        worker.join(timeout=30)
        check(not worker.is_alive(), "hammer did not stop")
        check(not failures, f"hammer failures: {failures}")
        second.terminate()

        with lock:
            sent = list(attempted)
            acked = list(acknowledged)
            read_count = reads
            refused_count = refused
        check(refused_count == 0, f"observed {refused_count} refused connections")
        check(sent == acked, f"sent/acknowledged writes differ: {len(sent)} != {len(acked)}")
        check(len(acked) == len(set(acked)), "client generated duplicate write ids")
        check(old_values.issubset(set(acked)), "pre-cutover acknowledged writes disappeared")

        # A third replay is unnecessary: the replacement's TERM drain has already
        # crossed the durable barrier. Count canonical bytes to catch duplicate or
        # lost appends even if an idempotent live view could otherwise hide them.
        persisted = re.findall(
            r':l "@m6-handover".*?:p "note".*?:r "(write-[0-9]+)"',
            log.read_text(),
        )
        counts = collections.Counter(persisted)
        check(set(counts) == set(acked), "canonical log lost or gained write ids")
        check(all(count == 1 for count in counts.values()), f"duplicate appends: {counts}")

        print(
            "coord_handover: PASS — "
            f"writes={len(acked)} reads={read_count} refused={refused_count} "
            "lost=0 duplicated=0 queued-replay=snapshot"
        )
    finally:
        for daemon in (second, first):
            if daemon is not None and daemon.process.poll() is None:
                daemon.process.kill()
                daemon.process.wait(timeout=5)
        if listener is not None:
            listener.close()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
