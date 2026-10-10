import sys, json, urllib.request

side = sys.argv[1]
code = sys.stdin.read()

def call(name, args):
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": name, "arguments": args}}
    req = urllib.request.Request("http://127.0.0.1:25585/mcp",
                                 data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)

resp = call("eval_js", {"side": side, "code": code})
try:
    txt = resp["result"]["content"][0]["text"]
except Exception:
    print(json.dumps(resp))
    sys.exit(1)

try:
    obj = json.loads(txt)
except Exception:
    print(txt)
    sys.exit(0)

if obj.get("ok"):
    print(obj.get("value", ""))
    for d in obj.get("diagnostics", []) or []:
        print("[diag] " + d, file=sys.stderr)
else:
    print("ERROR: " + str(obj.get("error")))
    st = obj.get("stack")
    if st:
        print("STACK: " + str(st), file=sys.stderr)
    for d in obj.get("diagnostics", []) or []:
        print("[diag] " + d, file=sys.stderr)
    sys.exit(2)
