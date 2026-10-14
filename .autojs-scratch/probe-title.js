(function () {
  var out = {};
  var MC = Java.loadClass("net.minecraft.client.Minecraft");
  var mc = MC.getInstance();
  out.haveMc = !!mc;
  out.screenClass = "" + mc.screen;
  out.win = mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight();
  out.guiW = mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight();
  out.glfwHandle = "" + mc.getWindow().getWindow();
  out.focused = mc.isWindowActive();
  out.pauseOnLostFocus = mc.options.pauseOnLostFocus;
  out.runtime = "" + (Internal_runtime());
  function Internal_runtime() {
    try {
      var Internal = Java.loadClass("mezz.jei.common.Internal");
      return Internal.getOptionalJeiRuntime().orElse(null);
    } catch (e) { return "ERR " + e; }
  }
  var kids = mc.screen.children();
  var list = [];
  for (var i = 0; i < kids.size(); i++) {
    var k = kids.get(i);
    var o = {};
    try { o.x = k.getX(); o.y = k.getY(); o.w = k.getWidth(); o.h = k.getHeight(); } catch (e) { o.xy = "ERR"; }
    try { o.msg = "" + k.getMessage(); } catch (e) { }
    try { o.cls = "" + k.getClass(); } catch (e) { o.cls = "no-getClass"; }
    try { o.params = "" + k.getType() + "/" + k.getName(); } catch (e) { }
    list.push(o);
  }
  out.children = list;
  out.narration = (function () { try { return "" + mc.screen.getNarrationMessage(); } catch (e) { return "ERR " + e; } })();
  return JSON.stringify(out);
})()
