# AutoJS 开发与迁移规范

本仓库使用 `common + targets/<loader>-<minecraft-version>` 结构维护多个 Minecraft 加载器和版本，当前提供 `targets/` 下的六个 target（构建表见下文）。任何开发者或自动化代理在修改前都必须遵守本文件；更完整的协作、评审和 CI 规则见 [docs/MAINTENANCE_WORKFLOW.md](docs/MAINTENANCE_WORKFLOW.md)。

## 架构边界

```text
common/                         无确证版本差异的共享源码树与共享资源
targets/<loader>-<mc-version>/  一个独立的加载器 + Minecraft 版本工程
gradle/target-conventions/      所有 target 共用的构建约定
```

- `common` 放所有没有确证版本差异的代码。依赖平台类型不是拆分理由，Java 版本也不是：`common` 至今仍引用 Minecraft 类型，但只在 `mc/` 那层接缝之后——共享代码经 `cc.sighs.autojs.mc.McApi` 取到 `LoaderApi`/`VanillaApi`/`ClientApi`，接口签名里不出现任何只在某个版本存在的类型（要拿注册表值就传 `"namespace:path"` 文本，不在接口里暴露 `BuiltInRegistries`；id 类型本身在 26.1 从 `ResourceLocation` 改名成 `Identifier`，所以连它也不能出现在接缝签名里），这些调用点的实现全在 target。
- `targets/*` 除了该 target 的 metadata 与资源（`META-INF/neoforge.mods.toml`、`kubejs.plugins.txt`、版本/loader 专属资源），还放**经源码确证存在版本差异或加载器差异的 Java 实现**；其余共享逻辑在 `common`。当前 `targets/neoforge-1.21.1/src/main/java/` 承载三条轴的全部实现：脚本引擎轴（KubeJS 引擎与 Rhino 内核）、加载器轴（mod 入口与 `AutoJSBackends` 门面）与 Minecraft API 轴（`neoforge/mc/` 下 `McApi` 三个接口的实现，以及因为 `Input` 本身随版本改变而一并归 target 的 `neoforge/client/AutoJSInput`）。
- 每个 target 通过 `includeBuild('../../common')` 把 `common` 作为 composite build 导入 IDE（仅让 IDE 把 `common` 当作独立可编辑模块，不产生 Gradle 项目依赖）；再用 source-set sharing 把共享源码编译进自己：`prepareCommonSources` 这个 `Sync` 任务把 `../../common/src/main/java` 同步到 `$buildDir/generated/common-main` 并加入 main 源码集，test 源码集加入 `../../common/src/test/java` 与 `../../common/src/test/resources`。target 不依赖 `project(':common')`。
- 版本差异的登记点是各 target `prepareCommonSources` 里的 `exclude` 块：某个 common 文件在该 target 上有确证差异时，在那里 `exclude '<路径>'`，并把该 target 自己的实现放进 target。
- 唯一能把代码从 `common` 拆到 `targets/<loader>-<mc-version>/` 的理由是：经阅读实际源码/API 确证该内容在不同 loader 或不同 Minecraft 版本之间存在差异。脚本引擎世代算同一回事：`ScriptEngine` 的实现绑死在某一代 KubeJS/Rhino 的 API 上（2101 有 `KubeJSContextFactory`、`rhino.ContextFactory`，1802/1902/2001 没有），所以引擎实现归 target。不得凭猜测拆分，也不得为了“代码好看”而拆分。
- `common` 不是独立的 Gradle 子项目，而是共享源码树：`common/build.gradle` 用 `java-library` + `net.neoforged.moddev 2.0.141`、toolchain 21，对着与 `targets/neoforge-1.21.1` 相同的 NeoForge 1.21.1 类路径（含 KubeJS 与 Rhino）独立编译与测试（`gradlew -p common build` / `gradlew -p common test`）。
- 不要用运行时版本判断、反射或同名 class 覆盖来兼容不同 target；不同 API 应由各 target 的适配器实现。
- 一个发布 jar 只对应一个 loader 与一个 Minecraft 版本，禁止 universal jar。
- AutoJS 的分层：共享逻辑在 `common`，确证有版本或加载器差异的实现放 target、经该 target `prepareCommonSources` 的 `exclude` 替换。留在 `common/src/main/java/cc/sighs/autojs/` 的是 MCP 协议、HTTP 传输、工具注册表、side SPI（`side/` 的 `EvalBackend`/`EvalBackends`、`engine/` 的 `ScriptEngine`/`EngineSideBackend`、`neoforge/backend/` 的投递与超时）与 Minecraft API 接缝（`mc/` 的 `McApi` 及它给出的 `LoaderApi`/`VanillaApi`/`ClientApi`）。
- Minecraft API 轴：共享文件里任何随版本改变写法的调用点都不得直接出现，一律经 `mc/` 的接口；`McApi.install(LoaderApi, VanillaApi)` 由 target 的 mod 构造期调用，`McApi.installClient(ClientApi)` 由客户端入口调用。两个 `static` 工具类（`PlayerOps`/`AreaScan`）与实例类（`AutoJSTools`）都从这个静态入口取实现，因此接口方法不经过任何构造函数。跨版本写法不变的调用点照旧直接写在共享代码里（例如 `EntityType.create(Level)`、`Entity.teleportTo`、`CommandSourceStack`、`ChunkAccess.getHeight`），不为了整齐而搬进接口。
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/autojs/neoforge/` 里是 mod 入口（`AutoJSNeoForge121`、`AutoJSNeoForgeClient`）、引擎选择门面（`AutoJSBackends`）、两个 KubeJS side 后端、KubeJS 接线与拆沙箱、以及 Rhino 内核——它们要么用 NeoForge FML API，要么用本世代才有的 KubeJS/Rhino API，在别的 target 上编译不过。
- 客户端专属类（`neoforge/client/ClientEvalBackend`、`neoforge/AutoJSNeoForgeClient`，都在 `targets/neoforge-1.21.1/`）只能被客户端路径引用。服务端入口一律不得直接加载它们，否则专用服务器会 `NoClassDefFoundError`。
- KubeJS 是软依赖，判据只有 `ModList.get().isLoaded("kubejs")`：门面 `AutoJSBackends` 与 mod 入口不得 import 任何 `dev.latvian.mods.kubejs` 类型，KubeJS 分支只能收在 `neoforge/kubejs/`（`KubeJSIntegration` 与 `KubeJSEngine`）与既有的 KubeJS 专用类里，否则没装 KubeJS 时构造期就 `NoClassDefFoundError`。没装 KubeJS 时用自带内核（`neoforge/rhino/`、`neoforge/backend/RhinoSideBackend`、`neoforge/eval/ScriptValue`），它按 `neoforge.mods.toml` 里的 `rhino` 依赖在运行时解析。
- `kubejs.plugins.txt` 归 target：它是 KubeJS 专属入口，格式为 `<fqcn> [client|<modid> ...]`。
- 拆 KubeJS 沙箱的代码在 `targets/neoforge-1.21.1` 的 `neoforge/unrestricted/` 下（入口是 `AutoJSUnrestricted`，由 KubeJS 插件在加载期的两个时刻调用），由 `unrestricted` 配置开关控制，而且只用公开 API 实现（Context 子类、public 字段、`TypeWrappers.wrappers` 的条目替换），不使用 Mixin 也不使用反射，以免 KubeJS 升级就把整条链扯断。它是 KubeJS 引擎的实现细节：调用方只通过 `ScriptEngine.unrestricted()` 问沙箱开没开，不需要知道这三道闸门分别是什么。

## 文件与配置约定

| 内容 | 位置 | 规则 |
| --- | --- | --- |
| 共享 Java 代码 | `common/src/main/java/` | 放所有没有确证版本差异的代码；确证有版本差异的文件在该 target 的 `prepareCommonSources` 中 `exclude`，并由该 target 提供自己的实现。 |
| 目标专属 Java 代码 | `targets/<name>/src/main/java/` | 只放已在 `prepareCommonSources` 中 `exclude` 的对应实现（如今为脚本引擎轴、加载器轴与 Minecraft API 轴）。包名与 `common` 保持一致。 |
| 共享资源 | `common/src/main/resources/` | 会自动合并到所有 target 的最终 jar。 |
| 目标专属资源 | `targets/<name>/src/main/resources/` | 仅放该版本/loader 专属资源。 |
| Loader metadata | target 的 `src/main/resources/` | 保留在 target；Fabric、Forge、NeoForge 格式不可共用。 |
| 版本/loader 参数 | `targets/<name>/gradle.properties` | 不放在仓库根 `gradle.properties`；`kubejs_version` 与 `rhino_version` 必须与 `common/gradle.properties` 保持一致。 |
| 共享模组信息 | 根 `gradle.properties` | 仅 `mod_*` 和 Gradle 运行参数。 |
| 本地 jar 依赖 | `targets/<name>/libs/` | 自动作为 `implementation` 依赖读取；不需要逐条声明。 |
| 本地 maven 仓库 | `targets/<name>/libs-maven/` | 只在 `-Pautojs.localToolchain` 下注册的仓库，放本机网络读不到的已发布构件（`*.jar` 不在跟踪范围内，只提交 pom/module 等描述文件）；该目录的 `README.md` 说明里面是什么、为什么是拷贝而不是重定向。 |
| 发布配置 | `gradle/target-conventions/publish.gradle` | 统一管理，target 不复制发布逻辑。 |
| KubeJS 插件入口 | `targets/<name>/src/main/resources/kubejs.plugins.txt` | 每行 `<fqcn> [client\|<modid> ...]`；target 专属。 |
| 运行期配置 | `config/<mod_id>.json` | 首次启动生成，含 MCP 端口与开关；运行期产物，不提交。 |

`libs/` 中的普通 jar 不会自动带来传递依赖；依赖的其他 jar 也必须放入同一个 `libs/`，或改用正常的 Maven 依赖声明。不要把 `*-sources.jar`、`*-javadoc.jar` 或构建产物误放入此目录。

共享资源与 target 资源若有同路径文件，必须明确选择唯一归属；不要依赖覆盖顺序。加载器 metadata、Mixin 配置、access widener/access transformer 和版本专属语言文件一律归 target。

## 日常开发

1. 先判断改动是 `common`、单个 target、多个 target，还是构建/发布配置。
2. 单 target 改动只修改对应 `targets/<name>/`；不因方便而改动其他版本。
3. 改动默认放入 `common`；只有经源码确证某段内容存在版本差异时，才把它移入对应 target，并在该 target 的 `prepareCommonSources` 里 `exclude` 对应 common 路径。
4. 改动资源、metadata、Mixin、注册、事件、网络或渲染时，除构建外必须做相应的运行验证。
5. 不提交 token、账号、密码、私有仓库凭据、IDE 运行缓存或 `build/` 输出。

### 构建命令

每个 target 是独立 Gradle 根工程，应在它自己的目录中构建：

```powershell
cd targets\neoforge-1.21.1
.\gradlew.bat clean build
```

| Target | Gradle JVM |
| --- | --- |
| `forge-1.18.2` | JDK 17 |
| `forge-1.19.2` | JDK 17 |
| `forge-1.20.1` | JDK 17 |
| `fabric-1.20.1` | JDK 17 |
| `neoforge-1.21.1` | JDK 21 |
| `neoforge-26.1` | JDK 25 |

### 验证现状（截至最后一次逐 target 验证）

每个 target 都在**真实运行的游戏**上验过 MCP 面（`status`/`eval_js`/`reload_scripts`/`kubejs_log`，以及 `AutoJS.*` 的注册表查询、NBT、区域扫描、时间/天气、沙箱解除等路径）。方式与环境绑定，别照抄结论：

| Target | 运行验证方式 |
| --- | --- |
| `neoforge-1.21.1` | dev 运行：KubeJS 与「无 KubeJS」两种配置 × 客户端/服务端 |
| `forge-1.20.1`、`forge-1.19.2`、`forge-1.18.2` | **生产安装**（Forge 安装器 + reobf jar + 生产版 KubeJS/Rhino）。dev 运行在这条线上不可行：生产 jar 是 SRG 命名、而 moddev 的 dev 运行期是 official 命名，mixin refmap 对不上 |
| `fabric-1.20.1` | dev 运行（loom 会把生产 mod jar 重映射给 mixin remapper） |
| `neoforge-26.1` | dev 运行 |

已知覆盖缺口，**未验就是未验，不要读成「应当没问题」**：

- 所有 target 的**客户端侧**都没有实跑过（只有服务端）；客户端专属代码（`ClientEvalBackend`、`ClientScriptBridge`、`AutoJSInput`、`ClientApi.fps()`）只到编译期与结构对照。
- **无 KubeJS（Rhino 内核）模式**只在 `neoforge-1.21.1` 做过求值验证；`forge-1.19.2`/`forge-1.18.2` 的无 KubeJS 轮只验到「mod 起得来 + endpoint 在监听」。
- KubeJS 1802 自身有缺陷：脚本里 `try/catch` 会抛 `IllegalArgumentException`（`unrestricted` 开/关都复现），是那一代 KubeJS/Rhino 的问题；在该 target 上用 `eval_js` 时要避开 `try/catch`，靠 `diagnostics` 与读回状态判断。
- 1.19.2 生产服务端在**没有玩家在线**时，实体查询可能什么都看不到（`addFreshEntity` 成功且未移除，但 `Level.getEntities(&lt;AABB&gt;)` 返回空）——已确认不是 AutoJS 的调用方式所致（直接调游戏 API 结果相同）。做实体相关验证时连一个玩家。

### 发布

在对应 target 内运行 `publishMods` 可手动发布该 target 的 jar 到 CurseForge 和 Modrinth。根 `gradle.properties` 中配置非敏感项目 ID；token 只通过环境变量提供：

```powershell
$env:CURSEFORGE_TOKEN = '...'
$env:MODRINTH_TOKEN = '...'
.\gradlew.bat publishMods
```

发布前必须执行该 target 的 `clean build`，检查 jar 内的 metadata、共享 class、共享资源和版本范围。不要从根项目或错误 target 发布。

## 将既有项目迁入本框架

迁移应以“先可构建、再抽取共享代码、最后验证行为”为顺序，禁止先删除旧工程再尝试恢复。

1. **盘点原项目**：记录 Minecraft 版本、loader、JDK、Gradle、mappings、入口、Mixin、资源、数据生成、依赖与运行配置。
2. **建立 target**：为每个 `(loader, Minecraft 版本)` 建立 `targets/<loader>-<mc-version>/` 独立工程，包含 wrapper、`settings.gradle` 与本地 `gradle.properties`。用 `includeBuild('../../common')` 让 IDE 把 `common` 作为 composite build 的独立可编辑模块导入，并把 `../../common/src/main/java` 加入 main 源码集、把 `../../common/src/test/` 加入 test 源码集（source-set sharing）；不要使用 `project(':common')` 依赖。最后把目录登记进根 `build.gradle` 的 `targetBuilds`。
3. **放专属层**：把 metadata 与版本专属资源放进对应 target，再把经源码确证有版本/加载器差异的 Java 实现也放进去（脚本引擎实现、mod 入口与事件注册是最常见的两处）。判定依据是“这段代码引用的 API 在不同 target 的类路径上是否存在”，不是“它在概念上算不算接线”。不要在一个 target 放多版本分支。
4. **抽取 common**：把没有确证版本差异的代码迁到 `common`；只有经源码确证存在版本差异的部分才留在 target，并在该 target 的 `prepareCommonSources` 里 `exclude` 对应路径。共享的 SPI（`side/`、`engine/`）留在 `common`，实现由 target 提供。
5. **迁移资源**：所有 target 共用的 assets/data/lang 放到 `common/src/main/resources/`；将 Fabric/Forge/NeoForge metadata 与版本专属 Mixin 配置保留在 target。
6. **迁移依赖**：可从公开仓库解析的依赖写入对应 target 的 `build.gradle`（需要供 `common` 独立编译的依赖同时写进 `common/build.gradle`，并在两处 `gradle.properties` 保持版本一致）；仅本地提供的 jar 放入该 target 的 `libs/`。
7. **迁移配置**：模组名称、ID、许可证、作者、描述等共享值放根 `gradle.properties`；Minecraft、loader、mappings、版本范围和 JDK 相关值放 target 本地属性。
8. **逐 target 验证**：使用要求的 JDK 运行 `clean build`，检查 jar 内容，并做最小 client 与 dedicated server 启动验证；涉及数据或资源时额外运行 data generation/reload 验证。
9. **记录差异**：不能立即统一的 API 或行为差异写入 `docs/version-differences/`，由 target 适配实现，不能以 common 中的版本判断掩盖。
10. **清理旧结构**：只有所有迁入 target 均可构建且已验证后，才删除旧代码、旧资源与旧构建入口。

## 提交前检查

- `common` 只放没有确证版本差异的代码；有确证差异的文件已在对应 target 的 `prepareCommonSources` 中 `exclude` 并改为 target 自己的实现。
- 每个新增/修改的 target 使用正确 JDK 独立构建。
- 最终 jar 含正确 metadata、目标专属资源与共享 class/resources。
- 本地 `libs/` 内容明确且没有误提交的旧 jar。
- 发布相关改动不包含 token；项目 ID、版本类型、依赖关系和 changelog 已确认。
- README、AGENT.md、版本差异文档和支持矩阵与实际 target 一致。
