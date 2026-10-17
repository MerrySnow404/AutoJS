---
name: autojs
description: Test Minecraft mods on a live game through the AutoJS MCP endpoint. Use it to evaluate JavaScript on the client or server side of a running Minecraft game (NeoForge, Forge or Fabric; KubeJS when it is installed, AutoJS's own Rhino kernel when it is not), inspect and mutate any state, place blocks, summon entities, drive GUIs, input and the player, hot-reload scripts, read their errors, and turn any of it into a repeatable assertion. No human interaction is needed — it reads any data and simulates any player action while the game keeps running in the background.
---

# AutoJS

把运行中的游戏的脚本上下文暴露成本地 MCP 端点：**一条通向活着的游戏的口子，里面跑什么脚本由你决定**。这里的上下文是 KubeJS 的脚本上下文（装了 KubeJS），或 AutoJS 自带的 Rhino 内核（没装）——两种模式能力不同，见 README 的「两种模式：KubeJS 可选」。

用途是**测模组**，工作方式是**高频迭代**：写一小段脚本 → 跑 → 看返回值/日志 → 改 → 再跑。没有构建、没有重启、秒级往返，所以可以一条一条假设地试，而不是一次写一大段赌它对。这也是它区别于"写个调试命令 / 加临时日志 / 重启游戏"的地方：**试错成本低到可以随便试**。

**能力边界先说清楚：几乎任何数据都读得到，几乎任何操作都能模拟，全程不需要人。** 你拿到的是游戏自己的脚本上下文，不是一个受限的调试接口：

- **读**：任何对象、字段、NBT、注册表条目、屏幕与菜单结构（`AutoJS.ui()` 含 `menu.slots` 的槽位数与类型）、背包内容（`AutoJS.inventory()`，打开的容器用 Java 读 `player.containerMenu`）、实体、区块数据、游戏目录下的文件与日志。`eval_js` 返回的是真实 Java 对象，`Object.keys`/`JSON.stringify`/直接调方法都行；`AutoJS.*` 只是把这些查法包了一层。
- **写与模拟**：放置/破坏方块、召唤/移动/击杀实体、开关界面、点按钮、敲键、移动鼠标、驱动玩家移动、改时间天气、执行命令、发包与收包——走的是游戏自己的输入与命令路径（客户端走真实的 `Input`/`MouseHandler`/`Screen` 路径，服务端走 `ServerPlayerGameMode` 与命令源），所以被测 mod 看到的就是"真玩家在做这件事"，而不是绕过它的旁路。
- **不需要人工介入**：`AutoJS.background(true)` 把鼠标放出去但世界照常 tick，脚本可以一直跑；事件处理器能在运行中注册，"注入观察点 → 触发 → 读结果"不用改文件再重载；`reload_scripts` 让脚本文件改动即时生效；MCP 端点是本地 HTTP，调用方可以是任何自动化程序。**没有需要人去点、去看、去确认的步骤**——不要因为"这得有人操作一下"而停下。
- **权限**：默认 `unrestricted=true`，KubeJS 的类过滤、事件注册守卫、路径限制全部拆掉，`Runtime`、`ProcessBuilder`、游戏目录外的任意路径都够得到（这也是端点只绑 loopback 的原因）。

只有两类例外，别把"做不到"误判成这两类之外的东西：运行时**不能新建注册表条目**（物品/方块/流体在启动时冻结，测新内容要重启游戏）；个别 KubeJS 世代能力有差异（见第 2 节的「世代差异」一段）。除此之外，遇到"读不到/做不了"，先假设是脚本没写对。

所以**不要被下面任何一层绑住**：

- 内置 `AutoJS.*`（第 3 节）是**可选封装**——省掉坐标、注册表查找、掉落拾取之类的样板，不用它直接写 Java 也一样能碰到任何东西；
- KubeJS 事件是**可选驱动方式**——需要"随时间观察/随事件触发"时用它最省事，需要一次性求值时完全不必（Rhino 模式下没有事件系统，退回自己轮询）；
- 第 4 节的复杂案例只是**几个已验证的拼法**，照抄可以，但不是必走路径——真正的写法按你要测的东西来。

反过来，三层都用上通常最省事：用 `eval_js` 跑一段 Java 拿到对象，用 KubeJS 事件观察后续，用 `AutoJS.*` 收拾样板活。

两端（服务端 / 客户端）各自可调，一个 JVM 一个端点。

## 1. 框架原理

**结构**：`脚本上下文（KubeJS 或 AutoJS 自带的 Rhino）→ AutoJS（MCP 端点）→ 你`。

- 传输：Streamable HTTP，`POST http://127.0.0.1:25585/mcp`（JSON-RPC 2.0）。无状态，不提供服务端 SSE 流，所以 `GET /mcp` 返回 405。`GET /health` 探活，`GET /` 给摘要。
- 管线：HTTP 线程收到 `tools/call` → 把工作投给对应端的**主线程**（服务器线程 / 渲染线程）→ 主线程执行 JS → 结果回到 HTTP 线程。所以脚本永远在正确的线程上跑，你不需要自己排队。
- 沙箱：`unrestricted` 默认为 `true`，即 KubeJS 的类过滤、事件注册守卫、路径限制**全部拆掉**——`eval_js` 里能做这个 JVM 能做的任何事（加载任意类、读写任意路径、运行期注册事件处理器）。这是"测模组"需要的权限，也是它必须只绑 loopback 的原因。
- 配置：游戏目录 `config/autojs.json`（`enabled` / `bindAddress` / `port` / `token` / `unrestricted` / `evalTimeoutMs`）。填了 `token` 就要带 `Authorization: Bearer <token>`；非本地的 `Origin` 头一律拒绝。**端口可在启动期覆盖**：`-Dautojs.port=` 优先于 `AUTOJS_PORT` 环境变量，二者优先于配置文件；`0` 让系统给个空闲端口，实际值由 `status` 报出——同一台机器并跑多个实例时用它，免得撞端口。
- 生命周期：监听器在每个 side 就绪时开启（服务端：`ServerStartedEvent`；客户端：第一个 tick），最后一个 side 消失时关闭（客户端退出世界不关监听器，只下线 server side）。

**两端模型**（这是最容易搞错的一点）：

| side | 何时存在 | 执行线程 | 用途 |
| --- | --- | --- | --- |
| `server` | 专用服务器启动后；客户端加载了世界（集成服务器）期间 | 服务器主线程 | **改状态、看权威数据**：方块、实体、背包、NBT、命令 |
| `client` | 仅客户端 | 渲染线程 | 看客户端视角：界面、FPS、资源；**驱动玩家移动** |

两端是两套独立的脚本上下文：全局变量互不相通，服务端的绑定（如 `Client`）在客户端以外是 `undefined`。`side=auto` 优先 server，不可用时退回 client。

## 2. 用法

**先调 `status`**，确认哪一端活着、端点与限额，再动手。

**接管测试前先开后台调试模式**：客户端侧 `AutoJS.background(true)`。它直接把鼠标放出去——不用开任何界面，所以既不会暂停世界（1.21.1 里暂停只由 pause 界面触发），也不会吞掉脚本正在驱动的输入。开了之后可以放心切到别的窗口干活、让流程在后台跑；点一下游戏窗口会重新抓住鼠标，或用 `AutoJS.background(false)` 主动拿回来。

MCP 层就 4 个工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `status` | — | 哪些 side 可用、端点、限额 |
| `eval_js` | `code`、`side`、`timeout_ms` | 执行 JS，返回最后一个表达式的值 |
| `kubejs_log` | `side`、`kind`(`errors`\|`warnings`\|`all`)、`limit` | 读该端 KubeJS 控制台；`all` 含脚本自己的 `console.info` |
| `reload_scripts` | `side`、`timeout_ms` | 重跑该端脚本（等价 `/kubejs reload <side>-scripts`） |

裸 HTTP 调用长这样：

```bash
curl -s -X POST http://127.0.0.1:25585/mcp -H 'Content-Type: application/json' \
  --data-raw '{"jsonrpc":"2.0","id":1,"method":"tools/call",
               "params":{"name":"eval_js","arguments":{"side":"server","code":"AutoJS.where()"}}}'
```

**返回值形状**（成功）：

```json
{"ok":true,"side":"server","elapsedMs":6,"type":"string","value":"in world minecraft:overworld at 3,70,-9, no container open"}
```

失败是 `ok:false` + `error` + `stack`（MCP 层同时置 `isError`）。

**必须看 `diagnostics`**：它是本次调用期间该端产生的日志行。KubeJS 有些失败不抛异常、只写日志，`ok:true` 不代表没出事：

```json
{"ok":true,"value":"42",
 "diagnostics":["[20:16:25] [ERROR] ! autojs-eval#1: Event handler 'ServerEvents.recipes' can only be registered during script loading!"]}
```

**两种写法，选对差很多**：

- **一次性查询**：`eval_js` 里写表达式，拿回值。适合"现在是什么状态"。
- **注入流程**：`eval_js` 里注册 `ServerEvents.tick(...)` / `EntityEvents.beforeHurt(...)` 等 KubeJS 事件，让游戏自己按 tick 调用。**这比反复 eval 快得多**，也是观察"随时间变化的行为"的正确方式。跑完的处理器会一直留着：用计数器自己停手，或 `reload_scripts` 一次清光。

关掉端点前不用特别处理；要重来一遍干净环境就 `reload_scripts`。

### 三层杠杆，任选（别被任何一层绑住）

`eval_js` 的本质是"往活着的游戏里执行任意脚本"。可用的是三层，用哪层由你要测什么决定：

1. **直接写 Java / 原生 API**：`Java.loadClass("...")`、`player.getInventory()`、`level.getBlockState(pos)`…… 最底层，什么都能碰。AutoJS 默认已拆掉类过滤与路径限制，所以这层是完整可用的。签名不确定就查本地源码 jar（见第 5 节），别猜。
2. **AutoJS 的封装**（第 3 节）：省掉坐标、注册表查找、掉落拾取、发包之类的样板。它只是让你少写几行，缺什么方法都没关系——绕过去写 Java 即可。
3. **KubeJS 自带的事件与预注册工具类**：通常是最省试错的一层，见下。

**世代差异（同一个 `eval_js`，不同 KubeJS 世代里脚本侧的东西不一样）**：下面列的是 KubeJS 2101 一代的形状。已实测的差异——1.18.2（1802 世代）**没有 `Java` 全局**，取类要用小写 `java('fqcn')`，事件用老式 `onEvent('...')`（没有 `ServerEvents`/`PlayerEvents`/`BlockEvents` 这些命名空间），日志文件是 `logs/kubejs/<side>.txt`；那一代还有 `try/catch` 直接抛 `IllegalArgumentException` 的缺陷。`tags` 与 `nbt` 的命名空间/形状也随版本变（例如 `forge:rods` vs `c:rods`、`{Count:2b,…}` vs `{count:2,…}`）。先 `AutoJS.help()` 与实测探一下，别照抄另一代的写法。

### KubeJS 自带的东西，优先考虑（省大量试错）

它们已经现成地放在每个脚本上下文里，**不用 import、不用查某个版本的 Java 签名**：

- **预注册全局**（直接用）：`Item`/`Items`、`Block`/`Blocks`、`Text`、`NBT`、`JsonIO`、`Registry`、`Ingredient`、`SizedIngredient`、`BlockStatePredicate`、`Utils`、`Platform`、`console`、`global`、`DamageSource`、`BlockPos`/`Vec3d`/`Direction`、`Fluid`、`Notification`、`ParticleOptions`…客户端另有 `Client`、`GLFWInput`。
- **事件组**：`ServerEvents`（tick / tags / recipes / commandRegistry / generateData…）、`PlayerEvents`（loggedIn / chat / advancement / inventoryChanged / tick…）、`BlockEvents`（rightClicked / placed / broken / drops / blockEntityTick…）、`ItemEvents`（rightClicked / crafted / smelted / foodEaten…）、`EntityEvents`（beforeHurt / afterHurt / death / spawned / drops…）、`LevelEvents`（tick / beforeExplosion / afterExplosion…）、`NetworkEvents`（dataReceived）、`RecipeViewerEvents`、`ClientEvents`、`StartupEvents`、`KeyBindEvents`。

**为什么它们省试错**：

- 它们挂在**游戏真正的派发路径**上，所以经它们观察到/触发的就是 mod 实际会遇到的那条路径，而不是绕过它 —— 测出来的结论更可信。
- 事件对象已经把参数解好了（`event.entity`、`event.block`、`event.item`、`event.player`…，具体字段看 KubeJS 对应的 wrapper），不用你猜签名。
- 线程与"哪一端"的问题它们已经处理好了。

**AutoJS 拆掉了 KubeJS "只能在加载期注册事件"那道守卫**，所以这些处理器可以在运行中注册，包括直接从 `eval_js` 注册。于是"注入一个观察点 → 触发 → 读结果"是秒级的，不用改脚本文件再重载。实测过的例子：`ServerEvents.tick` 注入一个计数器（3 秒 64 次）、`EntityEvents.beforeHurt` 捕获受伤（用 `/damage` 触发）。

## 3. 内置 API 列表

每个 KubeJS 脚本上下文都绑定了 `AutoJS` 全局，`eval_js` 和脚本文件用的是同一个对象。`AutoJS.help()` 打印完整清单。

**这些是可选封装，不是必须的入口。** 缺哪个方法、或者不想用，就照第 2 节直接写 Java；`AutoJS.*` 只是把常见的样板活替你写了。

约定：坐标一律三个数字（另有接受 `BlockPos` 的重载）；名字可以是全 id 也可以是片段，**有歧义时报错并列出候选**；改世界的方法需要服务端（客户端改的是服务端不认的幽灵状态），`move` 反过来只在客户端有效。

**定位**

| 方法 | 作用 |
| --- | --- |
| `where()` | 人话回答现在在哪：主菜单 / 加载中 / 世界里某坐标 / 暂停 / 死亡 / 某个界面（含容器类型） |
| `ui()` | 上面那句话的 JSON：`{where, screen:{class,title,mod…}, menu:{kind,class,slots,mod}}` |
| `snapshot()` | 一句话定位：side/dist/where/tick/维度/时间/天气/玩家 |
| `menu()` | 当前打开的容器菜单（两端都有） |
| `side()` `mods()` `modVersion(id)` `help()` | 环境信息 |

**区域**

| 方法 | 作用 |
| --- | --- |
| `area(半径?)` / `area(中心, 半径, 选项?)` / `area(x,y,z,半径)` | 区域体检：`{dimension, center, box, cost, height, biomes, surface, blocks, blockEntities, entities, structures, notes}`。选项 `{vertical, top, entities, volume, structures}`。**只读已加载区块**，大区域自动降采样并把降采样写进 `cost`/`notes` |

**玩家状态**

| 方法 | 作用 |
| --- | --- |
| `state()` | 血量/饱食/氧气/XP/模式/维度/坐标/**朝向**/水中/着火/摔落/效果/手持/护甲。朝向只能从这里读 |
| `look(距离?)` | 准星指着什么：`{type:"block"\|"entity"\|"miss", …}` |
| `view(距离)` / `view(角度, 距离, 条数)` | **结构化视锥**：锥内实体带 `health/maxHealth/hostile/targetingMe/lineOfSight` |

**感知（都只读已加载区块，不加载/生成地形）**

| 方法 | 作用 |
| --- | --- |
| `findBlocks(名字, 半径, 条数)` | 世界里"这东西在哪"，最近优先，返回 `[{block,pos,distance}]` |
| `nearestBlock(名字, 半径)` | 最近的一处，或 null |
| `blockInfo(pos)` / `blockInfo(x,y,z)` | 方块 id/属性/硬度/发光/是否空气/是否挡路/是否液体/生物群系/上下方方块/方块实体类型 |
| `distanceTo(实体\|pos\|{x,y,z})` | 距离 |

**动作（走游戏自己的逻辑，算正常玩法）**

| 方法 | 作用 |
| --- | --- |
| `lookAt(目标)` | 转向目标（服务端会发包同步给客户端） |
| `move(前进, 平移, 跳, 疾跑)` | **客户端**：接管键盘输入并按住对应方向；值会保持，松手用 `move(0,0,false,false)` |
| `crouch(on)` | 潜行 |
| `mine(pos)` / `mine(x,y,z)` | 破坏方块（`ServerPlayerGameMode.destroyBlock`：掉落、工具、耐久、发包全套）。**掉落物留在地上** |
| `collect(半径?)` | 捡起地上的掉落物（原版拾取规则，含 10 tick 拾取延迟） |
| `attack(实体)` | 攻击；**受攻击冷却限制**，没充能时返回 false |
| `use()` | 右键：吃、喝、射、扔、开门、使用机器 |
| `place(pos, 面)` / `place(x,y,z,面)` | 右键某个面：放下手持方块、播种、开箱子、用机器。面取 `up/down/north/south/east/west` |
| `background(on)` | **客户端**：后台调试模式。`true` 放开鼠标（只关掉暂停界面，其它界面不动）、`false` 重新抓住。见 §2 |

**合成**

| 方法 | 作用 |
| --- | --- |
| `craftable(条数)` | 按真实配方算出背包现在能合成什么，含消耗明细 |
| `craft(配方id, 次数)` | 合成（校验材料、扣材料、给产物）。省略的只是"把物品挪进格子"这步 UI |

**注册表查询**

| 方法 | 作用 |
| --- | --- |
| `item(id\|片段)` `block(...)` `entityType(...)` | 查注册表，歧义时报候选 |
| `id(任意对象)` | → 注册表 id（物品/方块/实体/方块状态/手持物） |
| `tags(物品\|方块)` | → 标签 id 列表 |
| `findItems(片段, 条数)` `findBlocks(片段, 条数)` `findEntities(片段, 条数)` | 按名字搜注册表 |

**读写世界**

| 方法 | 作用 |
| --- | --- |
| `server()` `level()` `player()` `players()` | 拿对象（`server()` 在纯客户端为 null） |
| `pos(x,y,z)` `posOf(东西)` | 造/取 BlockPos |
| `blockAt(pos)` `blockEntity(pos)` | 读方块状态 / 方块实体 |
| `nbt(方块实体\|物品\|Tag)` | 方块实体与物品的 NBT（SNBT 文本），例如 `{Items:[],id:"minecraft:chest",x:0,y:101,z:0}` |
| `entitiesNear(半径)` `inventory(玩家?)` `held(玩家?)` | 附近实体 / 非空物品栏 / 手持 |
| `command(命令)` | 以服务端身份执行原版命令（任何 mod 的命令都能下） |
| `give(物品, 数量)` `setBlock(pos, 方块)` `summon(类型, x,y,z)` | 常用操作 |
| `setTime(ticks)` `setWeather(clear\|rain\|thunder, ticks)` `heal(玩家)` `tp(实体, x,y,z)` | 环境与调试手段 |

**文件与其它**

| 方法 | 作用 |
| --- | --- |
| `read/write/append/exists/readJson/writeJson` | 相对路径按游戏目录解析，绝对路径可用 |
| `stack(物品, 数量)` `log(...)` | 造物品堆 / 写 KubeJS 控制台 |

## 4. 复杂调试案例（`AutoJS.*` 不够用时）

`AutoJS.*` 只覆盖"能放下、能打开、NBT 有东西"。真正的 bug 几乎都在封装够不到的地方：mod 自己的类、自己的数据、自己的 GUI 事件。下面每个案例都用 `Java.loadClass` 直接碰这些，**都已实测**（除注明外 `side=server`），把 `com.example` 换成你的 mod 即可。

最短的循环，够用就别往下翻：

```js
AutoJS.setBlock(0,100,0,"yourmod:your_block"); AutoJS.blockInfo(0,100,0)
AutoJS.place(0,100,0,"up"); AutoJS.nbt(AutoJS.blockEntity(0,100,0))
AutoJS.mine(0,100,0); JSON.parse(AutoJS.collect(6))
```

### C1 读方块实体**实际类**的内部状态（不用反射、不猜 NBT）

`AutoJS.blockEntity()` 给的是真实对象，成员按**运行时类**解析——mod 的方块实体自己的 getter 直接能调：

```js
var be = AutoJS.blockEntity(0, 100, 0);
be.getOpenNess(0.5)          // 实测：ChestBlockEntity 的方法，BlockEntity 上没有
be.getProgress()             // 机器类同理；公开字段同样按运行时类解析，如 be.energyStored
```

比猜 NBT 结构可靠。只有它不公开时才退回 `AutoJS.nbt(be)`。

### C2 手工构造一次"玩家右键"（指定手、面、命中点）

`AutoJS.place()` 只按面中心点；mod 若看命中偏移、手、潜行，就得自己造 `BlockHitResult`：

```js
var Vec3 = Java.loadClass("net.minecraft.world.phys.Vec3");
var Hit  = Java.loadClass("net.minecraft.world.phys.BlockHitResult");
var Dir  = Java.loadClass("net.minecraft.core.Direction");
var Hand = Java.loadClass("net.minecraft.world.InteractionHand");

var p   = AutoJS.player();                 // 需要在线玩家；纯服务端没人时它是 null
var pos = AutoJS.pos(0, 101, 0);
var hit = new Hit(Vec3.atCenterOf(pos).add(0, 0.5, 0), Dir.UP, pos, false);
p.gameMode.useItemOn(p, p.level(), AutoJS.held(p), Hand.MAIN_HAND, hit).consumesAction()
```

参数顺序与 `PlayerOps` 里实际用的是同一套（`useItemOn(player, level, stack, hand, hit)`；`useItem` 少一个 `hit`）。

### C3 手工造物品组件（1.21 的 DataComponents）

```js
var DC   = Java.loadClass("net.minecraft.core.component.DataComponents");
var Comp = Java.loadClass("net.minecraft.network.chat.Component");
var Hand = Java.loadClass("net.minecraft.world.InteractionHand");

var s = AutoJS.stack("minecraft:diamond_sword", 1);
s.set(DC.CUSTOM_NAME, Comp.literal("Probe Blade"));
s.getHoverName().getString()               // → "Probe Blade"（实测）
AutoJS.player().setItemInHand(Hand.MAIN_HAND, s)
```

拿在手里之后，mod 的名称/tooltip/组件读取路径就都能测；`JSON.parse(AutoJS.state()).held` 也能确认。

### C4 遍历注册表：这个 mod 到底注册了什么

```js
var Reg = Java.loadClass("net.minecraft.core.registries.BuiltInRegistries");
var it  = Reg.BLOCK.keySet().iterator(); var out = [];
while (it.hasNext()) {
  var id = it.next();
  if (String(id.getNamespace()) === "yourmod") out.push(String(id));
}
out.length + " :: " + out.slice(0, 5).join(", ")
```

实测在 1.21.1 上能枚举出 1060 个 `minecraft:` 方块。`ITEM`/`ENTITY_TYPE`/`BLOCK_ENTITY_TYPE`/`MOB_EFFECT` 同法；`Reg.X.getKey(obj)` 反向拿 id。比 `findItems("yourmod")` 全，而且拿到 id 就能顺着去 `Java.loadClass` 对应类。

### C5 用**真实输入路径**点界面（测 GUI 的必杀）

别直接调按钮的 `onPress()`——那会绕过 `ScreenEvent`，很多 mod 的监听器根本看不到。走游戏的点击路径（`side=client`）：

```js
var CH = Java.loadClass("net.neoforged.neoforge.client.ClientHooks");
var s = Client.screen; var b = null;
for (var i = 0; i < s.children().size(); i++) {
  var c = s.children().get(i);
  if (c.getMessage && String(c.getMessage().getString()).indexOf("Options") >= 0) b = c;
}
var x = b.getX() + b.getWidth() / 2.0, y = b.getY() + b.getHeight() / 2.0;
CH.onScreenMouseClickedPre(s, x, y, 0);
s.mouseClicked(x, y, 0);
CH.onScreenMouseClickedPost(s, x, y, 0, false);
```

实测：标题界面上这一下点开了 `Options...`（`mouseClicked` 返回 true，`AutoJS.ui().screen.class` 变成 `OptionsScreen`）。键盘/字符同理：`onScreenKeyPressedPre/Post` + `screen.keyPressed(...)`、`onScreenCharTypedPre/Post` + `screen.charTyped(...)`。

### C6 复现"受伤/效果"时先看 KubeJS 藏了什么

```js
var MEI = Java.loadClass("net.minecraft.world.effect.MobEffectInstance");
var ME  = Java.loadClass("net.minecraft.world.effect.MobEffects");
var z = AutoJS.summon("minecraft:zombie", 3, 80, 3);
z.addEffect(new MEI(ME.MOVEMENT_SPEED, 200, 2));   // ✅ 实测可用
typeof z.hurt                                       // → "undefined"：KubeJS 2101 把它藏了，不是实体坏了
AutoJS.attack(z)                                    // 伤害走真实攻击/冷却路径
AutoJS.command("damage @e[type=minecraft:zombie,limit=1] 5")   // 或命令
```

### 把检查写成可复跑的断言

一段脚本返回 pass/fail，改完代码 `reload_scripts` 再跑：

```js
var checks = [];
checks.push(["物品存在", !!AutoJS.item("yourmod:your_item")]);
checks.push(["方块能放下", AutoJS.setBlock(0, 100, 0, "yourmod:your_block")]);
checks.push(["方块有方块实体", !!AutoJS.blockEntity(0, 100, 0)]);
checks.push(["组件可读", AutoJS.stack("yourmod:your_item", 1).getHoverName().getString().length > 0]);
JSON.stringify(checks.map(function (c) { return (c[1] ? "PASS " : "FAIL ") + c[0]; }))
```

写成 `kubejs/server_scripts/yourmod_test.js`，用 `reload_scripts` + `kubejs_log` 看结果。

### 排查报错

```
reload_scripts side=server     → 重跑脚本
kubejs_log     side=server     → 报错带 文件名#行号；栈帧已折叠成首帧+计数
```

`kubejs_log` 读的是该 side 的 KubeJS 控制台文件（重载会清空，所以重载后读到的就是这次加载报了什么）。更早的历史或游戏日志直接读文件：`AutoJS.read("logs/kubejs/server.log")`、`AutoJS.read("logs/latest.log")`（老一代 KubeJS 是 `.txt`）。

## 5. 心智模型（踩过的坑）

- **服务端才是权威。** 在 `side=client` 里改方块/背包/世界状态只在客户端本地生效，服务端不认。改状态默认走 `server`；`client` 用来看客户端视角、开界面、驱动玩家移动。
- **玩家移动只能在客户端侧驱动。** 服务端的 `ServerPlayer` 位置由客户端决定，往它身上塞速度几乎不动（实测 104 tick 只挪 0.2 格）；真正走路的是 `side=client` 的 `AutoJS.move`（实测 3 秒 12.2 格 ≈ 真实步速）。
- **`move` 会接管键盘输入**（否则客户端物理会把你的输入覆盖掉），代价是真按键在离开世界重新进入之前不再生效。
- **别用"开界面放鼠标"这招后台跑流程。** 1.21.1 里单人服务器**只在挂着 pause 界面时才暂停**（`screen`/`overlay` 是暂停界面且世界未联机），失焦本身**不**暂停。可开暂停界面会把 tick 停掉、还会吞掉脚本正在驱动的输入——所以它不是"放鼠标"的正当手段。要在后台跑流程，用 `AutoJS.background(true)`：它只关掉暂停界面并放开鼠标，不是新开界面。反向注意，窗口失焦时原版 `grabMouse` 不生效（`isWindowActive` 为假），点一下游戏窗口就能重新抓住鼠标。
- **运行时无法创建物品、方块、流体。** 注册表在游戏启动时就冻结了，这是结构性的；测新注册内容要重启游戏，能热重载的只有脚本。
- **事件处理器可以运行时注册**，但效果要等该事件下次派发；配方/标签这类通常要再来一次 `/reload`（`reload_scripts` 只重载脚本，不重载数据包）。
- **1.21.1 的 API 名和别的版本不一样。** 报错会明确说 `Cannot find function X in object Y`。源码就在本地：`targets/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.234-sources.jar`（5168 个 MC/NeoForge `.java`）。**不确定就先查源码，别猜。**
- **KubeJS 的 Rhino 不对脚本暴露 `getClass()` 和一部分方法**（例如 `getYRot`），要用 `AutoJS.state()` 这类封装；反射也走不通（caller-sensitive 方法被拒）。Java 字符串用 `.length()`，不是 `.length`。
- **这个引擎比你想的旧**：没有 `class`/`extends`、没有 `...`、没有 `async`/`await`/`Promise`，也没有 `globalThis`；`let/const`、箭头函数、模板字符串、解构、`for..of`、生成器、`?.`、`??`、`Map/Set` 都可用。

## 6. 常见错误对照

| 现象 | 原因 |
| --- | --- |
| `Class is not allowed by class filter!` | `unrestricted` 被关掉了，改回 `true` |
| `Cannot find function X in object Y` | API 名不对（版本差异），查 sources jar |
| `'x' matches N … ; use the full id` | 名字有歧义，用全名（`minecraft:stick` 而不是 `stick`） |
| `Side 'client' is not registered` / `has no KubeJS scripts` | 专用服务器没有客户端侧；客户端未加载世界时没有服务端侧，先 `status` |
| `Timed out … waiting for the server thread` | 主线程卡住或正在重载，调大 `timeout_ms` |
| `mining/using/placing must be driven on the server side` | 这些动作要用 `side=server` |
| `this side has no player to act on` | 服务端没人登录 / 客户端没进世界 |
| `ok:true` 但什么都没发生 | 先看 `diagnostics`，再回读状态验证 |

## 7. 边界与还没做的

- 专用服务器没有客户端侧；客户端未加载世界时没有服务端侧。
- 端点默认无 token、只绑 `127.0.0.1`，且默认拆掉沙箱：能访问它 = 能在该 JVM 里执行任意 Java。别在共享机器上开着。
- **还没做**：容器槽位读写（箱子里有什么、往熔炉投料、取产物）、定点寻路（现在只有"按住方向键"，绕不开悬崖/水/栅栏）、方块形状摆放校验、装备穿戴与比较、数据包收发的高层封装（目前直接用 KubeJS 的 `NetworkEvents`）。
