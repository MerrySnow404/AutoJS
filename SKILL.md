---
name: autojs
description: Test Minecraft mods on a running game through the AutoJS MCP endpoint. Use it to evaluate JavaScript on the client or server side of a live NeoForge 1.21.1 + KubeJS game, inspect and mutate state, place blocks, summon entities, open GUIs, drive the player, hot-reload KubeJS scripts, read their errors, and turn any of it into a repeatable assertion.
---

# AutoJS

把运行中的游戏的 KubeJS 脚本上下文暴露成本地 MCP 端点：**一条通向活着的游戏的口子，里面跑什么脚本由你决定**。

用途是**测模组**，工作方式是**高频迭代**：写一小段脚本 → 跑 → 看返回值/日志 → 改 → 再跑。没有构建、没有重启、秒级往返，所以可以一条一条假设地试，而不是一次写一大段赌它对。这也是它区别于"写个调试命令 / 加临时日志 / 重启游戏"的地方：**试错成本低到可以随便试**。

所以**不要被下面任何一层绑住**：

- 内置 `AutoJS.*`（第 3 节）是**可选封装**——省掉坐标、注册表查找、掉落拾取之类的样板，不用它直接写 Java 也一样能碰到任何东西；
- KubeJS 事件是**可选驱动方式**——需要"随时间观察/随事件触发"时用它最省事，需要一次性求值时完全不必；
- 第 4 节的参考流程只是**常见环境的现成套路**，照抄可以，但不是必走路径。

反过来，三层都用上通常最省事：用 `eval_js` 跑一段 Java 拿到对象，用 KubeJS 事件观察后续，用 `AutoJS.*` 收拾样板活。

两端（服务端 / 客户端）各自可调，一个 JVM 一个端点。

## 1. 框架原理

**结构**：`KubeJS 脚本上下文 → AutoJS（MCP 端点）→ 你`。

- 传输：Streamable HTTP，`POST http://127.0.0.1:25585/mcp`（JSON-RPC 2.0）。无状态，不提供服务端 SSE 流，所以 `GET /mcp` 返回 405。`GET /health` 探活，`GET /` 给摘要。
- 管线：HTTP 线程收到 `tools/call` → 把工作投给对应端的**主线程**（服务器线程 / 渲染线程）→ 主线程执行 JS → 结果回到 HTTP 线程。所以脚本永远在正确的线程上跑，你不需要自己排队。
- 沙箱：`unrestricted` 默认为 `true`，即 KubeJS 的类过滤、事件注册守卫、路径限制**全部拆掉**——`eval_js` 里能做这个 JVM 能做的任何事（加载任意类、读写任意路径、运行期注册事件处理器）。这是"测模组"需要的权限，也是它必须只绑 loopback 的原因。
- 配置：游戏目录 `config/autojs.json`（`enabled` / `bindAddress` / `port` / `token` / `unrestricted` / `evalTimeoutMs`）。填了 `token` 就要带 `Authorization: Bearer <token>`；非本地的 `Origin` 头一律拒绝。
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

## 4. 参考流程（常见环境的套路，不是必走路径）

下面这些是测模组时反复会用到的拼法，**照抄可以，但别当成清单或必须走的流程**：它们只是把前面几节的东西拼起来的样子。真正要测什么、怎么切步骤，按你自己的模组来——很多时候一段十行的自制脚本比套用这里的模板更直接。除特别标注外都用 `side=server`。

### T1 测一个方块（放下 → 看属性 → 破坏 → 看掉落）

```js
AutoJS.setBlock(0, 100, 0, "yourmod:your_block")       // 或 place() 模拟玩家右键摆放
AutoJS.blockInfo(0, 100, 0)                            // 属性/硬度/发光/群系/方块实体
AutoJS.mine(0, 100, 0)                                 // 走游戏破坏逻辑
JSON.parse(AutoJS.collect(6))                           // 掉落物（注意 10 tick 拾取延迟）
```

要测掉落表就连续挖几个不同工具下的表现；要测"能否被工具破坏"就看 `mine` 的返回值和 `collect` 的结果。

### T2 测方块实体 / 机器（放置 → 开界面 → 看 NBT）

```js
AutoJS.setBlock(0, 101, 0, "yourmod:your_machine")
AutoJS.place(0, 101, 0, "up")                           // 右击它，等价玩家点开界面
AutoJS.where()                                          // → "container open: <标题> (container menu: YourMenu)"
AutoJS.ui()                                             // 含 menu.class / menu.slots / menu.mod
AutoJS.nbt(AutoJS.blockEntity(0, 101, 0))               // 机器内部状态，SNBT
```

**缺口**：容器槽位的读写（往机器里塞料、读进度、取产物）还没做，现在只能看 NBT 和菜单结构。这是测机器类 mod 最该补的一块。

### T3 测一个物品（拿到 → 手持 → 使用 → 看效果）

```js
AutoJS.give("yourmod:your_item", 1)
AutoJS.command("item replace entity @a hotbar.0 with yourmod:your_item")   // 精确放到手里的槽位
JSON.parse(AutoJS.state()).held                         // 确认拿到
AutoJS.use()                                            // 右键使用
JSON.parse(AutoJS.state())                              // 看 effects / health / 变化
AutoJS.nbt(AutoJS.held(AutoJS.player()))                // 物品 NBT/组件
```

### T4 测一个实体

```js
var e = AutoJS.summon("yourmod:your_entity", 0, 100, 0)
JSON.parse(AutoJS.view(360, 16, 5))                     // 结构化信息：health/hostile/targetingMe
JSON.parse(AutoJS.state())                              // 它会不会打你、你掉不掉血
AutoJS.attack(e)                                        // 攻击（受冷却限制，循环攻击要放 tick 里）
AutoJS.command("damage @e[type=yourmod:your_entity,limit=1] 5")   // 也可以走命令
```

### T5 测客户端界面与渲染（`side=client`）

```js
AutoJS.where()                                          // "at the main menu" / "in the player inventory" / 某个 mod 的界面
AutoJS.ui()                                             // screen.class / title / containerScreen / mod
Client.setScreen(null)                                  // 关掉界面；也可以 new 一个原版界面直接打开
JSON.parse(AutoJS.snapshot()).client                    // fps / window / hostingWorld
```

`ui().screen.mod` 用类的 code source 与 `ModList` 比对得出"这个界面属于哪个 mod"，开发工作区里匹配不到时该字段省略。

### T6 测事件与回调（注入 KubeJS 事件，别用 Java 循环）

```js
var __seen = [];
ServerEvents.tick(function (e) { if (__seen.length < 5) __seen.push(JSON.parse(AutoJS.state()).health); });
EntityEvents.beforeHurt(function (e) { __seen.push("hurt " + e.entity); });
AutoJS.command("damage @a 2")                           // 触发
// 稍后： JSON.stringify(__seen)
```

处理器没有注销接口：流程要自己用计数器停手，或者 `reload_scripts` 清光。

### T7 测网络包与数据同步

用 KubeJS 的 `NetworkEvents.dataReceived(...)` 观察收包（客户端/服务端各自注册），配合 `AutoJS.player().connection.send(...)` 或你自己的通道发包。同理可测"服务端改了状态，客户端什么时候才看见"：

```js
// side=server：改时间
AutoJS.setTime(18000)
// side=client：立刻读
Client.level.getDayTime()          // 可能还是旧值
// 稍后再读一次 → 追上了（时间包按周期同步）
```

### T8 测配方、标签、注册表

```js
AutoJS.craftable(10)                                    // 按真实配方算可行性，含消耗
JSON.parse(AutoJS.tags(AutoJS.item("yourmod:your_item")))
AutoJS.findItems("yourmod", 20)                         // 这个 mod 加了哪些物品
AutoJS.findBlocks("ore", 50, 20)                        // 世界里已加载区域内的矿石
```

### T9 把检查写成可复跑的断言

测模组最省事的形态：一段脚本跑完返回 pass/fail，改完代码 `reload_scripts` 再跑一次。

```js
var checks = [];
checks.push(["物品存在", !!AutoJS.item("yourmod:your_item")]);
checks.push(["方块能放下", AutoJS.setBlock(0, 100, 0, "yourmod:your_block")]);
checks.push(["方块有方块实体", !!AutoJS.blockEntity(0, 100, 0)]);
checks.push(["标签齐全", AutoJS.tags(AutoJS.item("yourmod:your_item")).length > 0]);
JSON.stringify(checks.map(function (c) { return (c[1] ? "PASS " : "FAIL ") + c[0]; }))
```

这种脚本可以直接写成 `kubejs/server_scripts/yourmod_test.js`，用 `reload_scripts` + `kubejs_log` 看结果。

### T10 排查报错

```
reload_scripts side=server     → 重跑脚本
kubejs_log     side=server     → 报错带 文件名#行号；栈帧已折叠成首帧+计数
```

`kubejs_log` 只读 KubeJS 的日志文件（重载时被清空，所以重载后立刻读到的就是这次加载报了什么）。更早的历史或游戏日志直接读文件：`AutoJS.read("logs/kubejs/server.log")`、`AutoJS.read("logs/latest.log")`。

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
