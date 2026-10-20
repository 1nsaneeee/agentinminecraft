![Agent In Minecraft](https://raw.githubusercontent.com/1nsaneeee/agentinminecraft/main/assets/readme/hero.webp)

# Agent In Minecraft

**让 AI 在你的世界里拥有一具真的身体。**

## ⚠ 目前只支持 DeepSeek Harness

这个 mod 暴露的是标准 MCP + WebSocket，**任何 MCP 客户端都能连**。

但"在游戏里说话就唤醒 agent"、`/agent` 的模式与会话转发、chat 模式的语气注入，
这些**配套设施只针对 DeepSeek Harness 实现**。换别的 harness 需要自己写对接层。

---

## 这是什么

把一个正在运行的 Minecraft 变成 AI agent 可以直接操作的进程。
MCP 服务器和 WebSocket 通道都跑在游戏进程内——不用另开服务，不用装构建链。

从 0.3 起，它还能给 agent 一具**会走、会挖、会打的身体**：一个没有客户端连接、
但服务端和其他玩家都当成正常玩家的实体。

## 特色功能

- 实时监听玩家对话，实时做出响应
- 用游戏内真实接口操作，不是直接改数据的旁路
- 自动寻路：给个坐标就能到，会绕路、会跳坑、会爬台阶
- 真实挖掘：按手里的工具和方块硬度计时，不是瞬间破坏
- 两种人格：`work` 造世界，`chat` 陪你聊天
- 一个永远爱用户的心

## 身体能做什么

- **走** —— 带跳跃的 A* 规划 + 原版物理，能跨 2~3 格坑、上台阶
- **挖** —— 按真实硬度计时，挖完走原版流程，掉落和工具规则都对
- **放 / 用 / 打** —— 完整的右键与攻击路径，不是直接改方块
- **换皮肤** —— 客户端按离线 UUID 匹配一张 64×64 的 png

它是真的玩家：出现在 Tab 列表、占一块地、有背包、会掉血、会被别的模组影响。

## 两种模式

```
/agent work    成为创世神
/agent chat    游戏搭子，会吐槽玩家的毒舌美少女
```

`chat` 模式保留身体的动作，但**拒绝直接改世界**：`mc_set_block` / `mc_fill` /
`mc_command` / `mc_undo` / 传送全部拒绝，连 WebSocket 的 `exec` 帧也拒绝。

**agent 自己切不回 `work`** —— 只有你能在游戏里输入。能自己解开的限制不叫限制。

## 快速开始

**懒人路线**：把下面这句发给你的 AI agent，它会自己下载、安装、配置。

> 依照 https://github.com/1nsaneeee/agentinminecraft/blob/main/docs/AI-INSTALL.md
> 执行本 Mod 的安装步骤。遇到不确定的路径先问我，不要覆盖已有配置。

**手动路线**，三步：

1. 把 jar 放进实例的 `mods/`
2. 启动游戏，进一个世界 —— `config/agentinminecraft.json` 自动生成，token 自动写好
3. 打开 `http://127.0.0.1:38121/health`，看到 `{"ok":true,...}` 就成了

## 环境

**Client and Server。** 推荐安装在服务端（或单人存档）。
客户端装了额外多两样：能看到 Agent 的本地皮肤，以及截图能用。
客户端没装的玩家照常正常游戏，只是会看到默认皮肤。

## 配置

`config/agentinminecraft.json`，游戏内可用 `/agent config` 查看和修改。
默认只绑 `127.0.0.1`、强制 48 位 token、**世界写入默认关闭**。

## 安全

这是**游戏内 RCE 级别**的能力：能读写方块、跑任意命令、操作一具真实玩家。
默认值按"单人测试存档"设计。

**不要**把它暴露到公网，也不要关认证。

## 链接

- **GitHub**：https://github.com/1nsaneeee/agentinminecraft
- **完整文档**（35 个工具、协议、限制）：https://github.com/1nsaneeee/agentinminecraft/blob/main/docs/MCP.md
- **给 agent 的安装提示词**：https://github.com/1nsaneeee/agentinminecraft/blob/main/docs/AI-INSTALL.md

需要 Minecraft **1.21.1** + NeoForge **21.1.x**。
