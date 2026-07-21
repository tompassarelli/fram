#!/usr/bin/env python3
"""Manage Fram's owned MCP block in a Codex project config.

The editor works on bytes so unrelated TOML remains byte-identical. New blocks
carry begin/end markers and record the exact newline separator inserted before
the begin marker; ``unset`` removes that owned raw span instead of guessing
whether a neighboring blank line belongs to the user. Unmarked Fram blocks from
older versions remain removable and are upgraded to the marked form on ``set``.
"""

import json
import os
import re
import sys


BEGIN_RE = re.compile(
    rb"(?m)^# >>> fram-code-wire managed mcp_servers\.fram separator=([0-2])\n"
)
END_MARKER = b"# <<< fram-code-wire managed mcp_servers.fram\n"
FRAM_HEADER_RE = re.compile(rb"^\[mcp_servers\.fram\][ \t]*$")
FRAM_NESTED_RE = re.compile(rb"^\[mcp_servers\.fram\.[^\]]+\][ \t]*$")
ANY_TABLE_RE = re.compile(rb"^\[[^\]]+\][ \t]*$")


def toml_str(value: str) -> str:
    return json.dumps(value)


def render_toml(server: dict) -> bytes:
    lines = ["[mcp_servers.fram]"]
    lines.append(f"command = {toml_str(server['command'])}")
    args = server.get("args", [])
    lines.append("args = [" + ", ".join(toml_str(arg) for arg in args) + "]")
    env = server.get("env", {})
    if env:
        lines.append("")
        lines.append("[mcp_servers.fram.env]")
        for key, value in env.items():
            lines.append(f"{key} = {toml_str(str(value))}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def render_owned(server: dict, separator_length: int) -> bytes:
    separator = b"\n" * separator_length
    begin = (
        "# >>> fram-code-wire managed mcp_servers.fram "
        f"separator={separator_length}\n"
    ).encode("ascii")
    return separator + begin + render_toml(server) + END_MARKER


def owned_span(data: bytes):
    """Return the full raw span owned by Fram, including its separator."""
    matches = list(BEGIN_RE.finditer(data))
    if not matches:
        return None
    if len(matches) != 1:
        raise ValueError("multiple managed Fram begin markers")

    begin = matches[0]
    end_start = data.find(END_MARKER, begin.end())
    if end_start < 0:
        raise ValueError("managed Fram begin marker has no matching end marker")
    if data.find(END_MARKER, end_start + len(END_MARKER)) >= 0:
        raise ValueError("multiple managed Fram end markers")

    separator_length = int(begin.group(1))
    start = begin.start() - separator_length
    if start < 0 or data[start : begin.start()] != b"\n" * separator_length:
        raise ValueError("managed Fram separator does not match its marker")
    return start, end_start + len(END_MARKER), separator_length


def legacy_span(data: bytes):
    """Find an unmarked Fram table emitted by an older helper version."""
    lines = data.splitlines(keepends=True)
    offsets = []
    offset = 0
    for line in lines:
        offsets.append(offset)
        offset += len(line)

    for index, line in enumerate(lines):
        bare = line.rstrip(b"\r\n")
        if not FRAM_HEADER_RE.match(bare):
            continue
        end_index = index + 1
        while end_index < len(lines):
            candidate = lines[end_index].rstrip(b"\r\n")
            if ANY_TABLE_RE.match(candidate) and not FRAM_NESTED_RE.match(candidate):
                break
            end_index += 1

        # Blank lines immediately before the next unrelated table are not
        # provably legacy-owned, so leave them outside the removal span.
        while end_index > index + 1 and not lines[end_index - 1].strip():
            end_index -= 1
        end = offsets[end_index] if end_index < len(lines) else len(data)
        return offsets[index], end
    return None


def append_separator(data: bytes) -> int:
    if not data or data.endswith(b"\n\n"):
        return 0
    if data.endswith(b"\n"):
        return 1
    return 2


def write_bytes(path: str, data: bytes) -> None:
    with open(path, "wb") as handle:
        handle.write(data)


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit("usage: fram-code-wire-toml.py set|unset <config.toml> [server-json]")
    mode, path = sys.argv[1], sys.argv[2]
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except FileNotFoundError:
        data = b""

    try:
        managed = owned_span(data)
    except ValueError as error:
        sys.exit(f"fram-code-wire-toml.py: refusing malformed managed block: {error}")

    if mode == "set":
        if len(sys.argv) < 4:
            sys.exit("fram-code-wire-toml.py set: missing server-json")
        server = json.loads(sys.argv[3])
        if managed:
            start, end, separator_length = managed
            updated = (
                data[:start]
                + render_owned(server, separator_length)
                + data[end:]
            )
        else:
            legacy = legacy_span(data)
            if legacy:
                start, end = legacy
                updated = data[:start] + render_owned(server, 0) + data[end:]
            else:
                separator_length = append_separator(data)
                updated = data + render_owned(server, separator_length)
        write_bytes(path, updated)
    elif mode == "unset":
        if managed:
            start, end, _ = managed
            updated = data[:start] + data[end:]
        else:
            legacy = legacy_span(data)
            if not legacy:
                return
            start, end = legacy
            updated = data[:start] + data[end:]

        if updated == b"":
            try:
                os.remove(path)
            except FileNotFoundError:
                pass
        else:
            write_bytes(path, updated)
    else:
        sys.exit(f"unknown mode {mode!r} (want set|unset)")


if __name__ == "__main__":
    main()
