## 0.3.0 — The agent gets a body

**只支持 DeepSeek Harness。** MCP 是标准的，任何 MCP 客户端都能连；但游戏内唤醒、
`/agent` 会话与模式转发、chat 模式语气注入这些配套能力只针对 DeepSeek Harness 实现。

### 新增：一具真正能干活的身体

- `mc_player_spawn` / `despawn` / `list` / `look` / `hold`
- `mc_player_walk` / `halt` —— 带跳跃的 A* 导航，能上台阶、跨 2~3 格坑
- `mc_player_break` / `place` / `use` / `attack` —— 真挖、真放、真用、真打
- `mc_player_teleport` —— 开发工具，chat 模式拒绝
- 本地皮肤：`skinFile` / `skinPlayer` / `skinModel`

### 新增：两种模式

`/agent work` 与 `/agent chat`。chat 模式在工具入口强制，拒绝直接改世界与传送，
WebSocket 的 `exec` 也拒绝。agent 自己无法切回 work。

### 新增：配置管理

`/agent config`、`/agent config set <key> <value>`、`/agent config reload`。
`set` 只写那一个键（增量），整体保存先读盘再合并；空值直接拒绝。
token 只显示前 4 位和后 4 位。

### 修复（来自外部代码审查）

- `mc_undo` 缺少模式与写入开关检查
- WebSocket `exec` 可绕过 chat 模式
- `mc_fill` 的撤销快照与 `/fill` 执行的维度可能不一致
- 退出存档未清理假玩家、撤销历史、监视器
- 挖掘直接调用 `destroyBlock` 跳过了出生点保护与 `LeftClickBlock` 钩子
- chunked 请求体可绕过 8 MiB 上限
- 重复 tick：世界已调用 `ServerPlayer.tick()`，只补 `doTick()`

### 其他

- `mc_player_move` 更名为 `mc_player_teleport`
- `/agent config` 打印真实键名，并支持 `player` → `playerName` 等别名
- 新增传输层回归测试（`tools/test.ps1`，9 条）
