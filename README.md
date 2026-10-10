# AutoJS

基于 [SighsTemple](https://github.com/Tower-of-Sighs/SighsTemple) 框架的 Minecraft 模组工程，使用 `common + targets/<loader>-<minecraft-version>` 结构。

| 项 | 值 |
| --- | --- |
| 模组名 | AutoJS |
| mod id | `autojs` |
| 包名 / Gradle group | `cc.sighs.autojs` |
| 作者 | MerrySnow |
| 协议 | GPL-3.0-or-later |
| 当前 target | `neoforge-1.21.1` |
| Minecraft / NeoForge | 1.21.1 / 21.1.234 |
| Gradle JVM | JDK 21 |
| 依赖 | [KubeJS](https://kubejs.com) `2101.7.2-build.379` |

## IDEA

直接打开 `targets/neoforge-1.21.1/` 目录。IDEA 会导入该 target，并把 `../../common` 作为 composite build 的独立可编辑模块一起导入，只下载该 target 的加载器和 Minecraft 依赖。

## Target

| Target | Gradle JVM | 构建命令 |
| --- | --- | --- |
| `neoforge-1.21.1` | JDK 21 | `targets\neoforge-1.21.1\.\gradlew.bat clean build` |

根项目默认只同步 `common`，也可以用 `-Ptarget` 聚合构建：

```powershell
.\gradlew.bat '-Ptarget=neoforge-1.21.1' build
```

## 结构

- `common/`: 所有没有确证版本差异的共享代码与共享资源，可以引用 Minecraft、NeoForge 与 KubeJS 类型。
- `targets/*`: loader 和版本专属入口、metadata、资源及 API 适配。
- `gradle/target-conventions/publish.gradle`: 所有 target 共用的发布约定。

每个 target 通过 source-set sharing 把 `../../common/src/main/java`（由 `prepareCommonSources` 同步到生成目录）直接编译进自己的源码集，因此 `common` 既能在任意 target 内被独立编辑，也能单独构建：

```powershell
.\gradlew.bat -p common build
```

经源码确证在某个 target 上存在版本差异的 common 文件，在该 target 的 `prepareCommonSources` 中 `exclude` 对应路径，并由该 target 提供自己的实现。

## MCP 端点

AutoJS 把 KubeJS 的脚本上下文包装成本地 MCP（Model Context Protocol）端点，让 AI 直接读写运行中的游戏，而不是靠读代码猜。

- 传输：Streamable HTTP，端点为 `POST http://<bindAddress>:<port>/mcp`（默认 `127.0.0.1:25585`）。另有 `GET /health`（探活）与 `GET /`（摘要）。不提供服务端 SSE 流，因此 `GET /mcp` 返回 405，与规范允许的行为一致。
- 配置文件：`config/autojs.json`，首次启动自动生成。
- 工具：
  - `status`：列出本实例存在的 side、各自是否可用，以及端点与限额。
  - `eval_js`：参数 `code`、`side`（`auto`/`server`/`client`）、`timeout_ms`；在选定 side 的主线程上通过 KubeJS 执行 JavaScript，返回最后一个表达式的值。本次调用期间该 side 打出的日志会作为 `diagnostics` 一并返回——KubeJS 有些失败不抛出，只写日志，这个字段就是让它们可见的地方。
  - `kubejs_log`：参数 `side`、`kind`（`errors`/`warnings`/`all`）、`limit`；读该 side 的 `logs/kubejs/<side>.log`。KubeJS 在每次重载时清空它，所以重载后立即读取得到的就是这次加载的报错。Rhino 的一个解析错误会吐几十行 JVM 栈帧，这里会折叠成首帧 + 计数，`errorCount` 计的是消息数而非行数。
  - `reload_scripts`：参数 `side`、`timeout_ms`；重跑该 side 的脚本，等价于游戏内 `/kubejs reload <side>-scripts`。服务端脚本重载不会连带 `/reload` 数据包，所以改到配方/标签/战利品表还需要一次数据包重载。

### 客户端与服务端

一次连接覆盖本 JVM 的所有 side，side 由每次调用的 `side` 参数决定：

| side | 何时存在 | 执行线程 |
| --- | --- | --- |
| `server` | 专用服务器启动后；客户端仅在已加载世界（集成服务器）期间 | 服务器主线程 |
| `client` | 仅客户端；专用服务器上不存在 | 客户端主线程 |

`side=auto`（默认）在 server 可用时选 server，否则退回 client。`status` 会明确说明哪个 side 不存在，避免选错。

两端是两个独立的 KubeJS 上下文：全局变量不互通，客户端绑定（如 `Client`）在服务端是 `undefined`，反之亦然。跨端协作只能走游戏自身的网络（数据包 / `player.sendData`）。

### 权限（默认拆掉沙箱）

这是调试工具，不是给玩家用的，所以 `unrestricted` 默认为 `true`，会把该 side 的 KubeJS 沙箱拆掉：

| 门 | 原行为 | 拆掉后 |
| --- | --- | --- |
| 类过滤（`ScriptManager.isClassAllowed`） | `Java.loadClass` 与类型可见性按包黑名单拒绝 | 全部放行：`java.lang.Runtime`、`java.io`、`java.nio`、`java.net`、`net.neoforged.fml`、`cpw.mods` 等都能加载 |
| 事件注册守卫（`canListenEvents`） | 加载期之外注册事件/配方/标签/命令会被拒，而且只写日志不报错 | eval 期间放行。注册本身立即生效，但事件要等下次派发才会跑（配方/标签通常要再来一次 `/reload`） |
| 路径限制（`Path`/`File` type wrapper） | 所有 JS→路径的转换都被限制在游戏目录内 | 放行：`JsonIO`/`NBTIO`/`Files` 都能读写游戏目录之外，相对路径仍按游戏目录解析 |

实现全部走公开 API，没有 Mixin、没有反射：类过滤在 KubeJS 里只有两个消费点（`visibleToScripts` 与 `loadJavaClass`），用自带的 Context 子类替换；守卫是 public 字段；路径限制是替换 `TypeWrappers.wrappers` 里的两个条目。

代价要说清楚：**KubeJS 每个 side 只有一份脚本上下文，所以拆沙箱对该 side 的所有脚本生效**，不只是 eval 出来的片段——你自己的 `server_scripts` 也不再有类过滤。把 `unrestricted` 设为 `false` 即恢复原状。

拆不掉的部分不是守卫而是结构：运行时无法注册物品与方块（注册表已冻结）；标为 startup-only 的 `EventGroup` 仍然拒绝 server/client 脚本类型；反射依然别扭——KubeJS 的 Rhino 隐藏了 `getClass()`、并拒绝对 caller-sensitive 方法做 MethodHandle 查找，所以拿不到 `Method`/`Field` 对象（包装那一层已放开，但上游还有两道）。

### 安全

端点默认只绑 `127.0.0.1`，并拒绝非本地的 `Origin` 头以阻断 DNS rebinding。它能执行任意 JS，加上默认开启的 `unrestricted`，等价于在这个 JVM 里执行任意 Java：若该端口不止你一个人可达，请在配置中填写 `token`，请求时带 `Authorization: Bearer <token>`；`enabled=false` 可整体关闭。

### 已知边界

受 KubeJS 自身限制（详见工具描述与「权限」一节）：运行时无法注册物品与方块（注册表已冻结，这是结构性的）；把 `unrestricted` 关掉后类过滤与路径限制会重新生效；eval 定义的全局在 `reload_scripts`、`/reload` 或资源重载后丢失；反射始终别扭——KubeJS 的 Rhino 隐藏了 `getClass()` 并拒绝对 caller-sensitive 方法做 MethodHandle 查找。

## AutoJS 全局

同一套能力也以 `AutoJS` 全局绑定注入每个 KubeJS 脚本上下文，`startup_scripts` / `server_scripts` / `client_scripts` 里都能直接用，和 `eval_js` 是同一个对象（代码在 `targets/*/…/neoforge/script/AutoJSTools.java`）：

```js
AutoJS.where()       // 人话回答现在在哪："at the main menu" / "playing in minecraft:overworld at 2,89,-6, no screen open" / "at a crafting table"
AutoJS.ui()          // 同样内容的 JSON：{where, screen:{open,class,title,pauseScreen,containerScreen,mod}, menu:{kind,class,slots,mod}}
AutoJS.menu()        // 当前打开的容器菜单（两端都有：客户端取本地玩家的，服务端取权威的那份）
AutoJS.snapshot()    // 一句话定位：side/dist/where/tick/维度/时间/天气/玩家
AutoJS.item('jetpack')                         // 精确 id 或模糊名；多个候选时抛错并列出候选
AutoJS.findItems('diamond', 10)                // → ['minecraft:deepslate_diamond_ore', 'minecraft:diamond', ...]
AutoJS.tags(AutoJS.item('diamond'))            // → ['c:gems', 'minecraft:beacon_payment_items', ...]
AutoJS.command('give @a minecraft:diamond 8')   // 任何 mod 的命令
AutoJS.nbt(AutoJS.blockEntity(0, 101, 0))      // → {Items:[],id:"minecraft:chest",x:0,y:101,z:0}
AutoJS.inventory(AutoJS.player())              // 只列非空槽
AutoJS.write('report.json', JSON.stringify({}))// 相对路径按游戏目录解析
```

`where()` 认得的阶段：主菜单、首次启动的无障碍界面、加载世界、连接服务器、已断开、暂停（含所在维度坐标）、死亡界面、物品栏、工作台、熔炉，以及**任何**容器界面（会报出它显示的是哪个菜单，例如 `container open: Crafting Table (crafting table)`）。平台/机器类界面走同一条通用分支，所以各类模组的 GUI 都适用；`ui()` 里还会在可判定时给出所属 mod id。

- 定位：`where` `ui` `menu` `snapshot` `side` `mods` `modVersion` `help`
- 查询：`item` `block` `entityType` `id` `tags` `findItems` `findBlocks` `findEntities` `mods` `modVersion`
- 读状态：`server` `level` `player` `players` `pos` `posOf` `blockAt` `blockEntity` `nbt` `entitiesNear` `inventory` `held` `snapshot`
- 操作（需要服务端）：`command` `give` `setBlock` `summon` `setTime` `setWeather` `heal` `tp`
- 玩家状态：`state` `look` `view`（视锥）
- 感知：`findBlocks(query, radius, limit)`（世界里找方块，只读已加载区块）、`nearestBlock`、`blockInfo`、`distanceTo`
- 动作（走游戏自己的路径，算正常玩法）：`lookAt` `move`（需客户端侧）`crouch` `mine` `collect` `attack` `use` `place`
- 进展：`craftable` `craft`
- 文件：`read` `write` `append` `exists` `readJson` `writeJson`
- 其它：`stack` `log` `help`

`AutoJS.help()` 打印完整签名清单。两条约定：坐标一律三个数字（另有 `blockAt(BlockPos)` 这类重载）；改世界的方法在客户端会抛错，因为客户端改的是服务端不认的幽灵状态。

### `area()`：区域体检

以实体或坐标为中心，一次拿到一块区域的概况。区域是立方体（中心 ± `radius`），`vertical` 可单独改垂直半程。

```js
AutoJS.area()                       // 以玩家为中心，半径 32
AutoJS.area(48)
AutoJS.area(AutoJS.player(), 64)    // 中心：entity | BlockPos | {x,y,z} | [x,y,z]
AutoJS.area(0, 64, 0, 48)
AutoJS.area(AutoJS.player(), 48, { top: 20, entities: 10, volume: false })
```

返回 JSON，结构固定，为空的分区会省略：

```json
{"dimension":"minecraft:overworld","center":[0,64,0],
 "box":{"min":[-32,32,-32],"max":[32,96,32],"size":[65,65,65]},
 "cost":{"ms":37,"chunksInBox":25,"chunksLoaded":25,"columnsScanned":4225,"columnStride":1,
         "volumeSamples":200000,"volumeStride":1},
 "height":{"min":62,"max":91,"avg":68.4},
 "biomes":{"minecraft:plains":4100,"minecraft:river":125},
 "surface":{"minecraft:grass_block":3900,"minecraft:water":200}, "blocks":{"minecraft:stone":120000,"minecraft:dirt":30000},
 "blockEntities":{"minecraft:chest":3},
 "blockEntityList":[{"id":"minecraft:chest","pos":[10,64,3]}],
 "entities":{"total":37,"byType":{"minecraft:pig":20,"minecraft:sheep":17}},
 "entityList":[{"type":"minecraft:pig","name":"Pig","pos":[1,64,2],"alive":true}],
 "structures":["minecraft:village_plains"],
 "notes":["only chunks that were already loaded are read; nothing was loaded or generated"]}
```

`surface` 和 `height` 用的是 `MOTION_BLOCKING` 高度图，也就是地面（或水面）：树叶、雪层这类不挡路的装饰不会把地形藏起来，所以森林里看到的仍是草/土/石头。`biomes` 按列统计。

| 选项（第 3 个参数） | 默认 | 作用 |
| --- | --- | --- |
| `vertical` | = `radius` | 垂直半程 |
| `top` | 10 | 每个分布返回前多少条；超出会在 `notes` 里说明总数 |
| `entities` | 0 | `entityList` 列出多少个个体（0 = 只要统计） |
| `volume` | true | 是否做三维方块组成普查 |
| `structures` | true | 是否列出区块里的结构 |

三个刻意的设计：

- **只读已加载区块。** 用 `getChunkNow` 取快照，绝不因查询而加载甚至生成区块——否则一个调试查询就能在世界里画出几百个新区块。未加载的部分不计入，`notes` 里报出 `chunksLoaded / chunksInBox`。
- **代价有上界，且写在返回值里。** 半径上限 128；列扫描最多 16384 列、体素普查最多 20 万采样点，超了自动按步长降采样，`cost` 给出 `columnStride`/`volumeStride`，`notes` 说明原因。
- **分布只给 top-N。** 每个分布按数量降序截到 `top` 条；种类更多时 `notes` 告诉你总共有多少种。
- **`structures` 只反映"已加载区块里登记的结构起始点"**（世界生成时写入的）。所以没生成过结构的区域不会有这一节；事后用 `/place structure` 放的结构也不计入——实测：放完村庄后 `getAllStarts()` 与 `StructureManager.getAllStructuresAt()` 都仍是 0。

## 依赖

KubeJS 在 target 与 `common` 中各自声明，两边都从公开仓库解析：

```groovy
repositories {
    maven { url = 'https://maven.latvian.dev/releases' }
    maven { url = 'https://jitpack.io' } // KubeJS 的 animated-gif-lib 传递依赖
}

dependencies {
    implementation "dev.latvian.mods:kubejs-neoforge:${kubejs_version}"
}
```

`kubejs_version` 同时写在 `targets/neoforge-1.21.1/gradle.properties` 与 `common/gradle.properties`（后者供 `common` 独立编译使用），两者必须保持一致。`targets/neoforge-1.21.1` 的 `META-INF/neoforge.mods.toml` 把 KubeJS 声明为 `required` 依赖（`ordering = "AFTER"`）。

## 共享资源

将所有加载器和版本共用的资源放在 `common/src/main/resources/`。构建任意 target 时，该目录会与 target 自己的 `src/main/resources/` 合并并写入最终 jar。

加载器 metadata 仍必须保留在 target 中：Fabric 使用 `fabric.mod.json`，Forge 使用 `META-INF/mods.toml`，NeoForge 使用 `META-INF/neoforge.mods.toml`。

## 本地依赖

每个 target 都会自动将自身 `libs/` 目录中的 `*.jar` 作为 `implementation` 依赖。将 jar 放入 `targets/neoforge-1.21.1/libs/` 后不需要在 `build.gradle` 中逐条声明；`*-sources.jar` 和 `*-javadoc.jar` 会被忽略。

本地 jar 的传递依赖无法自动推导。若某个 jar 还依赖其他库，需要将这些库也放入同一个 `libs/` 目录，或按常规方式声明依赖。

## 发布

target 提供 `publishMods`，可手动发布其自身的产物至 CurseForge 与 Modrinth。两个平台的项目 ID 是非敏感信息，在根 `gradle.properties` 中取消注释并填写：

```properties
publish_curseforge_project_id=你的CurseForge项目ID
publish_modrinth_project_id=你的Modrinth项目ID
```

token 只从环境变量读取，不要写入仓库。PowerShell 示例：

```powershell
$env:CURSEFORGE_TOKEN = '...'
$env:MODRINTH_TOKEN = '...'
$env:PUBLISH_CHANGELOG = '本次版本的更新说明' # 可选

cd targets\neoforge-1.21.1
.\gradlew.bat publishMods
```

`publish` 任务会把 target 的 Java component 发布到 Sighs Maven 仓库，细节见 [Publishing](docs/PUBLISHING.md)。

## 新增 Target

为其他加载器或 Minecraft 版本新增 target 时，按 [AGENT.md](AGENT.md#将既有项目迁入本框架) 的迁移流程建立独立工程：自带 wrapper、`settings.gradle`（`includeBuild('../../common')`）与本地 `gradle.properties`，然后把目录登记到根 `build.gradle` 的 `targetBuilds`。

## 版本参考

- [SKILL.md](SKILL.md)：给 AI 用的操作手册（配方、心智模型、常见错误对照、边界），可直接作为 agent skill 装载；每个 target 构建时也会把它打进 mod jar 根路径（`SKILL.md`），发行版里可直接解出来用
- [Minecraft 迁移差异参考](docs/version-differences/README.md)
- [多版本日常维护工作流](docs/MAINTENANCE_WORKFLOW.md)
- [Target CI 自动发现](docs/CI_TARGET_DISCOVERY.md)
