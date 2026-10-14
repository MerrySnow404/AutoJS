# AutoJS driving the JEI Editor dev client — launch recipe, primitives, blockers

Session 2026-10-11. Everything here was run for real against
`D:\work\JEI Editor\targets\neoforge-1.21.1` (NeoForge 21.1.238, MC 1.21.1, JEI 19.44.0.403,
68 enabled mods in `run/mods`).

## 1. What AutoJS is (verified, not assumed)

AutoJS is a **KubeJS plugin mod** (mod id `autojs`, `cc.sighs.autojs`), not a standalone process.
There is no build step or process of its own: it must be loaded **inside the game JVM**.
It opens a loopback MCP listener on the first client tick / server start.

- Transport: Streamable HTTP JSON-RPC 2.0, `POST http://127.0.0.1:25585/mcp` (stateless, no SSE,
  `GET /mcp` → 405). `GET /health` → `ok`. `GET /` → summary.
- Tools: `status`, `eval_js{code,side,timeout_ms}`, `kubejs_log`, `reload_scripts`.
- Config: `<gameDir>/config/autojs.json` (all defaults fine: port 25585, `unrestricted=true`,
  `evalTimeoutMs=5000`, `logEval=true`).
- `side=client` runs on the render thread, `side=server` on the integrated-server thread.
  Both sides exist only while a world is loaded (client side exists at the main menu too).
- KubeJS's Rhino is old: no `class`/`async`/`Promise`, `getClass()` is hidden, reflection is
  unreachable (`Class.getDeclaredMethod` is not exposed), `global.x=` is rejected, Java
  strings need `.length()`.

Failures are not always exceptions: always read `diagnostics`, and read back state to confirm.

## 2. Launch recipe (exact)

AutoJS + KubeJS must be added to the JEI Editor dev client. `run/mods` must not be touched, so the
jars are staged elsewhere and injected through a Gradle **init script**:

```bash
# staging (jars copied OUT of the two repos' run dirs; the .disabled KubeJS jar is only read)
mkdir -p D:/work/AutoJS/.autojs-scratch/jars
cp D:/work/AutoJS/targets/neoforge-1.21.1/build/libs/AutoJS-neoforge-1.21.1-1.0.0.jar \
   D:/work/AutoJS/.autojs-scratch/jars/
cp "D:/work/JEI Editor/targets/neoforge-1.21.1/run/mods/kubejs__kubejs-neoforge-2101.7.2-build.377.jar.disabled" \
   D:/work/AutoJS/.autojs-scratch/jars/kubejs-2101.7.2-build.377.jar   # FML ignores *.disabled

# launch (one instance only!)
cd "D:/work/JEI Editor/targets/neoforge-1.21.1"
JAVA_HOME='D:\program\jdk-21' ./gradlew.bat runClient --console plain \
  --init-script "D:/work/AutoJS/.autojs-scratch/jei-autojs.init.gradle"
```

`jei-autojs.init.gradle` (in this scratch dir) does, in `projectsEvaluated`:
`rootProject.dependencies.add('runtimeOnly', rootProject.files(<both jars>))`.
Verified in the log: `AutoJS 1.0.0 (autojs)` + `KubeJS 2101.7.2-build.377 (kubejs)` load, the
plugin registry reports `Found plugin source autojs`, and
`MCP endpoint listening on http://127.0.0.1:25585/mcp` appears on the render thread.
Startup to a usable endpoint: ~2.5 min (assets cached).

**Rule learned the hard way:** launch with the background task's timeout disabled, and never start a
second client while one is alive. Both instances share `run/`, the second one logs
`Could not bind the MCP endpoint ... Address already in use`, and the pair ends with the JVM dying
(`exit value -1`, no crash report).

## 3. GUI primitives (all verified)

`gui.js` (this directory) is the helper library; `gui.py` sends `gui.js + snippet` to `eval_js`.
Everything goes through the game's own input path, not around it:

| primitive | implementation | proof |
| --- | --- | --- |
| click / release | `ClientHooks.onScreenMouseClickedPre(screen,x,y,b)` → `screen.mouseClicked(...)` → `...Post` (release likewise) | moved a `silentgear:material_book` from hotbar 0 to hotbar 2 in the real player inventory; server-side `AutoJS.inventory()` confirms the swap, carried stack empty after |
| drag | `ClientHooks.onScreenMouseDragPre(...)` + `screen.mouseDragged(x,y,b,dx,dy)` | call is delivered to the current screen; **no end-to-end effect proven yet** (see gaps) |
| scroll | `onScreenMouseScrollPre(mouseHandler,screen,sx,sy)` + `screen.mouseScrolled(...)` | not exercised on a screen with an observable effect |
| type chars | `onScreenCharTypedPre` + `screen.charTyped(ch,0)` | typed `create:pressing` into the chat EditBox: `focused=true`, `getValue() === "create:pressing"` |
| key press/release | `onScreenKeyPressedPre` + `screen.keyPressed(code,scan,mods)` | keybinding click path proved via §4; screen-level key path delivered |
| global keybinds (E, T) | `KeyMapping.click(mc().options.keyX.getDefaultKey())` then the game's own `handleKeybinds()` | opened the inventory (`key.inventory`), opened chat (`key.chat`) — note `InputConstants.getKey("key.inventory")` throws, use the `KeyMapping` default key |
| screenshots | `Screenshot.grab(mc().gameDirectory, mc().getRenderTarget(), function(){})` → `run/screenshots/<ts>.png` | two shots, see `shots/` |
| widget lookup by name | `screen.children()` + `getX/getY/getWidth/getHeight/getMessage()`, match the translation key (`menu.singleplayer`, `selectWorld.create`, `gui.yes`) | used for title screen → singleplayer → create-world → join |
| cursor | `GLFW.glfwSetCursorPos(handle, sx, sy)` + `screen.mouseMoved(x,y)` | cursor moves; host-side capture still came back blank |
| JEI introspection | `Internal.getOptionalJeiRuntime()`, `RecipesGui.getRecipeLayoutUnderMouse(x,y)`, `IRecipeLayoutDrawable.getRecipeCategory()/getRecipe()/getRecipeSlotsView()` → slot `getRect()/getRole()/getSlotName()` | not usable in this instance (runtime is a dummy, §6) |

`AutoJS.background(true)` (mouse released, no screen opened) + `pauseOnLostFocus:false` in
`run/options.txt` are both required, otherwise the world stops ticking while the host works.

## 4. World join through the GUI (verified)

TitleScreen → click `menu.singleplayer` → click the first world-list row → the existing save
`新的世界` loads (no backup prompt appeared) → `/gamemode survival` (creative opens
`CreativeModeInventoryScreen`, whose `ItemPickerMenu` has no JEI overlay).
Caveats: opening `CreateWorldScreen` blocks the client thread for ~30 s (eval timeouts), world
loading blocks it for ~1-2 min, and the experimental-features `ConfirmScreen` must be answered
(`clickWidget("gui.yes")`).

## 5. The page test we could not run — and why

The intended flow (per `docs/JEI_PAGE_COMPAT_WORKFLOW.md` + `JeiRecipeEditorPlugin`):
open a JEI recipe page → right-click on the recipe layout → context menu row 0
("Editing: ON/OFF") → drag an ingredient from the JEI ingredient index onto an input slot →
right-click → "Save" → `datapacks/jeieditor-generated/data/<ns>/recipe/<path>.json` → "Reload".

Blocked at step 1 by the *environment*: **JEI's client GUI layer does not start in this instance.**

Evidence:

* `Internal.getOptionalJeiRuntime()` returns a runtime whose GUI objects are all dummies:
  `IngredientListOverlayDummy`, `RecipesGuiDummy`, `BookmarkOverlayDummy`, `IngredientFilterApiDummy`
  (the recipe manager itself works: 188 categories, and `hasClientSyncedRecipes()=true`).
* `NeoForgeGuiPlugin.getResourceReloadHandler()` → empty (GUI never started).
* The user-visible truth agrees: the in-game screenshot of the player inventory shows **no
  right-hand item index** (`shots/10-survival-inventory-no-jei-overlay.png`).
* Root cause in the log (`run/logs`, 02:18:01):

```
[ERROR] [me.je.li.lo.PluginCaller/]: Caught an error from mod plugin:
        class mezz.jei.neoforge.plugins.neoforge.NeoForgeGuiPlugin jei:neoforge_gui
java.lang.AbstractMethodError: Receiver class
  com.drowningfish233.silentgearjei.compat.jei.TraitSearchableListElementInfo
  does not define or inherit an implementation of the resolved method
  'abstract java.util.Collection getModNames...'
  at mezz.jei.gui.search.ElementPrefixParser.lambda$new$1(ElementPrefixParser.java:44)
  at mezz.jei.gui.ingredients.IngredientFilter.createElementSearch(IngredientFilter.java:130)
  at mezz.jei.gui.startup.JeiGuiStarter.start(JeiGuiStarter.java:135)
  at mezz.jei.neoforge.plugins.neoforge.NeoForgeGuiPlugin.registerRuntime(NeoForgeGuiPlugin.java:50)
```

So the third-party mod `silentgearjei` (Silent Gear JEI 1.1.9, `silentgearjei__silentgearjei-1.1.9.jar`)
registers a searchable element that does not implement JEI 19.44's `IElementInfo`/`getModNames` API.
JEI's `PluginCaller` catches the error, continues, and the runtime keeps its dummy GUI objects:
no ingredient index, no recipes screen, no JEI keybinds. Neither AutoJS nor JEI Editor is at fault,
and `run/mods` must not be edited to remove it — so this slice cannot produce the page evidence.

## 6. Problem list

**AutoJS-side gaps** (missing primitive / flakiness / timing)

1. **No GUI primitives at all.** No mouse, no keyboard, no screenshot, no widget lookup, no
   "find element by name", no wait-for-condition. All of §3 had to be built from raw
   `Java.loadClass` + `ClientHooks` calls in the snippet. This is the single biggest gap.
2. **No click helper that matches what the game does.** `MouseHandler.onPress/onMove` are private,
   so a caller must know to re-fire `ClientHooks.onScreenMouseClickedPre/Post` + `screen.mouseClicked`
   itself. Without that, JEI/any `ScreenEvent` listener never sees the click.
3. **No drag primitive.** `mouseDragged` can be synthesized, but `ScreenEvent.MouseDragged.Pre`
   callers only react on real button state per tick, so dragging that depends on
   `MouseHandler.isLeftPressed()` (JEI's per-tick drag update) has no supported path.
4. **Global keybinds need a workaround.** `screen.keyPressed` only reaches an open screen; pressing
   E/T/F2 with no screen open needs `KeyMapping.click(options.keyX.getDefaultKey())` plus a tick.
   `InputConstants.getKey("key.inventory")` throws (`Unknown key name`); the name must be
   `key.keyboard.e`.
5. **Rhino cannot disambiguate Java overloads.** `NativeImage.writeToFile(Path)` vs `writeToFile(File)`
   fails with "choice of Java method ... is ambiguous" for **both** a `File` and a `Path` object, so the
   obvious screenshot API is unusable; `Screenshot.grab(File, RenderTarget, Consumer)` works only
   because a JS function does convert to `Consumer`.
6. **No read access to package-private editor state.** `ClientEditorState.isEditing()`,
   `getPendingPatches()`, `getLastDrop()`, `JeiRecipeEditorPlugin.hasEditableInputAtMouse()` are all
   package-private, `Class.getDeclaredMethod` is not exposed, so a driver cannot check "am I in edit
   mode?" / "did the drag register a patch?" without reading pixels or waiting for the file.
7. **`logEval=true` floods the log.** Every eval is echoed line-by-line into `logs/latest.log`
   (my helper file is ~170 lines per call: >14k log lines in 17 min). Real mod logs (the
   `AbstractMethodError` above) get buried. Recommend `logEval=false` for driven sessions.
8. **Timing.** `evalTimeoutMs` defaults to 5000 ms; opening `CreateWorldScreen` and loading a world
   block the client thread for 30 s - 2 min, so consecutive evals time out unless `timeout_ms` is
   raised per call (and the caller must be ready to retry).
9. **No screenshot that survives an unfocused window** (see environment #5) and no way to know the
   client is gone: after the JVM died, calls just got `ConnectionRefusedError`.

**JEI-Editor-side observations** (nothing broken; friction for scripted tests)

10. **The context menu needs a press/release pair.** `ClientEditorEvents.onMouseButton` only acts
    when `ClientEditorState.consumeActionClick()` is armed, and arming happens on the **release** of
    a left click. A driver must click a menu row twice (or click once, release, then click).
11. **The menu is drawn, not a widget.** Rows are computed from `mouseX/mouseY`
    (`x = clamp(mouseX,2,width-150)`, `y = clamp(mouseY,2,height-height-2)`, row *i* at
    `x+1 .. x+147`, `y+1+17i .. y+18+17i`); there is no widget to look up, so a driver must
    replicate the arithmetic (or right-click at a known point and offset from it).
12. **Everything is NeoForge `ScreenEvent`-driven**, which is good news: synthetic clicks/keyboard
    delivered through the hooks in §3 reach the editor exactly like real input.
13. **No public state accessors** (same as AutoJS #6): the editor only publishes
    `applyResult(...)`. A scripted harness would benefit from a small public read-only accessor
    (editing on/off, pending patch count, last status text).

**Environment problems**

14. **`silentgearjei` breaks JEI's GUI in this pack** (§5) - the blocker for this slice. The repo's own
    harnesses never touch JEI's GUI layer, so they pass while the client a user sees has no JEI item
    index or recipe screen.
15. **Host-side screenshots are blank.** `screenshot_app` (PrintWindow) on the GLFW window returns a
    white image; the in-game `Screenshot.grab` path is the only usable one.
16. **The dev client JVM dies after ~17 min with `exit value -1` and no crash report** (twice today:
    once right after `GLFW.glfwFocusWindow`, once after closing a chat screen). Two clients sharing
    `run/` definitely kills one of them (`BindException` on 25585); the single-instance death is
    unexplained.
17. **`run/options.txt` needs `pauseOnLostFocus:false`** (edited; `run/` is gitignored) - otherwise
    every host-side eval pauses the integrated server.
18. **JEI config lives in `run/config/jei/jei-client.ini`** (INI, not TOML) - `[lookupHistory]
    enabled=false` is the only disabled section, the ingredient list itself is enabled by default.

## 7. Next slice, in order

1. Get a JEI GUI that starts: run the same recipe with `silentgearjei` absent from the classpath
   (temporarily rename it in `run/mods` **only with explicit permission**, or better, a separate
   staging copy of the pack) and confirm `NeoForgeGuiPlugin.getResourceReloadHandler()` is present and
   the item index is visible in a screenshot.
2. Then drive: inventory (or JEI's own index) → hover an item → press R (82/19) →
   `RecipesGui.getRecipeLayoutUnderMouse` to read the category uid + input slot rect (this is how the
   driver should aim, not by guessing) → right-click → menu row 0 x2 → press on the index ingredient →
   `mouseDragged` steps → release on the input slot → right-click → "Save" (row 1) → verify
   `run/saves/<world>/datapacks/jeieditor-generated/data/<ns>/recipe/<path>.json` → "Reload" (row 2).
3. Only `create:pressing`/`mekanism:crushing` once step 2 is green; then reuse for the other 67 pages.

## 8. Files in this scratch dir

- `jei-autojs.init.gradle` - the injection init script (launch recipe).
- `jars/` - staged AutoJS + KubeJS jars.
- `gui.js`, `gui.py` - primitive library + MCP driver (`python gui.py client -e '<js>'`).
- `e2e.py` - the step driver (join / inv / jprobe / search / page / pageinfo / shoot).
- `probe-title.js`, `step-createworld.js` - one-off probes.
- `logs/runClient.log` - the measured client log (17 min, includes the JEI GUI failure).
- `shots/` - in-game screenshots (10/11 = JEI overlay absent, creative inventory).
- `ajs.py`, `mc.py`, `walker.js`, `goal.json`, `status.json` - an earlier session's files, untouched.
