# mcagent — Minecraft Agent Bridge

把一个正在运行的 Minecraft 客户端/整合包，变成 AI agent 可以直接操作的进程：
MCP（Model Context Protocol）服务器 + WebSocket 实时通道，全部跑在游戏进程内。

替代了之前靠 KubeJS/ProbeJS 拼出来的旁路方案。

## 它提供什么

| 通道 | 端点 | 用途 |
|---|---|---|
| MCP (Streamable HTTP) | `POST /mcp` | 23 个工具：状态、命令、方块、机器 NBT、背包、实体、聊天、粒子、日志、截图、配方、任务书、批量建造/撤销 |
| WebSocket | `GET /ws?token=` | 玩家实时发送的聊天、进出世界/离开事件，可按需订阅 |
| 健康探针 | `GET /health` | 唯一免鉴权端点，判断"没起来"还是"鉴权坏了" |

默认 `127.0.0.1:38121`，除 `/health` 外全部要求 `Authorization: Bearer <token>`。
token 在 `config/mcagent.json` 里，首次启动自动生成。

## 为什么不用 Gradle

这套构建直接拿启动器已经下载好的 jar 当 classpath：

```
%APPDATA%\PrismLauncher\libraries\net\neoforged\neoforge\<ver>\neoforge-<ver>-client.jar   # 1561 个 NeoForge 补丁类
%APPDATA%\PrismLauncher\libraries\net\minecraft\client\...\client-...-srg.jar              # 7936 个全量 mojmap 类
%APPDATA%\PrismLauncher\libraries\net\neoforged\neoforge\<ver>\neoforge-<ver>-universal.jar # NeoForge API
+ fancymodloader/loader、bus、coremods、mergetool、authlib、gson、brigadier、datafixerupper
```

所以：**不需要联网、不需要 Gradle、不需要 NeoGradle/ModDevGradle**。
NeoForge 1.21.1 生产环境就是 mojmap 方法名（`getBlockState`、`sendSystemMessage`），
不涉及 SRG remap。

几个必须知道的坑（都在 `build.ps1` 里处理了）：

- `@Mod` / `IEventBus` 不在 NeoForge 的三个 jar 里，在 `fancymodloader/loader` 和 `bus`
- `net.neoforged.api.distmarker.Dist` 只有 `mergetool-*-api.jar` 里有一份
- `Commands.performPrefixedCommand` 是**实例方法**：`server.getCommands().performPrefixedCommand(...)`
- `CommandSourceStack` 的公开构造器接受一个 `CommandSource`，这是捕获命令输出的唯一办法

## 构建 / 安装

```powershell
cd E:\Minecraft\mcagent
.\build.ps1              # 编译 + 打包 + 装进实例 mods/
.\build.ps1 -NoInstall   # 只编译
.\build.ps1 -Instance 'D:\other\.minecraft'   # 装到别的实例
```

改完 mod **必须重启游戏**，FML 没有热重载。

## 配置

`<game_dir>/config/mcagent.json`

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉就不启动 HTTP |
| `host` | `127.0.0.1` | 想暴露到局域网才改，别乱改 |
| `port` | `38121` | |
| `token` | 自动生成 | 48 位 hex |
| `allowCommands` | `true` | 是否允许 `mc_command` / WS `exec` |
| `allowWrite` | `false` | 世界写入（放方块等）单独一道开关 |
| `maxOutputChars` | `16000` | 命令输出截断长度 |
| `greetOnJoin` | `true` | 进世界时在聊天栏打印一行桥接就绪 |
| `agentName` | `Deepseek` | 聊天显示名，格式 `<Deepseek> 内容` |

## MCP 工具

读状态：

`mc_status` `mc_blocks` `mc_block` `mc_block_entity` `mc_entities` `mc_inventory`
`mc_logs` `mc_screenshot`

配方 / 注册表：

`mc_recipe` `mc_tags`

任务书（需要 FTB Quests，不存在则不注册这两个工具）：

`mc_quests` `mc_quest`

操作：

`mc_command` `mc_chat` `mc_chat_read` `mc_highlight` `mc_wait`
`mc_fill` `mc_undo` `mc_history`
`mc_watch` `mc_watch_list`
`mc_set_block`

- 所有游戏状态读取都会跳到**服务端主线程**执行，HTTP 线程不会碰世界数据
- `mc_screenshot` 跳到**客户端渲染线程**，用 `Screenshot.takeScreenshot` + `NativeImage.asByteArray()`（PNG）
- `mc_wait` 用来和游戏节奏同步：等 N tick、或等玩家说一句匹配正则的话
- `mc_fill` 走 `/fill`（完整的 blockstate 语法），但会先记下区域内每个方块的旧状态和方块实体 NBT，
  所以 `mc_undo` 能把机器连同里面的东西一起还原。只记录**自己改的**区域，不碰玩家改动。
- `mc_highlight` 的描边由服务端每 8 tick 重发一次，所以 `seconds` 是真的持续秒数
- `mc_watch` 登记一个方块实体，NBT 一变就推 WS 事件（需要客户端订阅 `blocks` topic）

## WebSocket 协议

客户端 → 服务端：

```json
{"type":"say","text":"..."}              // 以 agent 身份说话
{"type":"exec","command":"..."}          // 执行命令（需 allowCommands）
{"type":"state"}                          // 主动要一次完整状态
{"type":"sub","topics":["blocks"]}        // 订阅额外主题
{"type":"unsub","topics":["blocks"]}
{"type":"ping"}
```

服务端 → 客户端：

```json
{"type":"hello","state":{...},"topics":["chat","events"]}
{"type":"chat","player":"...","text":"...","system":false}
{"type":"event","kind":"player_join|player_leave|server_started|server_stopping","data":{...}}
{"type":"watcher","data":{"label":"...","pos":"x,y,z","status":"errored","before":"...","after":"..."}}
{"type":"block_place","data":{"pos":"x,y,z","block":"...","dimension":"..."}}
{"type":"block_break","data":{"pos":"x,y,z","block":"...","player":"..."}}
{"type":"exec_result","command":"...","output":"..."}
```

**主题**：`chat`（默认）与 `events`（默认）始终订阅；`blocks` 需要显式 `sub`，
里面是方块实体变更、方块放置/破坏——默认关掉是故意的，否则你每放一块砖都会推一条。

`block_place` / `block_break` 只推**带方块实体**的方块（机器、容器），
普通建筑方块不推。

`tools/ws-watch.mjs` 是个零依赖客户端，`--exit-on chat` 模式下玩家一说话进程就退出，
适合拿"进程结束"当信号唤醒 agent。

## 客户端 / 服务端分离

`net.minecraft.client.*` 只在 `client/ClientApiImpl.java` 里出现，通过
`Class.forName` + dist 判断反射加载，所以这个 mod 在专用服务器上也能加载，
只是 `mc_screenshot` 会返回"客户端专用"。

## 已知限制（v0.2）

- MCP 无状态：不发 `Mcp-Session-Id`，不支持 server→client 的 sampling/roots
- 未实现 HTTP 压缩、TLS（只绑回环，够用）
- `mc_blocks` 单次上限 8000 行；`mc_fill` 单次上限 4096 格（受撤销内存限制）
- 撤销栈只保留最近 16 次操作，且**只覆盖 agent 通过 `mc_fill` 做的改动**
- `mc_recipe` 遍历的是原版 `RecipeManager`，对自定义 `RecipeType`
  （如 Custom Machinery）只能统计到该类型的配方数量，读不出其专有字段
- WebSocket 无消息队列：游戏线程直接写 socket，极端卡顿时会短暂阻塞
- 截图抓的是上一帧的主渲染目标，个别驱动下可能出现半帧

## 安全

这是游戏内 RCE 级别的能力。默认值已经按"单人测试存档"设计：
只绑回环、强制 token、写操作默认关。**不要**把 `host` 改成 `0.0.0.0`
放到公网，也不要关认证。
