(function () {
  var log = [];
  function step(name, value) { log.push(name + "=" + value); }
  var s = scr();
  step("where", "" + AutoJS.where());
  // 1. Singleplayer
  step("singleplayer", clickWidget("menu.singleplayer"));
  step("screen1", scr() ? "" + scr().getTitle() : "-");
  // 2. Create New World
  step("create", clickWidget("selectWorld.create"));
  var cws = scr();
  step("screen2", cws ? "" + cws.getTitle() : "-");
  // 3. name the world deterministically through the name field
  var name = null;
  var ws = widgets();
  for (var i = 0; i < ws.length; i++) {
    if (ws[i].msg && ws[i].msg.indexOf("selectWorld.enterName") >= 0 && ws[i].w > 100) { name = ws[i]; break; }
  }
  if (name) {
    var kids = cws.children();
    var box = kids.get(name.i);
    try { box.setValue("ajs-e2e"); step("nameSet", "" + box.getValue()); }
    catch (e) { step("nameSetErr", e); }
  } else { step("nameField", "not-found"); }
  return JSON.stringify({ log: log, widgets: ws });
})()
