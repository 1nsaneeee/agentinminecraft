# MCP 接入与世界操作

## 会话

服务接受带 Bearer 凭据的 `POST /mcp`。客户端用 JSON-RPC `initialize` 建立会话，保存响应头 `Mcp-Session-Id`，后续请求带上它。发送 `notifications/initialized` 后通过 `tools/list` 获取本版本的工具和参数。退出时使用带相同凭据及会话头的 `DELETE /mcp`。工具调用返回的 `isError` 和文本包含实际执行结果。

多个客户端可以同时读取。第一个需要独占操作的请求取得操作权，所有者调用工具会续期。写会话空闲约 120 秒会撤销，编辑作业另有 15 秒 keepalive。停止、世界切换、配置重载或写会话关闭会撤销旧操作。收到 404 后重新初始化，重新检查世界、任务和实际方块，再根据玩家委托决定如何继续。普通会话空闲 30 分钟后过期。

## 从玩家的视角调查

玩家对准对应目标执行 `/agent select block`、`/agent select entity`，或用 `/agent select corner a`、`/agent select corner b` 框选区域。`/agent select show` 查看，`/agent select clear` 清除。管理权限要求为 2 级。玩家在外部 Agent 中提问。`mc_status` 同时给出真人 UUID 和该玩家现有的 `selection`。未选择时省略此字段。也可以用 `mc_selection` 单独读取同一份选区。多人时应先明确请求者。世界和选区各带版本标识，离开或换维度会清除个人选区。

| 用途 | 入口 |
| --- | --- |
| 观察世界 | `mc_block`、`mc_blocks`、`mc_block_entity`、`mc_entities`、`mc_inventory` |
| 核对实例知识 | `mc_knowledge`、`mc_recipe`、`mc_tags`、`mc_item_inspect`、`mc_command_inspect` |
| 跨 tick 观察状态 | `mc_observe`、`mc_observe_wait`、`mc_watch`、`mc_watch_list` |
| 执行可核验编辑 | `mc_edit_preview`、`mc_edit_preview_selection`、`mc_edit_apply`、`mc_edit_start` |
| 查历史和撤销 | `mc_edit_jobs`、`mc_undo_records`、`mc_undo` |
| 工程身体 | 用 `tools/list` 查看 `mc_player_*` 的移动、跟随、建造和交互工具 |

遇到陌生模组，Agent 可以基于安装版本和注册 ID 自行查阅资料，再用当前世界的配方、标签和方块数据核对。各模组通过通用接口暴露的信息量会有差异。

## 权限与恢复

`allowWrite` 控制直接编辑，默认关闭。`allowCommands` 独立控制 `mc_command`，默认开启。开着命令权限便可以执行有副作用的原版或模组指令。只调查的委托应按调查范围操作。FakePlayer 的身体交互使用独立的游戏动作。原生命令产生的改动不会自动进入模组的撤销记录。

需要改方块时先预览，将返回的 `planId`、`worldSessionId` 和 `planRevision` 交给应用工具，执行后读回方块。撤销前可查询记录，遇到后续更改产生的冲突会保留记录。`/agent stop` 或 `mc_control_stop` 会停止编辑、观察与身体动作。真人执行 `/agent resume` 后可以发起新任务。`/agent config` 查看配置，`/agent config reload` 重新载入。
