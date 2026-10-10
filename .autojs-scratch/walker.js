(function () {
  var GST = "D:/work/AutoJS/.autojs-scratch/goal.json";
  var SST = "D:/work/AutoJS/.autojs-scratch/status.json";
  var S = { gx: 0, gz: 0, active: false, key: "", path: [], pi: 0, state: "idle", tick: 0,
            lastPos: null, still: 0, planEvery: 25, hp: 20, raw: "", d: 0,
            landPos: null, air: 300 };

  function blk(x, y, z) {
    var s = AutoJS.blockInfo(x, y, z);
    return s ? JSON.parse(s) : null;
  }
  function solid(b) { return b && b.air === false && b.liquid === false; }
  function K(i, j) { return i + "," + j; }

  function heightAt(x, z, py) {
    for (var y = py + 3; y >= py - 9; y--) {
      var b = blk(x, y, z);
      if (!b) return null;
      if (b.liquid) return null;
      if (solid(b)) {
        var a1 = blk(x, y + 1, z), a2 = blk(x, y + 2, z);
        if (a1 && a2 && a1.air === true && a2.air === true && !a1.liquid && !a2.liquid) return y + 1;
      }
    }
    return null;
  }

  var R = 22;
  var ORTH = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  var DIRS = [[1, 0, 1], [-1, 0, 1], [0, 1, 1], [0, -1, 1],
              [1, 1, 1.42], [1, -1, 1.42], [-1, 1, 1.42], [-1, -1, 1.42]];

  function makeGrid(px, pz, py) {
    var H = {};
    function Hh(i, j) {
      var k = K(i, j);
      if (H.hasOwnProperty(k)) return H[k];
      var v = (i < -R || i > R || j < -R || j > R) ? null : heightAt(px + i, pz + j, py);
      H[k] = v;
      return v;
    }
    var D = {};
    function dry(i, j) {
      var k = "d" + i + "," + j;
      if (D.hasOwnProperty(k)) return D[k];
      var ok = Hh(i, j) !== null;
      if (ok) {
        for (var n = 0; n < ORTH.length; n++) {
          if (Hh(i + ORTH[n][0], j + ORTH[n][1]) === null) { ok = false; break; }
        }
      }
      D[k] = ok;
      return ok;
    }
    return { Hh: Hh, dry: dry, H: H };
  }

  function bfs(g, strict, gi, gj) {
    var Hh = g.Hh, dry = g.dry;
    var prev = {}, seen = {}, q = [[0, 0]], head = 0;
    seen[K(0, 0)] = 1;
    while (head < q.length && head < 9000) {
      var cur = q[head++];
      var ci = cur[0], cj = cur[1], ck = K(ci, cj);
      var h0 = Hh(ci, cj);
      if (h0 === null) continue;
      for (var n = 0; n < DIRS.length; n++) {
        var ni = ci + DIRS[n][0], nj = cj + DIRS[n][1];
        if (ni < -R || ni > R || nj < -R || nj > R) continue;
        var nk = K(ni, nj);
        if (seen[nk]) continue;
        var nh = Hh(ni, nj);
        if (nh === null) continue;
        if (strict && !dry(ni, nj)) continue;
        if (nh - h0 > 1) continue;
        if (h0 - nh > 2) continue;
        if (DIRS[n][2] > 1) {
          if (Hh(ci + DIRS[n][0], cj) === null) continue;
          if (Hh(ci, cj + DIRS[n][1]) === null) continue;
        }
        seen[nk] = 1; prev[nk] = ck; q.push([ni, nj]);
      }
    }
    return { seen: seen, prev: prev, q: q };
  }

  function buildPath(prev, target) {
    var path = [], cur = target, hops = 0;
    while (cur && cur !== K(0, 0) && hops++ < 3000) {
      var pr = cur.split(",");
      path.unshift([parseInt(pr[0], 10), parseInt(pr[1], 10)]);
      cur = prev[cur];
    }
    return path;
  }

  function plan() {
    var p = Client.player;
    var px = Math.floor(p.getX()), py = Math.floor(p.getY()), pz = Math.floor(p.getZ());
    var g = makeGrid(px, pz, py);
    if (g.Hh(0, 0) === null) { g.H[K(0, 0)] = py; }
    var gi = Math.max(-R, Math.min(R, Math.round(S.gx - px)));
    var gj = Math.max(-R, Math.min(R, Math.round(S.gz - pz)));
    if (g.Hh(gi, gj) === null) {
      var best = null;
      for (var i = -R; i <= R; i += 1) {
        for (var j = -R; j <= R; j += 1) {
          if (g.Hh(i, j) === null) continue;
          var dd = (i - gi) * (i - gi) + (j - gj) * (j - gj);
          if (!best || dd < best.d) best = { i: i, j: j, d: dd };
        }
      }
      if (!best) return null;
      gi = best.i; gj = best.j;
    }
    var target = K(gi, gj);
    var r = bfs(g, true, gi, gj);
    var strict = true;
    if (!r.seen[target]) {
      var r2 = bfs(g, false, gi, gj);
      if (r2.seen[target]) { r = r2; strict = false; }
    }
    var res = { px: px, py: py, pz: pz, target: target, gi: gi, gj: gj, gr: g, strict: strict };
    if (!r.seen[target]) {
      var pick = null;
      for (var m = 0; m < r.q.length; m++) {
        var mi = r.q[m][0], mj = r.q[m][1];
        if (mi === 0 && mj === 0) continue;
        var gd = (mi - gi) * (mi - gi) + (mj - gj) * (mj - gj);
        if (!pick || gd < pick.d) pick = { i: mi, j: mj, d: gd };
      }
      if (!pick) return null;
      if (pick.d >= gi * gi + gj * gj - 2) return null;
      res.path = buildPath(r.prev, K(pick.i, pick.j));
      res.partial = true;
    } else {
      res.path = buildPath(r.prev, target);
      res.partial = false;
    }
    res.H = g.H;
    res.cells = r.q.length;
    return res;
  }

  function wstat(extra) {
    var o = { state: S.state, d: S.d, h: S.hp, path: S.path ? S.path.length : 0,
              tick: S.tick, raw: S.raw, still: S.still, cells: S.cells,
              partial: S.partial, air: S.air, strict: S.strict };
    for (var k in extra) o[k] = extra[k];
    try { AutoJS.write(SST, JSON.stringify(o)); } catch (e) {}
  }

  ClientEvents.tick(function (e) {
    S.tick++;
    var p = Client.player;
    if (!p) return;

    if (S.tick % 8 === 0) {
      var g = null;
      try {
        var raw = AutoJS.read(GST);
        S.raw = raw ? ("" + raw).substring(0, 80) : "null";
        if (raw && ("" + raw).length > 1) g = JSON.parse("" + raw);
      } catch (err) { g = null; S.raw = "ERR " + err; }
      if (g && typeof g.x === "number") {
        var act = !!g.active, key = g.x + "," + g.z;
        if (act !== S.active || key !== S.key) {
          S.active = act; S.key = key; S.gx = g.x; S.gz = g.z; S.path = []; S.pi = 0;
        }
      }
    }

    var st = JSON.parse(AutoJS.state());
    S.hp = st.health;
    var airParts = ("" + st.air).split("/");
    S.air = parseInt(airParts[0], 10);
    if (!S.active) {
      if (S.wasActive) { AutoJS.move(0, 0, false, false); S.wasActive = false; }
      S.state = "idle"; wstat(); return;
    }
    if (!S.wasActive) { S.wasActive = true; }
    if (st.dead) { AutoJS.move(0, 0, false, false); S.state = "dead"; wstat(); return; }

    var bx = p.getX(), bz = p.getZ();
    var dg = Math.sqrt((S.gx - bx) * (S.gx - bx) + (S.gz - bz) * (S.gz - bz));
    S.d = Math.round(dg * 10) / 10;
    if (dg < 2.0) { AutoJS.move(0, 0, false, false); S.state = "arrived"; wstat(); return; }
    if (st.inLava || st.onFire) { AutoJS.move(0, 0, false, false); S.state = "danger"; wstat(); return; }

    if (S.lastPos && Math.abs(bx - S.lastPos[0]) < 0.08 && Math.abs(bz - S.lastPos[1]) < 0.08) S.still++;
    else S.still = 0;
    S.lastPos = [bx, bz];

    if (st.inWater) {
      if (S.air < 120) {
        AutoJS.move(0, 0, true, false);
        S.state = "surface";
        wstat();
        return;
      }
      if (S.tick % 6 === 0) {
        var px0 = Math.floor(bx), py0 = Math.floor(p.getY()), pz0 = Math.floor(bz);
        var bestL = null;
        for (var ai = -24; ai <= 24; ai += 2) {
          for (var aj = -24; aj <= 24; aj += 2) {
            if (heightAt(px0 + ai, pz0 + aj, py0) === null) continue;
            var ld = ai * ai + aj * aj;
            if (ld === 0) continue;
            if (!bestL || ld < bestL.d) bestL = { i: ai, j: aj, d: ld };
          }
        }
        if (!bestL && S.landPos) {
          bestL = { fx: S.landPos[0], fz: S.landPos[1] };
        }
        if (bestL) {
          var wx0 = bestL.fx !== undefined ? bestL.fx : px0 + bestL.i + 0.5;
          var wz0 = bestL.fz !== undefined ? bestL.fz : pz0 + bestL.j + 0.5;
          var ddx = wx0 - bx, ddz = wz0 - bz;
          var ddl = Math.sqrt(ddx * ddx + ddz * ddz) || 1;
          p.setYaw(Math.atan2(-(ddx / ddl), ddz / ddl) * 180 / Math.PI);
        }
      }
      AutoJS.move(1, 0, true, false);
      S.state = "swim";
      wstat();
      return;
    }

    if (st.onGround) S.landPos = [bx, bz];

    if (S.path.length === 0 || S.pi >= S.path.length || S.tick % S.planEvery === 0 || S.still > 50) {
      var pl = plan();
      if (!pl) { AutoJS.move(0, 0, false, false); S.state = "nopath"; wstat(); return; }
      S.origin = [pl.px, pl.py, pl.pz];
      S.H = pl.H;
      S.path = pl.path;
      S.pi = 0;
      S.cells = pl.cells;
      S.partial = pl.partial;
      S.strict = pl.strict;
    }

    var guard = 0;
    while (S.pi < S.path.length && guard++ < 60) {
      var cc = S.path[S.pi];
      var wx = S.origin[0] + cc[0] + 0.5, wz = S.origin[2] + cc[1] + 0.5;
      var ddd = Math.sqrt((wx - bx) * (wx - bx) + (wz - bz) * (wz - bz));
      if (ddd < 0.9) S.pi++; else break;
    }
    if (S.pi >= S.path.length) { S.path = []; S.state = "replan"; wstat(); return; }

    var aim = S.path[Math.min(S.pi + 1, S.path.length - 1)];
    var ax = S.origin[0] + aim[0] + 0.5, az = S.origin[2] + aim[1] + 0.5;
    var dx = ax - bx, dz = az - bz;
    var dl = Math.sqrt(dx * dx + dz * dz) || 1;
    p.setYaw(Math.atan2(-(dx / dl), dz / dl) * 180 / Math.PI);
    p.setPitch(0);

    var nxt = S.path[S.pi];
    var needJump = false;
    if (S.H) {
      var hn = S.H[K(nxt[0], nxt[1])];
      if (hn !== null && hn !== undefined && hn > Math.floor(p.getY()) + 0.1) needJump = true;
    }
    if (S.still > 8 && S.still < 50) needJump = true;
    AutoJS.move(1, 0, needJump, true);
    S.state = S.still > 8 ? "pushing" : "walk";
    wstat();
  });

  "walker registered"
})()
