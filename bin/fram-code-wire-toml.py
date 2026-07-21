#!/usr/bin/env python3
"""fram-code-wire-toml.py set|unset <config.toml> [server-json]

Textual, section-scoped editor for the [mcp_servers.fram] (+ nested
[mcp_servers.fram.*]) block in a Codex `.codex/config.toml`. Touches ONLY
that block's lines; every other line in the file is preserved byte-for-byte
(no full TOML parse/rewrite, so unrelated [projects.*] / [features] etc.
formatting survives untouched).
"""
import json
import re
import sys

FRAM_HEADER_RE = re.compile(r"^\[mcp_servers\.fram\]\s*$")
FRAM_NESTED_RE = re.compile(r"^\[mcp_servers\.fram\.[^\]]+\]\s*$")
ANY_TABLE_RE = re.compile(r"^\[[^\]]+\]\s*$")


def toml_str(s: str) -> str:
    return json.dumps(s)


def render_block(server: dict) -> list[str]:
    lines = ["[mcp_servers.fram]"]
    lines.append(f"command = {toml_str(server['command'])}")
    args = server.get("args", [])
    lines.append("args = [" + ", ".join(toml_str(a) for a in args) + "]")
    env = server.get("env", {})
    if env:
        lines.append("")
        lines.append("[mcp_servers.fram.env]")
        for k, v in env.items():
            lines.append(f"{k} = {toml_str(str(v))}")
    return lines


def find_block_span(lines: list[str]):
    """Return (start, end) exclusive of the existing fram block (header
    through the line before the next non-fram table), or None if absent."""
    for i, line in enumerate(lines):
        if FRAM_HEADER_RE.match(line.rstrip("\n")):
            j = i + 1
            while j < len(lines):
                stripped = lines[j].rstrip("\n")
                if ANY_TABLE_RE.match(stripped) and not FRAM_NESTED_RE.match(stripped):
                    break
                j += 1
            return (i, j)
    return None


def main():
    if len(sys.argv) < 3:
        sys.exit("usage: fram-code-wire-toml.py set|unset <config.toml> [server-json]")
    mode, path = sys.argv[1], sys.argv[2]
    try:
        with open(path, "r") as f:
            lines = f.readlines()
    except FileNotFoundError:
        lines = []

    span = find_block_span(lines)

    if mode == "set":
        server = json.loads(sys.argv[3])
        block = [l + "\n" for l in render_block(server)]
        if span:
            start, end = span
            lines = lines[:start] + block + lines[end:]
        else:
            if lines and lines[-1].strip() != "":
                lines.append("\n")
            elif lines:
                pass
            else:
                pass
            if lines:
                lines.append("\n")
            lines += block
        with open(path, "w") as f:
            f.writelines(lines)
    elif mode == "unset":
        if span:
            start, end = span
            # also drop one preceding blank line we may have inserted (only
            # if it is blank and not the very first line of the file).
            if start > 0 and lines[start - 1].strip() == "":
                start -= 1
            lines = lines[:start] + lines[end:]
            with open(path, "w") as f:
                f.writelines(lines)
        # else: nothing to remove — leave file untouched.
    else:
        sys.exit(f"unknown mode {mode!r} (want set|unset)")


if __name__ == "__main__":
    main()
