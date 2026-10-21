## 0.3.1 — 修三个实操中暴露的问题

在游戏里连续玩下来暴露的，不是测试能测出来的那类问题。

### 修复：对角台阶走不上去

寻路只有四个正方向，所以「一格远、一格高」的对角台阶在图上根本不存在——
它算出「无路线」，退回直线冲刺，然后顶在第一级台阶上不动。
现在补上四条对角边，并遵守原版的墙角规则（两侧格都得空）。

### 修复：进水里不动

陆地上按住「跳」没意义，水里那个标志就是上浮键。之前进水里从没按过它，
身体沉底然后站着不动。跟路径走和无路线直冲两条路都补上了。

### 修复：长路线搜不到路

A* 的节点预算固定 6000、搜索框固定 10 格，35 格穿过村庄的路线会同时撞上这两条。
现在预算 30000，搜索框按距离放大（10 + 距离/3，上限 40）。

### 新增：闲下来会动

站着一动不动像道具。现在每隔 5~14 秒做一个小动作：回头看你、东张西望、
耸肩膀、蹲一下、原地跳。命令一到立刻让位。

### 新增：诊断日志

上台阶失败的现场看不到原因，现在尝试上台阶时每 5 tick 打一行
`step probe`（dy / 距离 / onGround / 碰撞 / vy / jump）。

## 0.3.0 — Agent In Minecraft

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
