#!/usr/bin/env python
"""AutoJS driver: sends gui.js helpers + a snippet to eval_js and prints the value.

Usage:
  python gui.py client < snippet.js
  python gui.py client snippet.js
  python gui.py server -e "AutoJS.where()"
Raw mode: python gui.py client --raw < snippet.js     (no helpers prepended)
Status:   python gui.py --status
"""
import json
import os
import sys
import urllib.request

URL = "http://127.0.0.1:25585/mcp"
SCRATCH = os.path.dirname(os.path.abspath(__file__))
HELPERS = os.path.join(SCRATCH, "gui.js")


def call(name, args, timeout=180):
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": name, "arguments": args}}
    req = urllib.request.Request(URL, data=json.dumps(body).encode("utf-8"),
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def ev(side, code, timeout_ms=20000, quiet=False):
    resp = call("eval_js", {"side": side, "code": code, "timeout_ms": timeout_ms})
    obj = json.loads(resp["result"]["content"][0]["text"])
    diags = obj.get("diagnostics") or []
    if not quiet:
        for d in diags:
            sys.stderr.write("[diag] %s\n" % d)
    if not obj.get("ok"):
        raise RuntimeError("%s | %s" % (obj.get("error"), diags))
    return obj.get("value")


def main():
    args = sys.argv[1:]
    if args and args[0] == "--status":
        print(call("status", {}))
        return
    side = args[0] if args else "client"
    raw = False
    rest = args[1:]
    if rest and rest[0] == "--raw":
        raw = True
        rest = rest[1:]
    if rest and rest[0] == "-e":
        body = rest[1]
    elif rest:
        body = open(rest[0], encoding="utf-8").read()
    else:
        body = sys.stdin.read()
    code = body if raw else open(HELPERS, encoding="utf-8").read() + "\n" + body
    print(ev(side, code))


if __name__ == "__main__":
    main()
