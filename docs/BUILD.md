# 构建与验证

> 面向改代码的人。只想用的话看 [../README.md](../README.md)。

## 为什么不用 Gradle

构建直接拿启动器已经下载好的 jar 当 classpath：

```
%APPDATA%\PrismLauncher\libraries\net\neoforged\neoforge\<ver>\neoforge-<ver>-client.jar
%APPDATA%\PrismLauncher\libraries\net\minecraft\client\...\client-...-srg.jar
%APPDATA%\PrismLauncher\libraries\net\neoforged\neoforge\<ver>\neoforge-<ver>-universal.jar
+ fancymodloader/loader、bus、coremods、mergetool、authlib、gson、brigadier、datafixerupper
```

所以：**不需要联网、不需要 Gradle、不需要 NeoGradle/ModDevGradle**。
NeoForge 1.21.1 生产环境就是 mojmap 方法名，不涉及 SRG remap。

## 命令

```powershell
.\build.ps1                                   # 编译 + 打包 + 装进实例 mods/
.\build.ps1 -NoInstall                        # 只编译
.\build.ps1 -Instance 'D:\other\.minecraft'   # 装到别的实例
```

改完 mod **必须重启游戏**，FML 没有热重载。

`build.ps1` 在安装前会同时删除 `mcagent-*.jar`（改名前的旧包）和
`AgentInMinecraft-*.jar`——两个同名 mod 会抢同一个端口。

## 几个必须知道的坑（`build.ps1` 里都处理了）

- `@Mod` / `IEventBus` 不在 NeoForge 的三个 jar 里，在 `fancymodloader/loader` 和 `bus`
- `net.neoforged.api.distmarker.Dist` 只有 `mergetool-*-api.jar` 里有一份
- `Commands.performPrefixedCommand` 是**实例方法**
- `CommandSourceStack` 的公开构造器接受一个 `CommandSource`，这是捕获命令输出的唯一办法
- 编译要加 `-proc:none`：sponge-mixin 自带的注解处理器需要 ASM，会把 javac 搞崩

## 测试

```powershell
.\tools\test.ps1               # 传输层回归，9 条，不连接游戏
.\tools\build-live-tests.ps1   # 构建可选 QA mod（build/mcagent-qa.jar）
```

QA mod 只允许在目录名为 `mcagent-review-*` 的存档副本里运行 `/mcagentqa run`，
下一 tick 执行后用 `/mcagentqa results` 看结果；`/mcagentqa ticks` 输出每 tick 实际计数，
`/mcagentqa chat` 临时进入 chat 模式 100 tick。测完把 QA jar 移走再重启。

传输层测试覆盖：chat 模式拒绝 WS 命令、allowCommands 开关、chunked 小体、
8 MiB 边界、超限块、累计上限、负块、未终止块流、缺失 CRLF。

## 发布

```powershell
.\tools\publish-curseforge.ps1 -DryRun   # 看要传什么
.\tools\publish-curseforge.ps1           # 真传，需要 PS7
```

凭据只从环境变量读（`CURSEFORGE_TOKEN`、`CURSEFORGE_PROJECT_ID`），
不进仓库、不进命令历史。CurseForge 项目必须先网页建好，API 不能往不存在的项目传。

## 目录

```
src/main/java/dev/mcagent/
├── McAgentMod.java      入口、事件注册
├── AgentConfig.java     配置读写（含旧文件名迁移）
├── AgentMode.java       work/chat 闸门
├── AgentCommands.java   /agent 与 /agent config
├── GameBridge.java      主线程调度、聊天、命令捕获
├── Tools.java           35 个 MCP 工具
├── McpServer.java / HttpCore.java / WebSocketConn.java / WsHub.java
├── fakeplayer/
│   ├── FakePlayers.java     无客户端 ServerPlayer 的生成与销毁
│   ├── FakeConnection.java  吞掉出站包的空连接
│   ├── AgentMovement.java   每 tick 驱动、逐跳执行
│   ├── AgentNav.java        带跳跃的 A*
│   └── AgentActions.java    挖/放/用/打、挖掘计时
├── client/              皮肤与截图（dist 判断 + 反射，专用服务器也能加载）
└── mixin/               PlayerInfo（客户端皮肤）、NetworkRegistry（假玩家放行）
```

## 内部命名

产品名是 **Agent In Minecraft**，但 Java 包仍是 `dev.mcagent`、日志前缀仍是
`[mcagent]`、mixin 配置文件仍是 `mcagent.mixins.json`。这些是内部标识，
改名会牵动 35 个文件和 mixin 加载配置，收益为零。
