#!/usr/bin/env python
"""End-to-end driver for the JEI Editor GUI test via AutoJS.

Steps are named; run one at a time:
    python e2e.py join          # main menu -> load the test world, creative->survival
    python e2e.py inv           # open the player inventory (JEI overlay visible)
    python e2e.py jprobe        # probe JEI: overlay displayed? draggable ingredient coords
    python e2e.py search <text> # type <text> into JEI's search box
    python e2e.py page          # hover first result and press R -> RecipesGui
    python e2e.py pageinfo      # read current page uid + slots from JEI
    python e2e.py shoot <path>  # in-game screenshot
"""
import json
import sys
import time

import gui

SHOTS = gui.SCRATCH + "/shots/"
HELPERS = open(gui.HELPERS, encoding="utf-8").read()


def ev(code, **kw):
    """Client-side eval with the gui.js helpers prepended."""
    return gui.ev("client", HELPERS + "\n" + code, **kw)


def srv(code, **kw):
    return gui.ev("server", code, **kw)


def wait_for(pred_js, timeout=180, label=""):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            v = ev(pred_js, timeout_ms=30000, quiet=True)
            if v and str(v) != "false":
                return v
        except Exception as e:
            print("  wait err:", str(e)[:90])
        time.sleep(4)
    return None


def step_join():
    ev('AutoJS.background(true); mc().options.pauseOnLostFocus = false; "ok"', quiet=True)
    w = ev('""+AutoJS.where()', quiet=True)
    print("start:", w)
    if "main menu" in w:
        print("singleplayer:", ev('clickWidget("menu.singleplayer")', timeout_ms=60000, quiet=True))
        print("screen:", ev('scr()? ""+scr().getTitle() : "-"', timeout_ms=30000, quiet=True))
        # first world-list entry, then Play
        print("pick:", ev('click(100, 66, 0)', quiet=True))
        print("play:", ev('clickWidget("selectWorld.select")', quiet=True))
        # a version/experimental confirmation may appear
        for _ in range(20):
            v = ev('JSON.stringify({w:""+AutoJS.where()})', timeout_ms=30000, quiet=True)
            if "ConfirmScreen" in v:
                print("confirm:", ev('clickWidget("gui.yes")', timeout_ms=60000, quiet=True))
            elif "playing" in v:
                break
            time.sleep(5)
    print("now:", ev('""+AutoJS.where()', timeout_ms=30000, quiet=True))
    print("gamemode:", srv('AutoJS.command("gamemode survival @a"); "sent"', quiet=True))
    print("mode:", srv('JSON.parse(AutoJS.state()).gameMode', quiet=True))


def step_inv():
    code = ('(function(){ var KM = Java.loadClass("net.minecraft.client.KeyMapping");'
            ' if (scr()) { scr().onClose(); }'
            ' KM.click(mc().options.keyInventory.getDefaultKey()); return "clicked"; })()')
    print(ev(code, quiet=True))
    time.sleep(2)
    print(ev('JSON.stringify({where:""+AutoJS.where(), ui: AutoJS.ui()})', quiet=True))


def step_jprobe():
    code = '''(function(){
      var r = jeiRuntime(); if (!r) return "no-runtime";
      var ov = r.getIngredientListOverlay();
      var out = {displayed: ov.isListDisplayed(), focus: ov.hasKeyboardFocus(), size: guiSize()};
      var hits = [];
      for (var x = 210; x <= 425; x += 5) {
        for (var y = 4; y <= 236; y += 5) {
          var st = ov.getDraggableIngredientUnderMouse(x, y);
          if (st && st.findAny().isPresent()) { hits.push(x + "," + y); }
        }
      }
      out.hits = hits.length;
      out.first = hits.slice(0, 12);
      if (hits.length) {
        var parts = hits[0].split(",");
        var st2 = ov.getIngredientUnderMouse(parseInt(parts[0],10), parseInt(parts[1],10));
        var o = st2.findFirst();
        if (o.isPresent()) {
          var ti = o.get();
          out.firstIngredient = "" + ti.getIngredient();
          try { out.firstId = "" + ti.getIngredient().getItem(); } catch(e) { out.firstIdErr = "" + e; }
        }
      }
      out.filter = "" + r.getIngredientFilter().getFilterText();
      return JSON.stringify(out);
    })()'''
    print(ev(code, timeout_ms=60000, quiet=True))


def step_search(text):
    # real typing through the ScreenEvent hook path
    code = ('(function(){ var s = scr(); if (!s) return "no-screen";'
            ' var r = jeiRuntime();'
            ' return JSON.stringify({typed: typeText(%s), filter: ""+r.getIngredientFilter().getFilterText()}); })()'
            % json.dumps(text))
    print(ev(code, timeout_ms=60000, quiet=True))


def step_page():
    code = '''(function(){
      var r = jeiRuntime();
      var ov = r.getIngredientListOverlay();
      var found = null;
      for (var x = 425; x >= 200 && !found; x -= 4) {
        for (var y = 4; y <= 236 && !found; y += 4) {
          var st = ov.getDraggableIngredientUnderMouse(x, y);
          if (st && st.findAny().isPresent()) { found = [x, y]; }
        }
      }
      if (!found) return "no-ingredient-under-any-probe";
      hover(found[0], found[1]);
      var lots = [];
      lots.push("hover@" + found);
      lots.push("keyR=" + keyDown(82, 19, 0));
      lots.push("keyRup=" + keyUp(82, 19, 0));
      return JSON.stringify({log: lots, where: ""+AutoJS.where(), screen: scr()? ""+scr().getTitle() : "-"});
    })()'''
    print(ev(code, timeout_ms=60000, quiet=True))


def step_pageinfo():
    code = '''(function(){
      var s = scr(); if (!s) return "no-screen";
      var out = {"where": ""+AutoJS.where(), "screen": ""+s.getTitle()};
      var pt = null;
      for (var x = 60; x <= 360 && !pt; x += 4) {
        for (var y = 30; y <= 210 && !pt; y += 4) {
          var o = s.getRecipeLayoutUnderMouse(x, y);
          if (o && o.isPresent()) { pt = [x, y]; }
        }
      }
      out.layoutPoint = pt;
      if (!pt) return JSON.stringify(out);
      var lw = s.getRecipeLayoutUnderMouse(pt[0], pt[1]).get();
      var rl = lw.recipeLayout();
      out.category = "" + rl.getRecipeCategory().getRecipeType().getUid();
      out.recipe = "" + rl.getRecipe();
      out.rect = "" + rl.getRect();
      var sv = rl.getRecipeSlotsView().getSlotViews();
      var slots = [];
      for (var i = 0; i < sv.size(); i++) {
        var v = sv.get(i);
        var e = {name: "" + v.getSlotName(), role: "" + v.getRole()};
        try { e.rect = "" + v.getRect(); } catch (err) { e.rect = "no-rect"; }
        try { e.item = "" + v.getDisplayedItemStack(); } catch (err) { }
        slots.push(e);
      }
      out.slots = slots;
      return JSON.stringify(out);
    })()'''
    print(ev(code, timeout_ms=60000, quiet=True))


def step_shoot(path):
    print(ev('shoot(%s)' % json.dumps(path), quiet=True))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "join":
        step_join()
    elif cmd == "inv":
        step_inv()
    elif cmd == "jprobe":
        step_jprobe()
    elif cmd == "search":
        step_search(sys.argv[2])
    elif cmd == "page":
        step_page()
    elif cmd == "pageinfo":
        step_pageinfo()
    elif cmd == "shoot":
        step_shoot(sys.argv[2])
    else:
        raise SystemExit("unknown step " + cmd)
