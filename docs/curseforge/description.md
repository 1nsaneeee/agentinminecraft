# Agent In Minecraft

> **⚠ 目前只支持 DeepSeek Harness。**
>
> 这个 mod 暴露的是标准 MCP + WebSocket，任何 MCP 客户端都能连；但"在游戏里说话就唤醒 agent"、
> `/agent` 的模式与会话转发、chat 模式的语气注入，这些**配套设施只针对 DeepSeek Harness 实现**。
> 换别的 harness 需要自己写对接层。

完整文档（安装、配置、全部 35 个工具、WebSocket 协议、已知限制）在 GitHub：

**https://github.com/1nsaneeee/agentinminecraft**

## 特色功能

- 实时监听玩家对话，实时做出响应
- 通过游戏内暴露的接口实现正常玩家的操作
- 强大的 work 模式，随心所欲地改造世界
- 一个永远爱用户的心

## 快速开始

**懒人路线**：把下面这句发给你的 AI agent，它会自己下载、安装、配置：

> 依照 https://github.com/1nsaneeee/agentinminecraft/blob/main/docs/AI-INSTALL.md
> 执行本 Mod 的安装步骤。遇到不确定的路径先问我，不要覆盖已有配置。

**手动路线**：把 jar 放进 `mods/` → 启动游戏进一个世界 → 打开
`http://127.0.0.1:38121/health` 看到 `{"ok":true,...}` 即成功。

## 这是什么

把一个正在运行的 Minecraft 客户端/整合包，变成 AI agent 可以直接操作的进程。
MCP 服务器 + WebSocket 通道都跑在游戏进程内，不需要额外开服务、不需要 Gradle 构建链。

从 0.3 起，它还能给 agent 一具**会走会挖会打的身体**：一个没有客户端连接、
但服务端和其他玩家都当成正常玩家的实体。

## 身体能做什么

| 能力 | 说明 |
|---|---|
| 走路 | 带跳跃的 A* 规划 + 原版物理，能跨 2~3 格坑、上台阶 |
| 挖掘 | 按 `getDestroyProgress` 算真实时长，挖完走原版破坏流程（掉落、工具规则都对） |
| 放置 / 使用 / 攻击 | 完整的右键与攻击路径，不是直接改方块 |
| 皮肤 | 客户端按离线 UUID 匹配本地 png |

## 两种模式

- `/agent work` —— 正常干活，所有工具可用
- `/agent chat` —— 只保留身体的动作，**禁止直接改世界**（`mc_set_block` / `mc_fill` /
  `mc_command` / `mc_undo` / 传送全部拒绝，WebSocket 的 `exec` 帧也拒绝）

agent 自己切不回 `work`，要真人输入。能自己解开的限制不叫限制。

## 配置

`config/agentinminecraft.json`，游戏内可用 `/agent config` 查看、`/agent config set` 修改。
默认只绑 `127.0.0.1`、强制 48 位 token、**世界写入默认关闭**。

## 技术参考

35 个 MCP 工具的完整清单、WebSocket 协议、鉴权与拒绝语义，在仓库里：

**https://github.com/1nsaneeee/agentinminecraft/blob/main/docs/MCP.md**

## 安全

这是**游戏内 RCE 级别**的能力：能读写方块、跑任意命令、操作一具真实玩家。
默认值按"单人测试存档"设计。不要把它暴露到公网，也不要关认证。

## 版本

- 需要 Minecraft **1.21.1** + NeoForge **21.1.x**
- 客户端与专用服务器都可加载（皮肤与截图仅客户端）
