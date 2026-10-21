# MCP 接口参考

> 这份文档面向 **agent / 脚本作者**。想了解这个 mod 是什么、要不要装，看 [../README.md](../README.md)。

## 端点

| 通道 | 端点 | 鉴权 |
|---|---|---|
| MCP (Streamable HTTP) | `POST /mcp` | `Authorization: Bearer <token>` + `Accept: application/json, text/event-stream` |
| WebSocket | `GET /ws` | `Authorization: Bearer <token>` |
| 健康探针 | `GET /health` | **免鉴权** |

默认 `127.0.0.1:38121`。token 在 `config/agentinminecraft.json`，48 位 hex。

`/health` 是唯一免鉴权端点，专门用来区分两种失败：

- 连不上 → 游戏没起来 / 端口被占
- 连上但 MCP 返回 401 → token 不对

MCP 无状态：不发 `Mcp-Session-Id`。请求体上限 8 MiB（含 chunked 累计）。

## 三级模式闸门

调用任何会改变游戏状态的工具前，先理解这三层，**否则会收到看似莫名其妙的拒绝**：

| 闸门 | 位置 | 拒绝时的消息 |
|---|---|---|
| `enabled=false` | 启动时 | 根本连不上（端口不监听） |
| `allowWrite=false` | 世界写入类工具 | `world writes are disabled; set allowWrite=true ...` |
| `allowCommands=false` | `mc_command`、WS `exec` | `commands disabled by config (allowCommands=false)` |
| chat 模式 | 见下表 | `chat mode does not edit the world directly ...` |

chat 模式（游戏内 `/agent chat`）**拒绝**：

```
mc_set_block   mc_fill   mc_command   mc_undo   mc_player_teleport   WS {"type":"exec"}
```

chat 模式**允许**：所有读取工具、`mc_player_walk/halt/look/hold/spawn/despawn`、
`mc_player_break/place/use/attack`、`mc_chat`。

**agent 无法自行退出 chat 模式**——只有人类能在游戏内输入 `/agent work`。

## 工具清单（35）

### 读取

| 工具 | 作用 | 关键参数 |
|---|---|---|
| `mc_status` | 版本、TPS、维度、玩家列表、桥接状态（含 `mode`） | — |
| `mc_blocks` | 区域方块扫描，上限 8000 行 | `x1..z2`、`includeAir` |
| `mc_block` | 单个方块 | `x,y,z` |
| `mc_block_entity` | 方块实体 NBT（机器、容器） | `x,y,z` |
| `mc_entities` | 附近实体：类型、位置、血量 | `radius`、`type`、`player` |
| `mc_inventory` | 玩家背包 | `player` |
| `mc_logs` | `latest.log` 过滤 | `filter`、`lines` |
| `mc_screenshot` | 抓主渲染目标（**仅客户端**） | `save` |

### 注册表

| 工具 | 作用 |
|---|---|
| `mc_recipe` | 某物品由哪些配方产出 / 被哪些消耗 |
| `mc_tags` | 物品/方块的 tag 归属 |

### 任务书（需 FTB Quests，否则不注册）

`mc_quests`（章节树 + 完成状态）、`mc_quest`（单任务详情）

### 操作

| 工具 | 作用 | 注意 |
|---|---|---|
| `mc_command` | 执行命令并捕获输出 | 权限默认取玩家 OP 等级，最低 2 |
| `mc_chat` | 以 agent 身份发言 | 有身体时走真实玩家聊天，否则回退系统行 |
| `mc_chat_read` | 读最近聊天 | 可配 `mc_wait --untilChat` |
| `mc_highlight` | 粒子描边 | 服务端每 8 tick 重发，`seconds` 是真秒数 |
| `mc_wait` | 等 N tick 或等一句聊天 | 超时返回 `reason:"timeout"` |
| `mc_fill` | 区域填充 | 上限 4096 格，填充前记录旧状态与方块实体 NBT |
| `mc_undo` | 回滚 | 栈深 16，只覆盖 `mc_fill` 的改动 |
| `mc_history` | 列出可撤销操作 | — |
| `mc_watch` / `mc_watch_list` | 监视方块实体 NBT 变化 | 变化推 WS `watcher` 事件 |
| `mc_set_block` | 单方块设置 | 不进撤销栈 |

### 身体

| 工具 | 作用 | 关键参数 |
|---|---|---|
| `mc_player_spawn` | 生成一具玩家（名字来自 `playerName`，**参数里的 name 已忽略**） | `x,y,z`、`yaw`、`pitch` |
| `mc_player_despawn` | 移除，并停走、取消挖掘 | `name` |
| `mc_player_list` | 位置、血量、手持、是否在走 | — |
| `mc_player_walk` | 自动寻路走到坐标 | `x,y,z`、`sprint` |
| `mc_player_halt` | 停走 | `name` |
| `mc_player_look` | 转向 | `yaw`、`pitch` |
| `mc_player_hold` | 切换快捷栏槽位 | `slot` |
| `mc_player_teleport` | 传送（**chat 模式拒绝**） | `x,y,z` |
| `mc_player_break` | 挖掘，按真实时长 | `x,y,z`、`face` |
| `mc_player_place` | 右键方块面：放方块/开箱子/拉杆 | `x,y,z`、`face` |
| `mc_player_use` | 右键空气：吃、喝、扔、射 | `name` |
| `mc_player_attack` | 攻击最近的非玩家活体，或指定 `entityId` | `entityId`、`radius` |

`mc_player_walk` 的返回值会带路线摘要，例如 `(8 hops, 2 jump(s))`。
`no route found, steering straight` 表示没规划出路线，正在直冲——通常意味着目标不可达。

## WebSocket 协议

客户端 → 服务端：

```json
{"type":"say","text":"..."}
{"type":"say_json","component":{...}}
{"type":"exec","command":"..."}
{"type":"state"}
{"type":"sub","topics":["blocks"]}
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
{"type":"agent_cmd","cmd":"work|chat|new|session","arg":"...","mode":"work|chat","by":"..."}
```

**主题**：`chat` 与 `events` 默认订阅；`blocks` 需要显式 `sub`。
`block_place` / `block_break` 只推**带方块实体**的方块（机器、容器）。

## 写给 agent 的几条注意事项

1. **所有世界读取都跳服务端主线程**。不要高频轮询 `mc_blocks`，单次 8000 行是有代价的。
2. **`mc_wait` 是同步的正确方式**。挖方块要等进度、玩家反应要等聊天，用 `mc_wait` 而不是自己睡眠。
3. **改写世界前先备份**：`mc_fill` 会自动记录，但 `mc_set_block` 不进撤销栈。
4. **拒绝不是错误**。看到 chat 模式的消息，说明人类收紧了权限，不要反复重试同一调用。
5. **`mc_screenshot` 可能不可用**（专用服务器）。先查 `mc_status.dist`。
6. **身体是真实玩家**：它会掉血、会被其他模组影响、会踩坏作物。不要把它当无实体代理用。
7. **地面高度是 `玩家 y - 1`，不是 `玩家 y`**。`mc_status` 报的是脚的位置，也就是下面那块方块的**顶面**：
   在 `y=-60` 放地板，实际会比草地高一格，房子会悬空。
   建造前先 `mc_block` 查一下脚下那格的 y，用它当地板层。
