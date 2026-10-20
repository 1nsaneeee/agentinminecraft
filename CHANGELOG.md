# mcagent 变更记录

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
