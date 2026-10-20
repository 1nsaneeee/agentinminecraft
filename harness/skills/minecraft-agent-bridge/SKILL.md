---
name: minecraft-agent-bridge
description: 在 Minecraft（NeoForge 1.21.1）里与玩家实时人机协作。优先使用 mcagent mod 暴露的 MCP 工具（mcp__minecraft__*）读取玩家/方块/机器 NBT、执行命令、截图、粒子高亮、发聊天；用 WebSocket 通道接收玩家实时发言。当用户要求"进游戏帮我看/执行/调试"、调试整合包机器、需要游戏内主动操作时使用。没有 mod 时回退到 KubeJS ProbeJS 通道。
---

# Minecraft 游戏内人机协作桥

## 判断入口（按顺序）

1. **工具表里有 `mcp__minecraft__*`？** → 有，直接调用，别碰 KubeJS。这是主路径。
2. 没有工具、但游戏在跑 → 检查 `<game_dir>/config/agentinminecraft.json` 是否存在、端口是否在听、
   以及 `cordis.patch.yml` 里有没有 `mcp-minecraft` 块。见「排障」。
3. 游戏里有 KubeJS 没有 mod → 走文末「回退通道」。

`<game_dir>` 是实例的游戏目录，通常形如 `.../versions/<实例>/.minecraft`。

## 一、MCP 工具速查

工具名在 harness 里带前缀：`mcp__minecraft__mc_status` 这样。

| 工具 | 用途 | 关键点 |
|---|---|---|
| `mc_status` | 是否在读世界、tick、TPS、所有在线玩家的坐标/维度/血量/模式，附带客户端窗口信息 | 第一个该调的 |
| `mc_command` | 执行任意命令并**捕获全部输出** | `/data`、`/list`、`/setblock`、mod 命令都能用；`permission=4` 解 op 命令 |
| `mc_blocks` | 盒状区域方块快照，默认跳过空气 | 输出 `x,y,z minecraft:id[props]`，`limit` 默认 2000 |
| `mc_block` | 单个方块 + 有没有方块实体 | |
| `mc_block_entity` | **方块实体完整 NBT（SNBT）** | 机器内部数据：进度、能量、槽位物品、状态机 |
| `mc_entities` | 玩家周围实体 + 距离 + 血量 | 支持 `type` 子串过滤 |
| `mc_inventory` | 背包（快捷栏/主栏/盔甲/副手）+ 当前选中槽 | 物品 id、数量、中文名、耐久 |
| `mc_chat` | 往游戏聊天栏说话 | 游戏侧自动加 `<Deepseek> ` 前缀 |
| `mc_chat_read` | 读玩家最近说过的话 | 有 500 行环形缓冲，可 `filter` 正则；**不包含 agent 自己发的**（防止 `mc_wait` 自我触发） |
| `mc_highlight` | 粒子描边一个盒子，让玩家看见你说的位置 | 用 `END_ROD`，必须 `ServerLevel.sendParticles` |
| `mc_wait` | 阻塞等到 N tick 过去 / 玩家说了匹配正则的话 | **替代轮询**，是最关键的同步原语 |
| `mc_logs` | 读 `latest.log` 或 `logs/kubejs/server.log` 的尾部 | 支持正则过滤，用来抓崩溃和脚本报错 |
| `mc_screenshot` | 截游戏窗口成 PNG，作为图片内容返回 | 客户端专用；能看到 GUI、聊天栏、机器界面 |
| `mc_recipe` | 某物品由哪些配方产出 / 被哪些配方消耗 | 我确认过，返回带输入清单 + 配方类型直方图 |
| `mc_tags` | 物品/方块的 tag 归属、堆叠上限、是否可损坏 | 判断 tag 配方会不会接受它 |
| `mc_fill` | 区域填充（包 `/fill`，保留 blockstate 语法） | **需 `allowWrite=true`**，单次 ≤4096 格，填充前自动记录旧状态 |
| `mc_undo` | 回滚最近的操作，方块实体内容一起还原 | 只能撤销 agent 自己通过 `mc_fill` 做的改动 |
| `mc_history` | 列出可撤销的操作 | 栈深 16 |
| `mc_watch` | 监视方块实体，NBT 变化推 WS 事件 | 客户端要 `sub` 到 `blocks` topic |
| `mc_watch_list` | 列出活跃监视器 | |
| `mc_quests` | FTB Quests 任务树 + 当前队伍完成状态 | 按章节分组，`[x]`/`[ ]`，验收 playtest 必用 |
| `mc_quest` | 单个任务详情：task 进度、开始/完成时间 | |

读状态类工具都可以不传 `player`，默认取首个在线玩家。
依赖别的 mod 的工具会自动缺席：没有 FTB Quests 就没有 `mc_quests` / `mc_quest`。

## 二、实时双向通道（WebSocket）

MCP 是请求/响应，**没有服务端主动推给 agent 的能力**。玩家临时说的话靠 WS。

### 首选：dsh-mc-chat 插件（常驻，无需手动挂监听）

`~/.dsh/plugins/dsh-mc-chat` 是个常驻 harness 插件，进程内自己连
`ws://127.0.0.1:38121/ws`，收到玩家聊天就调 `agent.followup(createUserMessage(...))`
注入一条会话消息并起一轮——和定时提醒、后台任务结束通知同一个入口。

- 装配：`profiles/desktop/cordis.patch.yml` 的 `mc-chat` insert 块；
  `profiles/desktop/node_modules/dsh-mc-chat` 是指向插件目录的 junction
- **消息必须用 `source: { kind: 'user' }`**。写成 `{kind:'plugin', plugin:'xxx'}`
  会在起轮时抛 `format v4 message requires a producer-owned source kind`——
  第三方插件不是登记过的 producer，而该失败发生在 `followup` 之后，
  插件里的 try/catch 捕不到，只会看到「本轮运行失败」。玩家打字本来也是用户输入。
- token 和端口从 `<game_dir>/config/agentinminecraft.json` 读，不在补丁里复制凭据
- 只转发 `system:false` 的帧；agent 自己发的和 join/leave 提示都是 `system:true`，避免自激
- 改插件后 patch 文件是热重载触发器：动一下 insert 块（哪怕加一行注释），
  mod 日志里会出现一次 `ws disconnected` → `ws connected`，那就是新代码生效了
- **先看插件在不在**：`GET /health` 的 `wsClients` ≥ 1 说明有人连着（通常就是它）

### 兜底：一次性监听脚本

插件被卸载或加载失败时才用：

```powershell
# 玩家说下一句话时进程退出（退出即唤醒 agent）
$cfg = Get-Content '<game_dir>\config\mcagent.json' -Raw | ConvertFrom-Json
$env:MCAGENT_TOKEN = $cfg.token
node E:\Minecraft\mcagent\tools\ws-watch.mjs --port 38121 --exit-on chat --timeout 600000
# --exit-on event:player_join 可以在玩家进世界时唤醒
# --topics blocks 订阅机器事件
```

把它作为后台任务启动，玩家发言时任务结束、agent 收到通知。注意：

- **每轮结束前必须保证有一个监听在跑**。否则玩家在游戏里说话不会唤醒你——消息只留在
  环形缓冲里，直到你下次主动 `mc_chat_read`。这是这套桥最容易被忽略的失效模式。
- 同理，别为了"省资源"把监听撤掉：一个 node 进程的成本远低于一条消息被漏掉。
- 退出码 `0` = 收到 chat；`3` = 超时；`1` = 连接失败或超时（**别只看退出码**，看输出里
  最后一行 JSON 是 `{"type":"chat"}` 还是 `{"type":"timeout"}`）
- 事件类型：`hello`（含完整状态）、`chat`（`player` / `text` / `system`）、
  `event`（`server_started` / `server_stopping` / `player_join` / `player_leave`）
- `server_stopping` 出现意味着玩家正在退出游戏，别再发工具调用了
- 反向发送：`mc_chat` 工具，或 `--say "文本"`
- **游戏内聊天别用 emoji / ✅ 之类的符号**：Minecraft 默认字体缺字形，会显示成方框或
  怪异的伪字母（用户实测反馈"看着好诡异"）。用纯中文或 ASCII。

### 主题订阅

默认只推 `chat` + `events`。要机器事件必须显式订阅：

```powershell
node tools\ws-watch.mjs --token <t> --topics blocks --exit-on never
```

- `blocks` 主题：`watcher`（被监视方块实体的 NBT 变化，带 `status` / `before` / `after`）、
  `block_place` / `block_break`（**只推带方块实体的方块**，机器和容器，不推普通建筑）
- 客户端发 `{"type":"sub","topics":["blocks"]}` / `{"type":"unsub",...}`

`watcher` 事件是"机器从 idle 变 errored 立刻知道"的关键，比轮询 `mc_block_entity` 省事。

## 二·五、写世界的规矩

`mc_set_block` / `mc_fill` 都要求 `allowWrite=true`（默认关）。开之前先说明理由。

- 批量搭建用 `mc_fill`，不要循环 `mc_set_block`：前者走 `/fill` 且**自动把区域旧状态
  和方块实体 NBT 记进撤销栈**，后者不留痕。
- 动别人正在用的机器前先说坐标。做实验优先在空地搭新测试台。
- `mc_undo` 的语义是"撤销 agent 自己做的改动"，**不会**回滚玩家自己放的东西。
  如果它返回 `WARNING: N block entities could not be restored`，说明机器内容没还原，
  必须告诉用户，不能说"已还原"。

## 三、常用配方

| 目标 | 做法 |
|---|---|
| 玩家在哪 | `mc_status` → `players[]` |
| 读机器状态 | `mc_block_entity`，看 `status`、`craftingManager.cores[].phase`、槽位物品 |
| 改单个方块 | `mc_set_block`（需 `allowWrite=true`）或 `mc_command` 跑 `/setblock` |
| 搭测试台 | `mc_fill`（自动记撤销），出问题 `mc_undo` 撤回 |
| 查"为什么做不出来" | `mc_recipe <item>`：产物配方 + 消耗它的配方 + 配方类型直方图 |
| 看玩家做到哪了 | `mc_quests`（需要 FTB Quests），单个细看 `mc_quest <id>` |
| 盯机器报错 | `mc_watch` + WS 订阅 `blocks`，别再轮询 |
| 让玩家看到某个盒子 | `mc_highlight`，说位置不如画出来（真的持续 N 秒） |
| 等玩家反应 / 等机器转完 | `mc_wait`（`ticks` 或 `untilChat`） |
| 排查崩溃/脚本错 | `mc_logs` + `filter` |
| 看界面卡在哪 | `mc_screenshot` |

**机器快照示例**（Custom Machinery）：

```
status:"idle"  craftingManager:{cores:[{phase:"CONDITIONS"}], type:"custommachinery:machine"}
componentManager:{items:[{slotID:"output1", item:{id:"factory:dirt_nugget", count:63}}]}
```

`status` 是 `StringRepresentable` 小写值（`idle` / `running` / `errored`）。
`phase=CONDITIONS` 表示在查配方条件；`output` 槽堆到 64 会卡住整台机器。

## 四、构建与安装（改 mod 时）

项目：`E:\Minecraft\mcagent\`

```powershell
cd E:\Minecraft\mcagent
.\build.ps1            # 编译 + 打包 + 装进实例 mods
.\build.ps1 -NoInstall # 只编译
```

- **不需要 Gradle、不需要联网**。classpath 全部取自
  `%APPDATA%\PrismLauncher\libraries`（NeoForge `*-client.jar` 补丁类 +
  `client-*-srg.jar` 全量 mojmap + `*-universal.jar` API + loader/bus/coremods/mergetool/authlib）。
- 关键 classpath 陷阱：`@Mod` 和 `IEventBus` **不在** NeoForge 那三个 jar 里，
  而在 `fancymodloader/loader` 和 `bus`；`Dist` 只在 `mergetool-*-api.jar` 里有。
- **改完必须重启游戏**，mod 没有热重载。KubeJS 脚本才有热重载。
- 运行时是 mojmap 方法名（`getBlockState`、`sendSystemMessage`），不是 SRG。
- `Commands.performPrefixedCommand` 是**实例方法**：`server.getCommands().performPrefixedCommand(stack, cmd)`。
- 输出捕获靠自建 `CommandSource` 实现塞进 `CommandSourceStack` 的公开构造器。

## 五、排障

| 现象 | 查什么 |
|---|---|
| 工具调用失败/超时 | 游戏是否在运行；`Get-NetTCPConnection -LocalPort 38121` |
| 端口在听但连不上 | token 是否与 `config/agentinminecraft.json` 一致；header 必须是 `Bearer <token>` |
| 工具不存在 | `cordis.patch.yml` 的 `mcp-minecraft` 块是否被改动；改了会热重载 |
| 进了世界没收到问候 | 正常历史行为：问候已移到 `PlayerLoggedInEvent`，旧 jar 不会打 |
| 想不开 harness 直接验 | `E:\Minecraft\mcagent\tools\probe.ps1 [-Tool mc_status]` |

`GET /health` 不需要鉴权，用来判断"服务活着但鉴权坏了"还是"根本没起来"。

## 六、回退通道：KubeJS ProbeJS

没有 mcagent mod 时，KubeJS 自带的本地 HTTP 服务可以当 RPC：

```bash
curl -s -X POST "http://127.0.0.1:61423/api/probejs/run-command" \
  -H "Authorization: Bearer <kubejs/config/web_server.json 里的 auth>" \
  -H "Content-Type: application/json" -d '{"command":"data get entity @s Pos"}'
# → [{"type":"success","content":"..."}]
```

- `POST /api/reload/server` 热重载 server 脚本（204）
- `GET /api/probejs/identity` 免鉴权探活
- 缺 `Bearer ` 前缀 → 403；缺头 → 401

**KubeJS 2101 坑位**（回退时才需要）：

1. `player.level` 是属性，不是方法
2. `level.getBlockState(x,y,z)` 不可用，必须传 `BlockPos`
3. 事件回调里别声明叫 `server` 的变量（Rhino redeclaration）
4. 脚本加载两次，顶层用 `var`
5. `java.nio.file.Files` 被 class filter 拦，没有文件 IO
6. `String(enum)` 返回小写值
7. 原版 `/data get block` 对 Custom Machinery 会失败，用 `lvl.getBlock(pos).entityData`

## 七、安全边界

- 这条通道等于游戏内 RCE：只用于单人存档 / 本地测试，不用于生产服务器。
- 默认只绑 `127.0.0.1`、要 token；**不要**为了省事关掉认证或暴露公网。
- `allowCommands` / `allowWrite` 是两道独立开关，写方块默认关。
- 改方块、拆机器、动玩家物品前先说一声，优先可逆操作。
- 临时诊断脚本统一 `_` 前缀，任务结束清理。
