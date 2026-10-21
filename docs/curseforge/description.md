![Agent In Your World](https://raw.githubusercontent.com/1nsaneeee/agentinminecraft/main/assets/readme/hero.webp)

# Agent In Your World

**Give an AI agent a real player body inside your Minecraft world.**

> ⚠ **DeepSeek Harness only, for now.** The mod itself speaks plain MCP + WebSocket, so any MCP client can connect. Waking it from in-game chat, the `/agent` modes and session forwarding, and the chat-mode personality are implemented against DeepSeek Harness. Other harnesses need their own adapter.

## Screenshots

![Close-up of the agent body](https://raw.githubusercontent.com/1nsaneeee/agentinminecraft/main/assets/readme/screenshots/01-body.png)
![The agent standing next to the player](https://raw.githubusercontent.com/1nsaneeee/agentinminecraft/main/assets/readme/screenshots/02-together.png)
![Mining progress on a block](https://raw.githubusercontent.com/1nsaneeee/agentinminecraft/main/assets/readme/screenshots/03-mining.png)
![A full conversation in chat mode](https://raw.githubusercontent.com/1nsaneeee/agentinminecraft/main/assets/readme/screenshots/04-chat.png)

Top left: a real player entity - skin, name tag, tool in hand.
Top right: not a chat window - it stands beside you.
Bottom left: blocks break over time, not instantly.
Bottom right: after `/agent chat` it talks, jokes, and answers back.

## Features

- Listens to in-game chat live and answers in the same chat
- Acts through the game's own interfaces, not by editing data behind its back
- Pathfinding: give it coordinates and it walks there - around obstacles, over gaps, up steps
- Real mining time, based on the tool and the block's hardness
- Two personalities: `work` builds, `chat` keeps you company
- A body that fidgets, glances around, and turns to look at you when you come close

## Modes

```
/agent work    build and break, all tools available
/agent chat    a companion that talks back; direct world edits are refused
```

`chat` keeps the body's normal movement but refuses to edit the world, run commands, or teleport.
**The agent cannot switch itself back to `work`** - only you can, from in-game chat.

## Skins

**No skin ships with the mod**, so a fresh body uses the default Steve/Alex look.

Drop a skin png into the game directory and point the config at it:

```
/agent config set skinFile config/mcagent/skin.png
```

Then let your AI respawn the body. Related fields: `skinFile` (the file), `skinPlayer` (which names wear it, blank = follow `playerName`), `skinModel` (`auto` / `wide` / `slim`).

## Install

Drop the jar into `mods/` and restart the game. Full instructions, configuration, source and docs:

**https://github.com/1nsaneeee/agentinminecraft**

Requires Minecraft **1.21.1** + NeoForge **21.1.x** · Client and Server

---

## 中文

**让 AI 在你的世界里拥有一具真的身体。**

同一段说明的中文版，全文见 [GitHub](https://github.com/1nsaneeee/agentinminecraft/blob/main/README.md)。

- 实时监听游戏内聊天并回应
- 通过游戏自身接口操作，不走改数据的旁路
- 给坐标就能走过去：会绕路、会跳坑、会爬台阶
- 按工具与方块硬度计时，不是瞬间破坏
- 两种模式：`work` 造世界，`chat` 陪你聊天
- 闲下来会东张西望、会转过头看你

安装：把 jar 放进 `mods/` 重启游戏。需要 Minecraft **1.21.1** + NeoForge **21.1.x**。
