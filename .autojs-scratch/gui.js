// AutoJS GUI driver helpers (client side). Prepended to every snippet by gui.py.
// Everything here goes through the game's own input path: the NeoForge ScreenEvent
// hooks plus the Screen methods they normally wrap, so JEI and JEI Editor see the
// same events a real mouse/keyboard would produce. The real cursor is also moved
// so per-tick polling (JEI hover highlight, tooltips) agrees.
var MC = Java.loadClass("net.minecraft.client.Minecraft");
var GLFW = Java.loadClass("org.lwjgl.glfw.GLFW");
var Hooks = Java.loadClass("net.neoforged.neoforge.client.ClientHooks");
var EditorState = Java.loadClass("cc.sighs.JEIEditor.client.ClientEditorState");

function mc() { return MC.getInstance(); }
function scr() { return mc().screen; }
function guiSize() {
  var w = mc().getWindow();
  return [w.getGuiScaledWidth(), w.getGuiScaledHeight(), w.getScreenWidth(), w.getScreenHeight()];
}
function cursorTo(gx, gy) {
  var w = mc().getWindow();
  var kx = w.getScreenWidth() / w.getGuiScaledWidth();
  var ky = w.getScreenHeight() / w.getGuiScaledHeight();
  GLFW.glfwSetCursorPos(w.getWindow(), gx * kx, gy * ky);
  return [gx, gy];
}
function hover(gx, gy) {
  cursorTo(gx, gy);
  var s = scr();
  if (s) { s.mouseMoved(gx, gy); }
  return gx + "," + gy;
}
function press(gx, gy, button) {
  var s = scr();
  if (!s) { return "no-screen"; }
  if (Hooks.onScreenMouseClickedPre(s, gx, gy, button)) { return "canceled-pre"; }
  var r = s.mouseClicked(gx, gy, button);
  return "" + Hooks.onScreenMouseClickedPost(s, gx, gy, button, r);
}
function release(gx, gy, button) {
  var s = scr();
  if (!s) { return "no-screen"; }
  if (Hooks.onScreenMouseReleasedPre(s, gx, gy, button)) { return "canceled-pre"; }
  var r = s.mouseReleased(gx, gy, button);
  Hooks.onScreenMouseReleasedPost(s, gx, gy, button, r);
  return "" + r;
}
function click(gx, gy, button) {
  var a = press(gx, gy, button);
  var b = release(gx, gy, button);
  return a + "/" + b;
}
function dragMove(gx, gy, button, dx, dy) {
  var s = scr();
  if (!s) { return "no-screen"; }
  cursorTo(gx, gy);
  if (Hooks.onScreenMouseDragPre(s, gx, gy, button, dx, dy)) { return "canceled-pre"; }
  var r = s.mouseDragged(gx, gy, button, dx, dy);
  Hooks.onScreenMouseDragPost(s, gx, gy, button, dx, dy);
  return "dragged:" + dx + "," + dy;
}
function scrollAt(gx, gy, sx, sy) {
  var s = scr();
  if (!s) { return "no-screen"; }
  cursorTo(gx, gy);
  if (Hooks.onScreenMouseScrollPre(mc().mouseHandler, s, sx, sy)) { return "canceled-pre"; }
  var r = s.mouseScrolled(gx, gy, sx, sy);
  Hooks.onScreenMouseScrollPost(mc().mouseHandler, s, sx, sy);
  return "" + r;
}
function keyDown(keyCode, scan, mods) {
  var s = scr();
  if (!s) { return "no-screen"; }
  mods = mods || 0;
  if (Hooks.onScreenKeyPressedPre(s, keyCode, scan, mods)) { return "canceled-pre"; }
  var r = s.keyPressed(keyCode, scan, mods);
  Hooks.onScreenKeyPressedPost(s, keyCode, scan, mods);
  return "" + r;
}
function keyUp(keyCode, scan, mods) {
  var s = scr();
  if (!s) { return "no-screen"; }
  mods = mods || 0;
  if (Hooks.onScreenKeyReleasedPre(s, keyCode, scan, mods)) { return "canceled-pre"; }
  var r = s.keyReleased(keyCode, scan, mods);
  Hooks.onScreenKeyReleasedPost(s, keyCode, scan, mods);
  return "" + r;
}
function typeText(text) {
  var s = scr();
  if (!s) { return "no-screen"; }
  var got = "";
  for (var i = 0; i < text.length; i++) {
    var ch = text.substring(i, i + 1);
    var code = text.charCodeAt(i);
    if (!Hooks.onScreenCharTypedPre(s, code, 0)) {
      s.charTyped(ch, 0);
      Hooks.onScreenCharTypedPost(s, code, 0);
      got += ch;
    } else { got += "[" + ch + "=pre-canceled]"; }
  }
  return got;
}
// Widget listing: no getClass() in KubeJS Rhino, so identify by duck typing.
function widgets() {
  var s = scr();
  if (!s) { return []; }
  var kids = s.children();
  var out = [];
  for (var i = 0; i < kids.size(); i++) {
    var k = kids.get(i);
    var o = { i: i };
    try {
      o.x = k.getX(); o.y = k.getY(); o.w = k.getWidth(); o.h = k.getHeight();
    } catch (e) { o.box = "no-box"; }
    try { o.msg = "" + k.getMessage(); } catch (e) { }
    try { o.narr = "" + k.getNarrationMessage(); } catch (e) { }
    try { o.visible = k.visible; o.active = k.active; } catch (e) { }
    out.push(o);
  }
  return out;
}
function findWidget(text) {
  var ws = widgets();
  for (var i = 0; i < ws.length; i++) {
    var w = ws[i];
    if (w.msg && w.msg.indexOf(text) >= 0) { return w; }
  }
  return null;
}
function clickWidget(text) {
  var w = findWidget(text);
  if (!w) { return "not-found:" + text; }
  var cx = Math.floor(w.x + w.w / 2), cy = Math.floor(w.y + w.h / 2);
  return click(cx, cy, 0) + " @" + cx + "," + cy;
}
// In-game framebuffer capture: host-side window capture returns a blank image for
// this GLFW/OpenGL window, so screenshots have to come from the game itself.
var Screenshot = Java.loadClass("net.minecraft.client.Screenshot");
// NativeImage.writeToFile(Path|File) is ambiguous for Rhino, so use Screenshot.grab
// and let the game write into <gameDir>/screenshots/<timestamp>.png.
function shoot(tag) {
  try {
    Screenshot.grab(mc().gameDirectory, mc().getMainRenderTarget(), function () { });
    return "grab-called:" + tag;
  } catch (e) {
    return "ERR " + e;
  }
}
function jeiRuntime() {
  try {
    var Internal = Java.loadClass("mezz.jei.common.Internal");
    return Internal.getOptionalJeiRuntime().orElse(null);
  } catch (e) { return null; }
}
function gameState() {
  var s = scr();
  return {
    where: "" + AutoJS.where(),
    screenTitle: s ? "" + s.getTitle() : "-",
    editing: "" + EditorState.isEditing(),
    pending: "" + EditorState.getPendingPatches().size(),
    lastDrop: "" + EditorState.getLastDrop(),
    jei: jeiRuntime() ? "yes" : "no"
  };
}
