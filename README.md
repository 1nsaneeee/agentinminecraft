# Agent In Minecraft

> **⚠ 目前只支持 DeepSeek Harness。**  
> **Client and Server**
>
> mod 本身暴露的是标准 MCP + WebSocket，理论上任何 MCP 客户端都能连；
> 但游戏内行为依赖智能体的插件系统，所以
> 这些**配套设施只针对 DeepSeek Harness 实现**。换别的 harness 需要自己写对接层。

*把一个正在运行的 Minecraft 客户端/整合包，变成 AI agent 可以直接操作的进程*

从 0.3 起，它还能给 agent 一具**会走会挖会打的身体**——一个没有客户端连接、
但服务端和其他玩家都当成正常玩家的 `FakePlayer`。

## 特色功能

- 实时监听玩家对话，实时做出响应
- 通过游戏内暴露的接口实现正常玩家的操作
- 强大的 work 模式，随心所欲地改造世界
- 一个永远爱用户的心

## QuickStart

### 路线一：全权交给 AI（懒人）

装好 **DeepSeek Harness**，然后把下面这句话连同链接一起发给它的 agent：

```text
依照 https://github.com/1nsaneeee/agentinminecraft/blob/main/docs/AI-INSTALL.md
执行本 Mod 的安装步骤。遇到不确定的路径先问我，不要覆盖已有配置。
```

它会自己下载、自己放文件、自己改 profile、自己装 skill，最后告诉你还要不要重启。
步骤和约束都写在 [docs/AI-INSTALL.md](docs/AI-INSTALL.md) 里——那份文件本身就是一段
可执行提示词，你不需要自己抄任何路径。

**唯一必须你手动做的只有一步：重启游戏。**

### 路线二：自己装（三步）

1. 把 `AgentInMinecraft-0.3.0.jar` 放进实例的 `mods/`
2. 启动游戏，进一个世界——`config/agentinminecraft.json` 会自动生成，token 自动写好
3. 浏览器打开 `http://127.0.0.1:38121/health`，看到 `{"ok":true,...}` 就成了

想让 agent 在游戏里说话就能被唤醒，再装 `harness/` 那一半，见
[harness/INSTALL.md](harness/INSTALL.md)。

### 第一次用它

```text
# 常用 MCP 工具
mc_status                 看游戏状态、TPS、玩家位置
mc_player_spawn           给 agent 造一具身体
mc_player_walk            走到某个坐标（自动寻路、会自动跳坑）
mc_player_break/place     挖 / 放
mc_player_attack          打最近的敌对生物

# 游戏内命令
/agent work               work 模式：能改世界（需要 allowWrite=true）
/agent chat               聊天模式：只动手，不直接改世界
/agent config             查看全部配置（token 打码）
```

## 它提供什么

| 通道 | 端点 | 用途 |
|---|---|---|
| MCP (Streamable HTTP) | `POST /mcp` | 35 个工具：读取游戏状态，以及操作一具真实的身体 |
| WebSocket | `GET /ws` | 玩家实时聊天、进出世界事件、方块事件，可按需订阅 |
| 健康探针 | `GET /health` | 唯一免鉴权端点，用来区分"没起来"和"鉴权坏了" |

默认监听 `127.0.0.1:38121`，除 `/health` 外全部要求 `Authorization: Bearer <token>`。
token 在 `config/agentinminecraft.json`，首次启动自动生成。

## Agent 的身体

| 能力 | 工具 | 怎么实现的 |
|---|---|---|
| 走 | `mc_player_walk` / `halt` | 带跳跃的 A* 规划 + 原版物理（`xxa/zza` + 跳跃位） |
| 挖 | `mc_player_break` | 按 `getDestroyProgress` 算真实时长，到点 `destroyBlock` |
| 放 | `mc_player_place` | `useItemOn`，走完整交互路径 |
| 用 | `mc_player_use` | `useItem`：吃、喝、扔、射 |
| 打 | `mc_player_attack` | `player.attack`，正常伤害计算 |
| 传送 | `mc_player_teleport` | 开发工具，**chat 模式拒绝** |

内置一个寻路算法，AI 输入目标坐标即可自动导航至目的地。

皮肤可换：`skinFile` 指向一张 64×64 png 就能替换，`skinPlayer` 是套用名单
（逗号分隔，留空表示跟随 `playerName`）。皮肤是**客户端行为**——只有装了同一个
mod 的客户端才看得到，其他人看到的是默认皮肤。

## 两种模式

```
/agent work    成为创世神
/agent chat    游戏搭子，会吐槽玩家的毒舌美少女
```

- `work`：所有工具可用。**改世界还需要 `allowWrite=true`**（默认 `false`），
  直接用 `/agent config set allowWrite true` 打开
- `chat`：保留身体的动作（走、挖、放、用、打），拒绝 `mc_set_block` / `mc_fill` /
  `mc_command` / `mc_undo` / 传送，以及 WebSocket 的 `exec` 帧

**agent 自己切不回 `work`**，要真人输入。能自己解开的限制不叫限制。

## 游戏内命令

| 命令 | 作用 |
|---|---|
| `/agent work` `/agent chat` | 切换模式，并通知外部 harness |
| `/agent new` `/agent session [n]` | 新建会话 / 切换会话 |
| `/agent config` | 打印全部配置（token 打码） |
| `/agent config set <key> <value>` | 改一项并写盘；空值、非法值直接拒绝 |
| `/agent config reload` | 重读配置文件，不用重启 |

`/work` `/chat` `/new` `/session` 是等价别名。

改 `host` / `port` / `enabled` 会写进文件，但**要重启才生效**——端口在启动时已经绑定了。

## 配置

`<game_dir>/config/agentinminecraft.json`

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉就不启动 HTTP（重启生效） |
| `host` | `127.0.0.1` | 想暴露到局域网才改，别乱改 |
| `port` | `38121` | 绑定端口（重启生效） |
| `token` | 自动生成 | 48 位 hex，**只能改文件** |
| `allowCommands` | `true` | 是否允许 `mc_command` / WS `exec` |
| `allowWrite` | `false` | 世界写入与传送的独立开关 |
| `maxOutputChars` | `16000` | 命令输出截断长度 |
| `maxLogLines` | `500` | `mc_logs` 单次上限 |
| `greetOnJoin` | `true` | 进世界时在聊天栏打印桥接就绪 |
| `playerName` | `Deepseek` | **身体的名字**：生成玩家、皮肤匹配、Tab 列表 |
| `agentName` | `Deepseek` | **没有身体时**发言用的署名 |
| `skinFile` | 空 | 皮肤 png，相对路径按游戏目录解析 |
| `skinPlayer` | 空 | 套皮肤的玩家名，逗号分隔；空表示跟随 `playerName` |
| `skinModel` | `auto` | `auto` / `wide` / `slim`，auto 按像素空洞数判断 |

## 构建 / 安装 / 验证

```powershell
.\build.ps1                    # 编译 + 打包 + 装进实例 mods/
.\build.ps1 -NoInstall         # 只编译
.\build.ps1 -Instance 'D:\other\.minecraft'   # 装到别的实例
```

改完 mod **必须重启游戏**，FML 没有热重载。

```powershell
.\tools\test.ps1               # 传输层回归，9 条，不连接游戏
.\tools\build-live-tests.ps1   # 构建可选 QA mod（build/mcagent-qa.jar）
```

QA mod 只允许在目录名为 `mcagent-review-*` 的存档副本里运行 `/mcagentqa run`。

## 配套设施

发布包里除 jar 之外还有 `harness/`，是**编排侧**那一半。没有它也能用
（任何 MCP 客户端都能连），装上之后才能在游戏里说话直接唤醒 agent：

| 文件 | 作用 |
|---|---|
| `harness/INSTALL.md` | 两侧完整的安装、验证、卸载步骤 |
| `harness/dsh-mc-chat/` | DSH 插件：常驻 WS 连接，游戏内发言唤醒 agent，转发模式/会话指令 |
| `harness/profile-patch-snippet.yml` | 粘进 `cordis.patch.yml` 的片段，**不含绝对路径** |
| `harness/skills/minecraft-agent-bridge/SKILL.md` | 给 agent 的工具使用说明 |

插件查找 `config/agentinminecraft.json` 的顺序：片段里的 `configPath` →
环境变量 `MCAGENT_CONFIG` → 当前工作目录下的 `config/agentinminecraft.json`。

## MCP 工具（35 个）

**读取**：`mc_status` `mc_blocks` `mc_block` `mc_block_entity` `mc_entities`
`mc_inventory` `mc_logs` `mc_screenshot`

**配方 / 注册表**：`mc_recipe` `mc_tags`

**任务书**（需要 FTB Quests，否则不注册）：`mc_quests` `mc_quest`

**操作**：`mc_command` `mc_chat` `mc_chat_read` `mc_highlight` `mc_wait`
`mc_fill` `mc_undo` `mc_history` `mc_watch` `mc_watch_list` `mc_set_block`

**身体**：`mc_player_spawn` `mc_player_despawn` `mc_player_list` `mc_player_look`
`mc_player_hold` `mc_player_walk` `mc_player_halt` `mc_player_teleport`
`mc_player_break` `mc_player_place` `mc_player_use` `mc_player_attack`

几条要点：

- 所有世界读取都跳到**服务端主线程**执行，HTTP 线程不碰世界数据
- `mc_screenshot` 跳到**客户端渲染线程**，抓主渲染目标
- `mc_wait` 用来和游戏节奏同步：等 N tick，或等玩家说一句匹配正则的话
- `mc_fill` 走 `/fill`（完整 blockstate 语法），但会先记录区域内每个方块的旧状态和
  方块实体 NBT，所以 `mc_undo` 能把机器连同里面的东西一起还原
- `mc_undo` 只撤销 **agent 自己**通过 `mc_fill` 做的改动，栈深 16
- `mc_chat` 在有身体时**走真实玩家聊天**，名字来自玩家 profile；没有身体时才退回一条
  `<agentName>` 系统行

## WebSocket 协议

客户端 → 服务端：

```json
{"type":"say","text":"..."}              // 以 agent 的身份说话
{"type":"say_json","component":{...}}    // 发一个可点击的 JSON 组件
{"type":"exec","command":"..."}          // 执行命令（需 allowCommands，chat 模式拒绝）
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

**主题**：`chat` 与 `events` 默认订阅；`blocks` 需要显式 `sub`。`block_place` /
`block_break` 只推**带方块实体**的方块（机器、容器）。

`tools/ws-watch.mjs` 是零依赖客户端，`--exit-on chat` 模式下一收到玩家发言就退出。

## 为什么不用 Gradle

构建直接拿启动器已下载的 jar 当 classpath，**不需要联网、不需要 Gradle、不需要
NeoGradle/ModDevGradle**。NeoForge 1.21.1 生产环境就是 mojmap 方法名，不涉及 SRG remap。

几个必须知道的坑（`build.ps1` 里都处理了）：

- `@Mod` / `IEventBus` 不在 NeoForge 的三个 jar 里，在 `fancymodloader/loader` 和 `bus`
- `net.neoforged.api.distmarker.Dist` 只有 `mergetool-*-api.jar` 里有一份
- `Commands.performPrefixedCommand` 是**实例方法**
- `CommandSourceStack` 的公开构造器接受一个 `CommandSource`，这是捕获命令输出的唯一办法
- 编译要加 `-proc:none`：sponge-mixin 自带的注解处理器需要 ASM，会把 javac 搞崩

## 已知限制

- MCP 无状态：不发 `Mcp-Session-Id`，不支持 sampling/roots
- 未实现 HTTP 压缩、TLS（只绑回环，够用）
- `mc_blocks` 单次上限 8000 行；`mc_fill` 单次上限 4096 格
- 撤销栈 16 层，且只覆盖 `mc_fill` 的改动
- `mc_recipe` 对自定义 `RecipeType` 只能统计数量
- WebSocket 无发送队列，极端卡顿时会短暂阻塞
- 截图抓的是上一帧主渲染目标，个别驱动下可能半帧
- **身体需要 mod 自己驱动 tick**：世界不为这具无客户端玩家调用 `doTick()`，
  物理和移动由 mod 每 tick 手动驱动一次；服务端卡住时身体也停
- **挖掘是自己计时的**：裂纹进度由 mod 发送，不走客户端的确认链路
- **导航是局部的**：包围盒 ±10 格、6000 节点，需要绕远路时退回直线 steering
- **皮肤是客户端行为**：只有装了同一个 mod 且在 `skinPlayer` 名单里的人看得到

## 安全

这是游戏内 RCE 级别的能力。默认值按"单人测试存档"设计：只绑回环、强制 token、
写操作默认关。**不要**把 `host` 改成 `0.0.0.0` 放到公网，也不要关认证。

`agentName` 以明文出现在聊天里，`token` 不会——`/agent config` 只显示前 4 位和后 4 位。
