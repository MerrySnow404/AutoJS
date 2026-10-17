# AutoJS

开发完模组内容之后总是要自己完成大部分验收，再对功能分别测试。

你是否想过让AI代行测试，但是又苦于computer use太脆弱、数据包仅限服务端、JUnit覆盖不全、脚本测试效率太低？

本模组提供了一种类似JShell的交互式编程环境工具，可以通过本地端口**直接向游戏中发送脚本并热执行**，可以**调用游戏进程中几乎所有Java类**。

你可以让AI随时使用本模组瞬间获取游戏中的任何数据、模拟游戏中的任何行为，近乎无限制，包括但不限于**GUI交互测试、生物行为测试、地形生成测试、多人游戏模拟、正常游戏流程模拟**等。

## 主要内容

KubeJS大家都很熟悉了，它内置了非常多的事件和工具类，并借助犀牛(Rhino)在客户端重载、服务端重载、启动阶段三个时间点执行(eval)。

事实上早在2025年中就有整合包开发者使用了监听聊天框消息并直接执行输入的JavaScript这样的调试方法，这也是本工具的前身。

本工具依赖于犀牛，而KubeJS是可选依赖，所有的类型筛选器、守护机制和路径限制在本工具的调试通道中全部被禁用，像HttpURLConnection或是Files这样危险的工具也可以被调用，通过内建的本地端口通讯，AI可以从外部直接调用。

简而言之，AI生成一段JavaScript脚本，发送给本工具的通讯端口，被犀牛热执行并转为Java类调用。

KubeJS预注册了非常多的绑定层如Item/Text/NBT/Registry/Ingredient/Utils/Platform/Fluid/Client/GLFWInput，并且内置了ServerEvents.tick、PlayerEvents、BlockEvents、ItemEvents、EntityEvents、LevelEvents、NetworkEvents、ClientEvents、StartupEvents、KeyBindEvents等大量常用事件，在可以热执行、热重载的需求下，这些事件非常方便，如果当前游戏版本有KubeJS可用，推荐一并安装。

除此之外本工具也预注册了几十个常用的调试方法，如获取当前游戏状态、获取世界内附近地形情况等，可以使用AutoJS.help()方法来查询所有本工具预注册的所有方法。

官方Maven：
```
repositories {
    maven {
        url "https://maven.sighs.cc/repository/maven-public/"
    }
}
dependencies {
    implementation 'com.sighs:AutoJS-forge-1.20.1:1.0.0'
}
```

## 其他

- **严禁整合包使用。**
- 目前仅支持了KubeJS支持的游戏版本，但犀牛覆盖的版本要多得多，后续会补上，如果有紧急需求请留言。
- 考虑到部分开发者有自研的加强版犀牛，或者很关注犀牛的更新，本工具没有内置犀牛。
- 自由度太高带来的缺点之一是AI自由发挥的稳定性较差，推荐在调试流程跑通之后让AI同步整理为稳定的测试脚本，方便反复测试，也可以用于Github的workflow测试。
- 本工具的jar中已内置SKILL，AI直接读取jar即可，但不同的使用场景最好整理一下相适应的SKILL，如果要让AI通过本工具像普通玩家一样玩游戏，也是可以做到的，可以使用仓库中的SKILL-GAMEPLAY试试。
- 欢迎投稿不同场景中适用的SKILL！

![cover](https://resource-api.xyeidc.com//client/members/pics/82ef1547)