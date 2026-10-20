![Agent In Minecraft](https://raw.githubusercontent.com/1nsaneeee/agentinminecraft/main/assets/readme/hero.webp)

# Agent In Minecraft

**让 AI 在你的世界里拥有一具真的身体。**

> ⚠ **目前只支持 DeepSeek Harness。** mod 本身是标准 MCP + WebSocket，任何 MCP 客户端都能连；
> 但游戏内唤醒、`/agent` 的模式与会话转发、chat 模式的语气注入，只针对 DeepSeek Harness 实现。

## 特色功能

- 实时监听玩家对话，实时做出响应
- 用游戏内真实接口操作，不是直接改数据的旁路
- 自动寻路：给个坐标就能到，会绕路、会跳坑、会爬台阶
- 真实挖掘：按手里的工具和方块硬度计时，不是瞬间破坏
- 两种人格：`work` 造世界，`chat` 陪你聊天
- 一个永远爱用户的心

## 截图

<!-- 图片已上传到 CurseForge 的图片区，把下面四条换成本站的图片直链即可：
![01 身体近景](<图片直链>)
![02 和玩家同框](<图片直链>)
![03 挖掘进度](<图片直链>)
![04 chat 模式](<图片直链>)
-->
图片在项目的 Gallery 区。

## 两种模式

```
/agent work    成为创世神
/agent chat    游戏搭子，会吐槽玩家的毒舌美少女
```

`chat` 模式保留身体的动作，但拒绝直接改世界。**agent 自己切不回 `work`** —— 只有你能在游戏里输入。

## 安装

把 jar 放进 `mods/`，重启游戏。详细步骤、配置、源码与全部文档：

**https://github.com/1nsaneeee/agentinminecraft**

需要 Minecraft **1.21.1** + NeoForge **21.1.x** · Client and Server
