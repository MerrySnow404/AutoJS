---
name: autojs-gameplay
description: 用 AutoJS 端点在一个活着的 Minecraft 世界里做"常规流程游玩"（采集、建造、战斗、赶路），而不是测模组。重点是把反应放进游戏循环、把控制面收敛成少量可靠原语，以及避免"主机侧思考时玩家在挨打"这类延迟性死亡。
---

# AutoJS 游戏流程游玩

[SKILL.md](SKILL.md) 讲的是**测模组**：写一小段脚本 → 跑 → 看返回值 → 改。那个工作方式的前提是"世界可以等你"。

**常规游玩的前提正好相反：世界不会等你。** 这段 SKILL 是那份文档的补集，只写游玩时真正会卡住人的东西。

一句话结论：**把决策放进游戏的 tick 循环里，主机侧只负责下目标和看状态。** 所有"在主机侧想一步、再发一条命令"的玩法都会死人。

---

## 0. 先认清代价：延迟表

下面这些是实测数字，用它来判定"某件事能不能放在主机侧做"。

| 动作 | 典型耗时 | 后果 |
| --- | --- | --- |
| 一次 `eval_js` 往返 | 20–60 ms | 还行，但别按 tick 频率用 |
| 一次 `eval_js` 往返 + 一次 `sleep` 轮询 | 300–800 ms | 这期间玩家**完全没有控制** |
| 一次 python `nav` 轮询周期 | 1.5 s | 僵尸能砍你 3 下 |
| 一次 `goto`/`travel` 里的"想一下" | 数秒–数十秒 | 已经死过好几次了 |

结论：

- **不会被伤害打断的事**（挖一个已知方块、合成、看背包）→ 主机侧随手做，无所谓。
- **会被伤害打断的事**（赶路、战斗、下水、夜里待在野外）→ 必须做成游戏内循环，主机侧只看结果。
- 处在危险里时，主机侧**任何**超过 ~0.5 s 的"思考/验证"都等于放弃控制。想清楚的时机是在安全的时候，不是在矿洞里。

---

## 1. 开工前的三个探针（30 秒，必须做）

### 1.1 世界在跑吗

```js
// side=server
JSON.stringify({ t1: AutoJS.server().getTickCount() })
```

隔一秒再读一次。**两次相等 = 世界被暂停了**，后面所有"没有反应"的现象都是这个原因，不要往别处查。

### 1.2 谁在暂停它：`pauseOnLostFocus`

单人世界挂 pause 界面时集成服务器会停 tick。而**默认选项 `pauseOnLostFocus=true` 会在窗口失焦 0.5 s 后自动弹出 pause 界面** —— 也就是"你在终端里干活"这件事本身就会让世界停下来。

```js
// side=client
Client.options.pauseOnLostFocus = false;
Client.setScreen(null);
```

`AutoJS.background(true)` 只做两件事：关掉 pause 界面、放开鼠标。**它不管 `pauseOnLostFocus`，所以它救不了你** —— 关掉界面 0.5 s 后游戏会再弹一次。两个都要做。

### 1.3 两端都活着吗

```
status → sides[].available
AutoJS.where()   // server: 世界坐标 / client: 界面
```

---

## 2. 控制面：谁只能干什么

| 能力 | 只能在 | 说明 |
| --- | --- | --- |
| `AutoJS.move(fwd, strafe, jump, sprint)` | **client** | 接管键盘输入。服务端那份 `move` 是塞速度，实测 100+ tick 只挪 0.2 格，别用 |
| 转向 | **client 优先** | 见 3.1。服务端 `lookAt` 靠 teleport 包同步，是"改朝向"的正确做法但不是每 tick 的好工具 |
| `mine` / `place` / `use` / `attack` | **server** | 客户端改的是本地幽灵状态 |
| 改世界 / 读权威数据 | **server** | |
| 读方块（感知） | **两端都行** | 客户端有自己已加载的区块，够用；放在 client 侧能省一次跨端往返 |

一条重要的经验：**把"感知 + 转向 + 移动"整条链放在 client 侧**，因为它不需要跨端，回路最短。

---

## 3. client 侧自转向：`setYaw` 而不是 `lookAt`

### 3.1 为什么

Rhino 不暴露 `getYRot` / `setYRot` / `getClass`（会报 `Cannot find function setYRot`）。**但 KubeJS 的实体包装补上了另一批名字**：

```js
// side=client，都可用
Client.player.setYaw(deg);        // yaw: 0=+z, -90=+x, 90=-x, 180=-z
Client.player.setPitch(0);
Client.player.getViewYRot(1.0);
Client.player.getViewXRot(1.0);
Client.player.getHealth();
Client.player.getX(); / getY() / getZ();   // 这三个也暴露了
```

服务端 `lookAt(目标)` 是发 `ClientboundPlayerPositionPacket` 同步朝向的，**偶尔校准可以，每 tick 调会一直重置客户端位置**。

### 3.2 转向公式

从当前位置 `(px,pz)` 指向 `(tx,tz)`：

```js
var dx = tx - px, dz = tz - pz, d = Math.sqrt(dx*dx + dz*dz);
var yaw = Math.atan2(-(dx/d), dz/d) * 180 / Math.PI;
Client.player.setYaw(yaw); Client.player.setPitch(0);
```

反过来，yaw → 方向向量：`(x, z) = (-sin(yaw), cos(yaw))`。

### 3.3 键盘以外的键：`Client.options.key*`

`AutoJS.move` 不覆盖"使用键"，而这会直接吃掉你的进食：

```js
Client.options.keyUse.setDown(true);   // 进食/拉弓期间必须按住
// ... 服务端 AutoJS.use() ...
Client.options.keyUse.setDown(false);
```

**原因**：服务端 `gameMode.useItem` 会正常 `startUsingItem`（`isUsingItem()===true`，`useItemRemainingTicks===32`），但客户端 `Minecraft` 每 tick 检查 `!options.keyUse.isDown()` 就发 `RELEASE_USE_ITEM`，把进食立刻取消 —— 现象是"用了食物但饥饿值不变"。

---

## 4. 正确架构：双端常驻 tick 循环 + 文件信箱

### 4.1 为什么不能用"主机侧一步步走"

主机侧每走一步都要一次往返；往返之间玩家是自由落体。正确做法是让游戏自己循环，主机侧只下目标和看结果。

### 4.2 信箱协议（唯一可靠的跨调用状态通道）

KubeJS **不允许给 `global` 赋值**（`'global' cannot be assigned to in client or server scripts`），所以不能拿它当共享变量。handler 的闭包变量只能被它自己读写。

用**绝对路径的文件**当信箱，两端和主机侧都能读写：

```js
var GST = "D:/work/AutoJS/.autojs-scratch/goal.json";    // 主机侧写，handler 读
var SST = "D:/work/AutoJS/.autojs-scratch/status.json";  // handler 写，主机侧读
```

要点：

- **绝对路径可用**（相对路径按游戏目录解析）。把它指向你自己的工程目录，就不必往游戏实例目录里塞东西。
- `AutoJS.read()` 返回 **Java String**，判断非空要用 `(""+raw).length > 1`。`.length` 是 `undefined` —— 这个坑会让 handler **静默地永远认为没有目标**，表现是玩家一动不动。
- 目标里带一个 `seq`/`x,z` 作为 key，变了才重置内部状态。

### 4.3 handler 的职责划分

| 侧 | 职责 | 频率 |
| --- | --- | --- |
| client tick | 读目标 → 感知 → 避障/寻路 → `setYaw` + `AutoJS.move` → 写状态 | 每 tick（寻路每 20–25 tick 重算） |
| server tick | 需要破坏/放置/攻击时；危险处置 | 按需 |
| 主机侧 | 写 goal、轮询 status、**只在安全时**思考下一步 | 1–2 s |

### 4.4 装/卸载 handler

- 从 `eval_js` 注册 handler 是可行的（`unrestricted=true` 拆掉了加载期守卫）。
- **`reload_scripts` 会清掉所有 handler**，改完脚本要重装。
- 注册用的脚本建议放在自己的工程目录里，用文件传给 `eval_js`，别在命令行里拼字符串。

### 4.5 致命细节：失活的 handler 会把玩家钉住

handler 里如果写成：

```js
if (!S.active) { AutoJS.move(0, 0, false, false); return; }   // ✗ 每 tick 都踩刹车
```

那么主机侧之后所有的 `AutoJS.move` 都会被它压掉，表现为**玩家完全不动，而每一步"看起来"都成功了**。改成只在状态切换那一次踩刹车：

```js
if (!S.active) { if (S.wasActive) { AutoJS.move(0,0,false,false); S.wasActive = false; } return; }
```

---

## 5. Rhino / KubeJS 的坑（都实际踩到过）

| 现象 | 真因 | 正确写法 |
| --- | --- | --- |
| `Cannot find function getYRot/setYRot/getClass` | Rhino 不暴露 | 用 `setYaw` / `setPitch` / `getViewYRot` |
| 字符串 `.length` 判空永远失败 | Java String 的 `.length` 是方法 | `(""+s).length` 或 `s.length()` |
| `JSON.stringify(AutoJS.findBlocks(...))` 打出一大堆 `new String(...)`、`int length()` | 对 Java 对象做了反射式转储 | `findBlocks` 本身**已经返回 JSON 文本**，直接读 `value`，别再 stringify |
| `entitiesNear(...).size()` 报错 | 返回的是 **Java 数组** | `.length` 和 `[i]` |
| `global.x = 1` 报 `'global' cannot be assigned to` | KubeJS 限制 | 用文件信箱，或 handler 闭包 |
| `ok:true` 但什么都没发生 | 没有异常、只写了日志 | 一定看 `diagnostics`，再回读状态验证 |
| 一次 `eval_js` 里 `try{}catch{}` 吞掉了错误 | 静默失败 | 把读到的原始值写进 status，事后能看见 |

---

## 6. 生存与移动原语（今天真正救命的四个）

这些都不依赖寻路，是"确定能做成的动作"。先把它们做出来，再谈聪明的导航。

### 6.1 柱跳 `pillar_up` —— 从任何坑里出来

背包里只要有方块（土就够），就能垂直上升：

```js
// 每轮：先挖头顶净空，再跳，再对着脚下那一格的顶面右键
AutoJS.mine(pos(x, y+2, z)); AutoJS.mine(pos(x, y+3, z)); AutoJS.mine(pos(x, y+4, z));
// client: 起跳一瞬
AutoJS.move(0,0,true,false);  // 60ms 后
AutoJS.move(0,0,false,false);
// 等到接近最高点（约 180ms 后），此时玩家碰撞箱已离开脚下那一格
AutoJS.place(AutoJS.pos(x, y-1, z), "up");   // side=server
```

要点：

- **必须在最高点附近放**（起跳后 ~200–250 ms）。早了玩家碰撞箱还压着目标格，服务端 `isUnobstructed` 检查会拒绝；现象是 `place` 返回 `true` 但方块没出现。
- `AutoJS.place(pos, face)` 是"对着 `pos` 的这个面右键"，所以要在 `E` 放方块，就点 `E` 的邻居朝 `E` 的那个面；在脚下垫方块 = 点下方方块的 `"up"` 面。
- 手上必须有方块：`inv.selected = i` 切槽位即可（`AutoJS.player().getInventory().selected = i`）。

### 6.2 挖阶梯 `dig_stair` —— 从任何墙外面出去

朝一个方向，每轮挖掉前方 1.3 格处 **feet+1 / feet+2 / feet+3** 三格，然后按住前进+跳跃 0.7 s。每轮自然上升约 1 格。石头没有镐不掉落，但**照样能被破坏** —— 逃命时不亏。

### 6.3 暴力推进 `force_travel` —— 局部寻路失效时的兜底

```text
loop:
  朝目标 setYaw
  挖掉前方 (feet+1, feet+2) 两格       # 万一是 1 格台阶
  AutoJS.move(1, 0, jump=true, sprint=true)
  sleep 0.75
```

今天最有价值的一条经验就在这：**花大力气写的 BFS 局部寻路在破碎地形（湖岸、冲沟、沼泽）上反复 dead-end，而这段 10 行的暴力推进行走把 107 格走完只用了 41 秒**。先有能走的，再谈走得好看的。

### 6.4 落水自救

水是最大的杀手（今天溺死 3 次）。策略：

- **寻路时把液体列视为不可通行**，并给"离水边距"（目标格四周都要是陆地）留一条严格模式。
- 一旦真的在水里：
  1. `air < 120` → **只按跳跃**（上浮），别管方向；
  2. 否则朝**最近一块陆地**游（局部扫描半径 24 的格子）；
  3. 局部扫描不到陆地时，朝**最后一次站过的干燥坐标**游（handler 里持续记录 `landPos`）。

---

## 7. 安全策略：把"会不会死"写进循环里

### 7.1 危险阈值不要设成满血

把 `health < 20` 当成停止条件，会让玩家掉半点血之后**永久拒绝移动**，然后被围死。今天的第一次死亡就是这么来的。

正确做法是分开两件事：

- **停止阈值**：`health < 12` 之类，真的危险才停。
- **受伤反应**：与基线比较，掉了血就进入"逃离/反击"分支，而不是停手。

### 7.2 handler 里常驻的护栏

```
每 tick：
  死了 / 在岩浆 / 着火        → 立刻停手，写 status="danger"
  在浅水里且氧气在掉           → 上浮
  附近敌对生物 < 6 格且自己血少 → 背对最近敌人 sprint 拉开
  每 8–12 tick 更新一次 status（含 health / 敌对数量 / 卡住计数）
```

### 7.3 卡住检测

位置 40 tick 没变就重算路径；120 tick 没变就挖阶梯。**不要无限重试同一个寻路结果** —— 今天在同一个点上打转了五分钟。

### 7.4 死亡即清零

死亡会把人送回世界出生点，之前所有位移全丢。所以：

- 天快黑、血不满、身上没装备时，**回撤/挖洞躲**比硬拼划算。
- 死一次 = 几分钟白干 + 可能丢掉刚采集的东西。复活流程：`Client.player.respawn(); Client.setScreen(null);`（客户端 DeathScreen 的按钮就是这个动作）。

### 7.5 时间

```js
AutoJS.level().getDayTime() % 24000   // 0=06:00, 13000=19:00(入夜), 23000=05:00(天亮)
```

一整夜约 10 分钟真实时间。入夜前把"回撤点/藏身处"定好。

---

## 8. 推荐工作流

1. **探针**：tick 两次 → `pauseOnLostFocus=false` → `background(true)` → 确认两端 `where()`。
2. **建信箱**：goal/status 两个 JSON 文件（绝对路径指向自己的工程目录）。
3. **写 client tick handler**，先只做三件事：读目标、`setYaw`、`AutoJS.move`。在开阔地测通。
4. **加原语**：`pillar_up`、`dig_stair`、`force_travel`、落水自救、进食。每个单独验证一次，之后靠兜底互相救场。
5. **加护栏**：威胁/血量/卡住 status。
6. **最后才做寻路**：惰性求高的 BFS（局部网格，半径 20–25），严格/宽松两遍，目标不可达时退化为"朝目标最近的可行格走"（沿海岸推进而不是撞死在海边）。
7. **主机侧薄薄一层**：写 goal、每 1–2 s 读 status、只在 status 说"安全"时才想下一步。

---

## 9. 反面清单（今天真实发生过的）

- ❌ 在海水里做 `goto` 实验，让主机侧 sleep，玩家溺水 3 次。
- ❌ 把危险阈值设成满血，掉血后原地不动被僵尸打死。
- ❌ 失活 handler 每 tick 踩刹车，手动逃跑 100 秒零位移，却以为是"寻路失败"。
- ❌ 反复用"一次性 eval 探一探"代替建立常驻循环，每一次探查都在放弃控制。
- ❌ 在没有 `force_travel` 之前死磕 BFS 寻路，把时间花在了"更聪明的寻路"而不是"能走就行"。
- ❌ result 形状试错时把 `JSON.stringify` 套在已经返回 JSON 文本的 API 上，读回来一堆类成员列表。
- ❌ 手边有 39 个土方块却没想到柱跳，在坑里靠寻路原地打转。

**共同点：所有这些都是"主机侧在想事情"或"主机侧在做验证"的时候死的。** 判断一个动作该不该做，标准不是"它有没有用"，而是"它需不需要玩家在这几秒里被控制"。

---

## 10. 参考实现

今天写出来、验证过能用的三个文件（放在工程目录的 scratch 子目录里）：

- `mc.py` —— 主机侧：`state/dig_stair/force_travel/pillar_up/kill_mobs/eat_all`。
- `walker.js` —— client tick 常驻：信箱 + 惰性 BFS + 离水边距 + 落水自救 + 卡住检测。
- `ajs.py` —— 最小 `eval_js` 客户端（读 stdin 的脚本、打印 `value` 与 `diagnostics`）。

它们只是"能跑"，不是"写得好"：`walker.js` 在破碎地形上仍会 dead-end，`force_travel` 才是真正的日常主力。下一步该做的是把 4.2 的信箱协议 + 6.3 的暴力推进 + 7.2 的护栏合成**一个** client tick 常驻体，让主机侧彻底退出实时回路。
