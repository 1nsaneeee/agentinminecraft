# mcagent 变更记录

## 未发布 — 2026-10-21

- 修复 `mc_fill` 的快照与命令执行维度不一致；`mc_undo` 补齐 chat / allowWrite 检查
- WebSocket `exec` 与主线程命令执行都拒绝 chat 模式
- chunked HTTP 请求体与 Content-Length 共用 8 MiB 上限，在分配内存前检查单块和累计大小
- 停服清理 FakePlayer、动作、撤销记录、watcher 与 highlight，拒绝排队中的旧世界请求
- 身体挖掘补齐 `mayInteract` 与 `LeftClickBlock` 取消检查
- 移除重复的 `ServerPlayer.tick()`；世界负责该调用，Mod 只补连接缺失的 `doTick()`
- 新增隔离传输回归与可选的真实游戏测试 Mod，后者只能操作存档副本

## v0.3 — 2026-10-20

> **改名**：发布用名字定为 **Agent In Minecraft**（mod id `agentinminecraft`，jar
> `AgentInMinecraft-0.3.0.jar`）。配置文件随之改为 `config/agentinminecraft.json`，
> 旧文件会被自动复制迁移，token 和开关不会丢。Java 包名仍是 `dev.mcagent`，
> 日志前缀仍是 `[mcagent]`——那些是内部标识，不在用户视野里。


Agent 有了一具能真正干活的躯体，以及它需要的全部底层修复。

工具（23 → 35）：

- `mc_player_spawn` / `despawn` / `list` / `look` / `hold`：生成一具无客户端的
  `ServerPlayer`，真出现在玩家列表、真拿物品、真占一块地
- `mc_player_walk` / `halt`：走。输入 `xxa/zza` + 跳跃位，物理交给原版
- `mc_player_teleport`：传送。**开发工具，chat 模式下拒绝**（名字与 `walk` 刻意分开）
- `mc_player_break` / `place` / `use` / `attack`：真挖、真放、真用、真打

导航：`AgentNav` 在可站立格子上做带跳的 A*（走 / 上台阶 / 掉落 / 跨 2~3 格坑），
`AgentMovement` 逐跳执行，跳的边提前起跳。实测三段台阶 + 两处坑规划为
`8 hops, 2 jumps`，落在平台 `y=-60` 并停住。

模式：`/agent work|chat`。chat 保留身体动作，拒绝直接改世界
（`mc_set_block` / `mc_fill` / `mc_command` / `mc_undo`）与传送，**在工具入口强制**。

配置：`/agent config`、`config set <key> <value>`、`config reload`。
`set` 只写那一个键（增量），整体保存先读盘再合并；空值直接拒绝。

皮肤：`skinPlayer` 支持名字列表；`playerName` 为空时回落 `agentName`；
空白配置会在三处被拦下，不再静默失效。

### 这一版真正难的地方（每一条都是具体的坑）

- 客户端连接缺失的物理 tick 由 `AgentMovement` 补足；原先同时手动调用 `tick()` 的
  解释有误，世界本身已调用 `ServerPlayer.tick()`，重复调用已在后续修复中移除
- 挖掘按 `getDestroyProgress` 算时间，再调用 `destroyBlock`；后续审查发现后者不包含
  包入口的全部交互检查，现已补齐 `mayInteract` 与 `LeftClickBlock`
- 假连接的 `awaitingPositionFromClient` 永远等不到确认，`resetPosition()` 每 tick
  把身体拽回传送点，同时中断挖掘。传送后手动清空该状态
- 离线玩家 UUID 要按 `OfflinePlayer:<name>` 现算，客户端才能在 `createSkinLookup` 里匹配
- 第二层皮肤需要 `modelCustomisation = 0x7F`，`createInformation.createDefault()` 给的是 0
- 攻击曾经误伤玩家：`mc_player_attack` 按"最近活体"选目标，现在排除玩家

### 其它

- `mc_player_move` 更名为 `mc_player_teleport`，名字与实际行为一致
- 删除三处死代码（`isBreaking` / `abortBreak` / `holdsPlaceable`），
  `cancelBreak` 接入"走远则中止挖掘"
- 仓库开始用 git 管理，`.gitattributes` 锁 LF，避免 checkout 翻掉全部行尾

## v0.2 — 2026-10-20

新工具（9 个，总数 14 → 23）：

- `mc_fill` 区域填充，走 `/fill` 保留完整 blockstate 语法；填充前记录区域旧状态与方块实体 NBT
- `mc_undo` 回滚最近的操作，方块实体内容一起还原
- `mc_history` 列出可撤销的操作
- `mc_watch` 监视方块实体，NBT 变化时推 WS 事件
- `mc_watch_list` 列出活跃监视器
- `mc_recipe` 查某物品由哪些配方产出 / 被哪些配方消耗，附配方类型直方图
- `mc_tags` 查物品/方块的 tag 归属
- `mc_quests` 读 FTB Quests 任务树 + 当前队伍完成状态（按章节分组，显示 [x]/[ ]）
- `mc_quest` 单个任务详情：任务类型、开始/完成时间、每个 task 的进度

WS 协议：

- 新增主题订阅 `sub` / `unsub`，主题为 `chat` / `events` / `blocks`
- `chat` 与 `events` 默认订阅，`blocks` 需要显式开启
- 新增事件：`watcher`、`block_place`、`block_break`（后两者只针对带方块实体的方块）

修复：

- `mc_highlight` 的 `seconds` 之前是假的：只发射一次粒子，实际存活约 1 秒。
  改为服务端每 8 tick 重发，直到到期；1 格大小的盒子步长加密到 0.25
- `mc_wait` 等够 tick 时错误地返回 `reason:"timeout"`，改为 `reason:"ticks"`
- `mc_blocks` 带 `includeAir` 时摘要仍写 "non-air blocks"，措辞改为按实际模式显示
- `mc_chat` / `mc_chat_read` 的发送者名从 `agent` 改为配置项 `agentName`（默认 `Deepseek`），
  聊天渲染成 `<Deepseek> 内容`；agent 自己的消息不写入 ChatLog，避免 `mc_wait` 自我触发

## v0.1 — 2026-10-20

首版：MCP Streamable HTTP 服务器 + WebSocket 实时通道，14 个工具，
纯 JDK 实现（不依赖 netty-codec-http / com.sun.net.httpserver）。

- 传输层：手写 HTTP/1.1（含 chunked 请求体）与 RFC 6455 帧
- 服务端访问统一跳主线程；截图跳客户端渲染线程
- 命令输出捕获靠自建 `CommandSource` 塞进 `CommandSourceStack` 公开构造器
- 构建：`javac` 直接对着启动器库缓存编译，不需要 Gradle、不需要联网
