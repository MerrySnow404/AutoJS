#!/usr/bin/env python
"""Driver for the AutoJS MCP endpoint: state, aim, walk (with hazard checks), eval."""
import sys, json, time, math, urllib.request

URL = "http://127.0.0.1:25585/mcp"


def call(name, args, timeout=180):
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": name, "arguments": args}}
    req = urllib.request.Request(URL, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def ev(side, code, timeout_ms=9000):
    resp = call("eval_js", {"side": side, "code": code, "timeout_ms": timeout_ms})
    txt = resp["result"]["content"][0]["text"]
    obj = json.loads(txt)
    if not obj.get("ok"):
        raise RuntimeError("%s | %s" % (obj.get("error"), obj.get("diagnostics")))
    for d in obj.get("diagnostics") or []:
        sys.stderr.write("[diag] %s\n" % d)
    return obj.get("value")


def srv(code, t=9000):
    return ev("server", code, t)


def cli(code, t=9000):
    return ev("client", code, t)


def state(side="server"):
    return json.loads(ev(side, "AutoJS.state()"))


def _js(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    return repr(v)


def move(fwd, strafe=0, jump=False, sprint=False):
    return cli("AutoJS.move(%s,%s,%s,%s); 'ok'" % (_js(fwd), _js(strafe), _js(jump), _js(sprint)))


def stop():
    return move(0, 0, False, False)


STEP_TPL = r"""
(function(){
  var p = Client.player;
  var px = p.getX(), py = p.getY(), pz = p.getZ();
  var st = JSON.parse(AutoJS.state());
  var dx = (%(tx)s) - px, dz = (%(tz)s) - pz;
  var d = Math.sqrt(dx*dx + dz*dz);
  var r = {d: Math.round(d*10)/10, h: st.health, g: st.onGround, w: st.inWater, f: st.onFire, s: "walk"};
  if (st.inWater || st.inLava || st.onFire) { AutoJS.move(0,0,false,false); r.s="hazard"; return JSON.stringify(r); }
  if (st.health < %(hp)s) { AutoJS.move(0,0,false,false); r.s="hurt"; return JSON.stringify(r); }
  if (d < %(tol)s) { AutoJS.move(0,0,false,false); r.s="arrived"; return JSON.stringify(r); }
  var base = Math.atan2(-(dx/d), dz/d) * 180 / Math.PI;
  function blk(x,y,z){ var s = AutoJS.blockInfo(x,y,z); return s ? JSON.parse(s) : null; }
  var fy = Math.floor(py + 0.2);
  function score(off, D){
    var a = (base + off) * Math.PI / 180;
    var ux = -Math.sin(a), uz = Math.cos(a);
    var fx = Math.floor(px + ux*D), fz = Math.floor(pz + uz*D);
    var feet = blk(fx,fy,fz), head = blk(fx,fy+1,fz);
    var g1 = blk(fx,fy-1,fz), g2 = blk(fx,fy-2,fz), g3 = blk(fx,fy-3,fz);
    if (!feet || !head || !g1) return 0;
    if (feet.liquid || head.liquid || g1.liquid) return 0;
    if (feet.air === false && head.air === false) return 0;
    if (feet.air === false && head.air === true) return 1;
    if (feet.air && g1.air && g2 && g2.air && g3 && g3.air) return 0;
    if (feet.air && g1.air) return 1;
    return 2;
  }
  var offs = [0,15,-15,30,-30,45,-45,60,-60,75,-75,90,-90], best = null;
  for (var i=0;i<offs.length;i++){
    var o = offs[i];
    var sc = Math.min(score(o,1.2), score(o,2.4));
    if (sc > 0 && (!best || sc > best.sc)) { best = {o:o, sc:sc}; }
    if (best && best.sc >= 2) break;
  }
  if (!best) { AutoJS.move(0,0,false,false); r.s = "blocked"; return JSON.stringify(r); }
  Client.player.setYaw(base + best.o);
  Client.player.setPitch(0);
  AutoJS.move(1, 0, best.sc === 1, %(sprint)s);
  r.o = best.o; r.sc = best.sc;
  return JSON.stringify(r);
})()
"""


def step(tx, tz, tol=2.0, sprint=True, hp=12.0):
    code = STEP_TPL % {"tx": repr(float(tx)), "tz": repr(float(tz)), "tol": repr(float(tol)),
                       "sprint": _js(sprint), "hp": repr(float(hp))}
    return json.loads(cli(code))


def smart_goto(tx, tz, tol=2.2, max_steps=900, sprint=True, verbose=True):
    """Walk with per-step local navigation. Returns (status, note)."""
    last = None
    stuck = 0
    for i in range(max_steps):
        r = step(tx, tz, tol=tol, sprint=sprint)
        if r["s"] == "arrived":
            stop()
            return "arrived", ""
        if r["s"] in ("hazard", "hurt", "blocked"):
            stop()
            return r["s"], json.dumps(r)
        p = state("client")["pos"]
        if last is not None and math.hypot(p[0] - last[0], p[2] - last[2]) < 0.15:
            stuck += 1
        else:
            stuck = 0
        last = p
        if i % 10 == 0 and verbose:
            print("  step %d d=%.1f h=%s y=%.0f o=%s sc=%s" % (i, r["d"], r["h"], p[1], r.get("o"), r.get("sc")))
        if stuck > 30:
            stop()
            return "stuck", json.dumps(r)
        time.sleep(0.25)
    stop()
    return "timeout", ""


def walk_to(tx, tz, tol=2.2, sprint=True, rounds=10):
    """smart_goto with lateral detours when progress stalls."""
    for k in range(rounds):
        st, note = smart_goto(tx, tz, tol=tol, sprint=sprint)
        if st == "arrived":
            return st, note
        p = state("server")["pos"]
        d = math.hypot(tx - p[0], tz - p[2])
        if d < 6:
            return "close", note
        ang = math.atan2(-(tx - p[0]), tz - p[2])
        side = 1 if k % 2 == 0 else -1
        for off in (55, -55, 90, -90):
            if off * side < 0:
                continue
            a = math.radians(ang + off if side > 0 else ang - off)
            ox, oz = p[0] - math.sin(a) * 10, p[2] + math.cos(a) * 10
            print("  detour %d -> %.0f,%.0f" % (k, ox, oz))
            r = smart_goto(ox, oz, tol=2.5, max_steps=45, sprint=sprint, verbose=False)
            if r[0] == "arrived":
                break
    return "giveup", ""


def hunt(dx, dz, legs=12, leg=45, what="log", radius=72, verbose=True):
    """Walk in a straight direction, scanning for `what` each leg. Returns first hits."""
    n = math.hypot(dx, dz) or 1.0
    dx, dz = dx / n, dz / n
    for i in range(legs):
        hits = json.loads(srv('AutoJS.findBlocks(%r, %d, 4)' % (what, radius), 15000))
        if hits:
            print("FOUND", json.dumps(hits))
            return hits
        p = state("server")["pos"]
        tx, tz = p[0] + dx * leg, p[2] + dz * leg
        st = nav(tx, tz, timeout=150)
        q = state("server")["pos"]
        if verbose:
            print("leg %d: %.0f,%.0f -> %.0f,%.0f (%.0f) y=%.0f h=%.0f %s"
                  % (i, p[0], p[2], q[0], q[2], math.hypot(tx - q[0], tz - q[2]), q[1], state("server")["health"], st))
        if st not in (True,):
            print("  stopping:", st)
            if st in ("danger", "dead"):
                return None
    return None


import os
SCRATCH = os.path.dirname(os.path.abspath(__file__))
GOAL = os.path.join(SCRATCH, "goal.json")
STAT = os.path.join(SCRATCH, "status.json")


def set_goal(tx, tz, active=True):
    with open(GOAL, "w", encoding="utf-8") as f:
        f.write(json.dumps({"x": tx, "z": tz, "active": active}))


def read_status():
    try:
        with open(STAT, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def nav(tx, tz, timeout=420, poll=1.5, tol=2.5, label=""):
    """Use the client-side walker to reach (tx,tz). Returns True on arrival."""
    set_goal(tx, tz, True)
    t0 = time.time()
    bad = 0
    lastprint = 0
    while time.time() - t0 < timeout:
        time.sleep(poll)
        s = read_status()
        ss = state("server")
        p = ss["pos"]
        d = math.hypot(tx - p[0], tz - p[2])
        if ss.get("dead"):
            print("  DEAD at %.0f,%.0f -> respawning" % (p[0], p[2]))
            set_goal(0, 0, False)
            cli("Client.player.respawn(); Client.setScreen(null); 'ok'")
            time.sleep(1.5)
            return "dead"
        if s.get("state") == "arrived" or d < tol:
            set_goal(p[0], p[2], False)
            return True
        if s.get("state") in ("danger", "nopath"):
            bad += 1
            if bad >= 6:
                print("  walker stuck: %s raw=%s" % (s.get("state"), s.get("raw")))
                set_goal(p[0], p[2], False)
                return s.get("state")
        else:
            bad = 0
        if time.time() - t0 - lastprint > 10:
            lastprint = time.time() - t0
            print("  %s d=%.0f y=%.0f h=%.0f %s path=%s still=%s"
                  % (label, d, p[1], ss["health"], s.get("state"), s.get("path"), s.get("still")))
    set_goal(tx, tz, False)
    return "timeout"


def travel(tx, tz, timeout=900, tol=5, label=""):
    """Long-range: keep re-issuing the goal until we get there or time runs out."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        left = timeout - (time.time() - t0)
        r = nav(tx, tz, timeout=min(200, left), tol=tol)
        if r is True:
            return True
        if r == "dead":
            time.sleep(3)
            continue
        p = state("server")["pos"]
        if math.hypot(tx - p[0], tz - p[2]) < tol:
            return True
        print("  travel retry (%s) at %.0f,%.0f" % (r, p[0], p[2]))
    return "timeout"


def scan(what="log", radius=72, n=4):
    return json.loads(srv("AutoJS.findBlocks(%r, %d, %d)" % (what, radius, n), 15000))


def pillar_up(steps=8, verbose=True):
    """Jump-place a dirt block under ourselves repeatedly (vanilla pillaring)."""
    for k in range(steps):
        st = state()
        p = st["pos"]
        x, y, z = int(math.floor(p[0])), int(math.floor(p[1])), int(math.floor(p[2]))
        srv('(function(){var pts=%s;var d=0;for(var i=0;i<pts.length;i++){'
            'var s=AutoJS.blockInfo(pts[i][0],pts[i][1],pts[i][2]);if(!s)continue;'
            'var b=JSON.parse(s);if(b.air===false&&b.liquid===false){'
            'AutoJS.mine(AutoJS.pos(pts[i][0],pts[i][1],pts[i][2]));d++;}}return d;})()'
            % json.dumps([[x, y + 2, z], [x, y + 3, z], [x, y + 4, z]]))
        cli("AutoJS.move(0,0,true,false); 'j'")
        time.sleep(0.06)
        cli("AutoJS.move(0,0,false,false); 'n'")
        time.sleep(0.16)
        r = srv('""+AutoJS.place(AutoJS.pos(%d,%d,%d),"up")' % (x, y - 1, z))
        time.sleep(0.55)
        st2 = state()
        p2 = st2["pos"]
        if verbose:
            print("  pillar %d: y %.2f -> %.2f place=%s h=%.1f" % (k, p[1], p2[1], r, st2["health"]))
        if st2.get("dead"):
            return "dead"
    return "ok"


MOB_JS = r'''(function(){
  var p = AutoJS.player(); var list = AutoJS.entitiesNear(28); var best=null, bd=1e9;
  for (var i=0;i<list.length;i++){
    var e=list[i]; var s=""+e;
    if (!/%(pat)s/.test(s)) continue;
    if (!e.isAlive()) continue;
    var d = Math.pow(e.getX()-p.getX(),2)+Math.pow(e.getZ()-p.getZ(),2);
    if (d<bd){bd=d;best=e;}
  }
  if(!best) return "none";
  var d=Math.sqrt(bd);
  var hit = d<=4.2 ? (""+AutoJS.attack(best)) : "-";
  return JSON.stringify({x:best.getX(), y:best.getY(), z:best.getZ(), d:d, hit:hit});
})()'''


def kill_mobs(pat="Sheep|Cow|Pig|Chicken", maxtime=200, count=1):
    """Chase and kill nearby passive mobs, then collect drops."""
    t0 = time.time()
    kills = 0
    code = MOB_JS % {"pat": pat}
    while time.time() - t0 < maxtime and kills < count:
        r = srv(code)
        if r == "none":
            print("  no target left")
            break
        o = json.loads(r)
        if o["hit"] == "true":
            kills += 1
            print("  hit at d=%.1f kills=%d" % (o["d"], kills))
            time.sleep(0.62)
            continue
        p = state("server")["pos"]
        dx, dz = o["x"] - p[0], o["z"] - p[2]
        dl = math.hypot(dx, dz) or 1
        cli("Client.player.setYaw(%r); Client.player.setPitch(0); 'ok'"
            % (math.degrees(math.atan2(-dx / dl, dz / dl))))
        move(1, 0, True, True)
        time.sleep(0.35)
    stop()
    srv("AutoJS.collect(8)")
    return state("server")


def eat_all(maxitems=8):
    """Eat every edible item we carry (hold the client use key so the server does not cancel)."""
    for _ in range(maxitems):
        st = state("server")
        if not st.get("canEat"):
            break
        sel = srv('(function(){var inv=AutoJS.player().getInventory();var DC=Java.loadClass("net.minecraft.core.component.DataComponents");'
                  'for(var i=0;i<inv.getContainerSize();i++){var s=inv.getItem(i);if(s.isEmpty())continue;'
                  'var f=s.get(DC.FOOD);if(f!==null&&f!==undefined){inv.selected=i;return i;}}return -1;})()')
        if str(sel) == "-1":
            print("  no food item")
            break
        cli("Client.options.keyUse.setDown(true)")
        srv("AutoJS.use()")
        time.sleep(2.3)
        cli("Client.options.keyUse.setDown(false)")
        s = state("server")
        print("  ate slot %s -> food=%s h=%.1f" % (sel, s["food"], s["health"]))
        time.sleep(0.3)
    return state("server")


MINE_JS = ('(function(){var x=%d,y=%d,z=%d;var s=AutoJS.blockInfo(x,y,z);'
           'if(!s)return "?";var b=JSON.parse(s);'
           'if(b.air===true||b.liquid===true)return "skip";'
           'return ""+AutoJS.mine(AutoJS.pos(x,y,z));})()')


def mine_cell(x, y, z):
    return srv(MINE_JS % (int(x), int(y), int(z)))


def dig_stair(ux, uz, steps=6, sprint=True, verbose=True):
    """Carve an ascending staircase one block at a time toward (ux,uz)."""
    n = math.hypot(ux, uz) or 1.0
    ux, uz = ux / n, uz / n
    for k in range(steps):
        p = state("server")["pos"]
        fx = int(math.floor(p[0] + ux * 1.3))
        fz = int(math.floor(p[2] + uz * 1.3))
        fy = int(math.floor(p[1]))
        got = []
        for dy in (1, 2, 3):
            got.append(mine_cell(fx, fy + dy, fz))
        cli("Client.player.setYaw(%r); Client.player.setPitch(0); 'ok'"
            % (math.degrees(math.atan2(-ux, uz))))
        move(1, 0, True, sprint)
        time.sleep(0.7)
        stop()
        srv("AutoJS.collect(5)")
        p2 = state("server")["pos"]
        if verbose:
            print("  dig %d: y %.1f->%.1f mined=%s" % (k, p[1], p2[1], got))
        if state("server").get("dead"):
            return "dead"
    return state("server")["pos"]


def nav_dig(tx, tz, timeout=300, tol=5, label=""):
    """nav, falling back to carving a staircase when the pathfinder dead-ends."""
    t0 = time.time()
    tries = 0
    while time.time() - t0 < timeout:
        r = nav(tx, tz, timeout=min(120, max(20, timeout - (time.time() - t0))), tol=tol, label=label)
        if r is True:
            return True
        if r == "dead":
            time.sleep(2.5)
            continue
        p = state("server")["pos"]
        if math.hypot(tx - p[0], tz - p[2]) < tol:
            return True
        tries += 1
        if tries > 12:
            return r
        print("  nav failed (%s); digging a staircase" % r)
        dig_stair(tx - p[0], tz - p[2], steps=5)
    return "timeout"


def force_travel(tx, tz, maxsteps=600, tol=4, sprint=True, verbose=True):
    """Dumb but robust: keep pushing toward the target; carve a step when blocked."""
    t0 = time.time()
    for i in range(maxsteps):
        p = state("server")["pos"]
        dx, dz = tx - p[0], tz - p[2]
        d = math.hypot(dx, dz)
        if d < tol:
            stop()
            return True
        st = state("server")
        if st.get("dead"):
            return "dead"
        cli("Client.player.setYaw(%r); Client.player.setPitch(0); 'ok'"
            % (math.degrees(math.atan2(-dx / d, dz / d))))
        # mine the two blocks above the feet ahead of us, in case it is a step up
        fx = int(math.floor(p[0] + dx / d * 1.3))
        fz = int(math.floor(p[2] + dz / d * 1.3))
        fy = int(math.floor(p[1]))
        for dy in (1, 2):
            mine_cell(fx, fy + dy, fz)
        move(1, 0, True, sprint)
        time.sleep(0.75)
        if i % 12 == 0:
            srv("AutoJS.collect(5)")
            if verbose:
                q = state("server")["pos"]
                print("  fwd %d: %.0f,%.0f (d=%.0f) y=%.0f h=%.0f food=%s t=%.0fs"
                      % (i, q[0], q[2], math.hypot(tx - q[0], tz - q[2]), q[1],
                         state("server")["health"], state("server")["food"], time.time() - t0))
    stop()
    return "maxsteps"


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "st"
    if cmd == "st":
        for s in ("server", "client"):
            try:
                print(s, json.dumps(state(s), ensure_ascii=False))
            except Exception as e:
                print(s, "ERR", e)
    elif cmd == "pos":
        print(json.dumps(state("server")["pos"]))
    elif cmd == "ev":
        side = sys.argv[2]
        code = open(sys.argv[3], encoding="utf-8").read() if len(sys.argv) > 3 and sys.argv[3] != "-" else sys.stdin.read()
        print(ev(side, code))
    elif cmd == "goto":
        print(walk_to(float(sys.argv[2]), float(sys.argv[3])))
    elif cmd == "travel":
        print(travel(float(sys.argv[2]), float(sys.argv[3])))
    elif cmd == "scan":
        print(json.dumps(scan(sys.argv[2] if len(sys.argv)>2 else "log")))
    elif cmd == "nav":
        print(nav(float(sys.argv[2]), float(sys.argv[3])))
    elif cmd == "hunt":
        print(json.dumps(hunt(float(sys.argv[2]), float(sys.argv[3]))))
    elif cmd == "respawn":
        print(cli("Client.player.respawn(); Client.setScreen(null); 'ok'"))
    elif cmd == "go":
        print(move(float(sys.argv[2]), float(sys.argv[3]),
                   sys.argv[4] == "1", sys.argv[5] == "1"))
    elif cmd == "yaw":
        print(cli("Client.player.setYaw(%s); Client.player.setPitch(0); 'ok'" % sys.argv[2]))
    elif cmd == "stop":
        print(stop())
    else:
        raise SystemExit("unknown cmd " + cmd)


if __name__ == "__main__":
    main()
