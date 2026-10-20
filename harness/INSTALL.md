# mcagent 配套设施安装

这个包分两半：

| 半边 | 内容 | 必需？ |
|---|---|---|
| 游戏侧 | `AgentInMinecraft-0.3.0.jar` | **必需**。没有它就没有桥接 |
| 编排侧 | `dsh-mc-chat` 插件 + profile 片段 + skill | 可选。没有它，你仍然可以直接调 MCP 工具，只是没有"游戏里说话就能唤醒 agent"这一段 |

只装游戏侧也能用：任何 MCP 客户端（Claude Desktop、自己的脚本、`curl`）都能连。

---

## 一、游戏侧

### 1. 前置

- Minecraft **1.21.1**
- NeoForge **21.1.x**（`21.1.0` 以上）
- 客户端或专用服务器都行；皮肤和截图只在客户端可用

### 2. 装 jar

把 `AgentInMinecraft-0.3.0.jar` 放进实例的 `mods/` 目录。

### 3. 首次启动

启动游戏（或服务器），进入一个世界。mod 会：

1. 在 `config/agentinminecraft.json` 写一份配置，**自动生成 48 位 token**
2. 在 `127.0.0.1:38121` 起 HTTP + WebSocket
3. 在聊天栏打印一行"桥接已就绪"

### 4. 确认活着

```powershell
curl http://127.0.0.1:38121/health
# {"ok":true,"name":"mcagent","version":"0.3.0",...}
```

`/health` 是唯一免鉴权的端点，专门用来区分"没起来"和"鉴权坏了"。

### 5. 配置

`config/agentinminecraft.json`，游戏内可以用 `/agent config` 查看、`/agent config set` 修改。
字段说明见 README 的配置表。

**默认 `allowWrite` 是 `false`**：`mc_fill` / `mc_set_block` / 传送都会被拒。
需要放方块时再打开：

```
/agent config set allowWrite true
```

---

## 二、编排侧（可选）

这一半让 agent 在**游戏里被 @ 到就自动醒过来**，并且能在游戏内切换模式与会话。

### 1. 装插件

把 `dsh-mc-chat/` 整个目录放到 DSH 的插件目录，并在 profile 里链接过去：

```powershell
# 插件本体
Copy-Item -Recurse dsh-mc-chat "$env:USERPROFILE\.dsh\plugins\dsh-mc-chat"

# profile 里的引用（目录链接，改插件源码立即生效）
New-Item -ItemType Junction `
  -Path "$env:USERPROFILE\.dsh\profiles\desktop\node_modules\dsh-mc-chat" `
  -Target "$env:USERPROFILE\.dsh\plugins\dsh-mc-chat"
```

> 开发提示：仓库里的 `harness/dsh-mc-chat/` 是**发布副本**。改插件时应该改
> `~/.dsh/plugins/dsh-mc-chat/`（那里是活的），再复制回仓库，否则两边会漂移。

### 2. 加 profile 片段

把 `profile-patch-snippet.yml` 的内容追加到
`%USERPROFILE%\.dsh\profiles\desktop\cordis.patch.yml`。

**不需要写任何路径**。运行中的游戏会在 `~/.mcagent/active.json` 写下自己的配置路径，
插件读这个指针跟随，所以：

- 换实例（开另一个 `.minecraft`）→ 自动跟过去
- 同时开两个实例 → 跟着最后启动的那个
- 游戏关了 → 指针被删除，插件退回静态候选，不会连一个已经死掉的端口

查找顺序：

1. 片段里的 `config.configPath`（需要强制指定时才写）
2. 环境变量 `MCAGENT_CONFIG`
3. `~/.mcagent/active.json`（游戏写的指针）
4. 当前目录下的 `config/agentinminecraft.json`

只有全都没命中时，才需要手动设：

```powershell
$env:MCAGENT_CONFIG = '<game_dir>/config/agentinminecraft.json'
```

### 3. 装 skill（可选）

让 agent 知道这些工具怎么用：

```powershell
Copy-Item -Recurse skills\minecraft-agent-bridge "$env:USERPROFILE\.dsh\skills\"
```

### 4. 重启 DSH

插件是热加载的，但 profile 片段变化需要重启一次。

---

## 三、验证

按顺序做，任何一步失败都别往下走。

| # | 做什么 | 期望 |
|---|---|---|
| 1 | `curl http://127.0.0.1:38121/health` | `{"ok":true,...}` |
| 2 | MCP 调 `mc_status` | 返回坐标、TPS、玩家列表 |
| 3 | 游戏里 `/agent config` | 打印 14 项，token 打码 |
| 4 | 游戏里说一句话 | agent 被唤醒并在游戏里回话 |
| 5 | 游戏里 `/agent chat` | 聊天模式；此时 `mc_set_block` 应被拒 |
| 6 | 游戏里 `/agent work` | 恢复；`mc_set_block` 可用（需 `allowWrite`） |

第 5 步的拒绝消息长这样：

```
chat mode does not edit the world directly - use the agent player's own hands, or /agent work first
```

---

## 四、卸载

```powershell
# 游戏侧
Remove-Item <game_dir>\mods\AgentInMinecraft-0.3.0.jar
Remove-Item <game_dir>\config\mcagent.json -Recurse -Force   # 含 token

# 编排侧
# 1) 从 cordis.patch.yml 删掉 mc-chat 那个 insert 块
# 2) 删掉 profiles\desktop\node_modules\dsh-mc-chat（链接，不删本体）
# 3) 删掉 ～/.dsh/plugins/dsh-mc-chat
```

---

## 五、安全提示

这是**游戏内 RCE 级别**的能力：能读写方块、跑任意命令、操作一具真实玩家。

默认值按"单人测试存档"设计——只绑回环、强制 token、写操作默认关。

- **不要**把 `host` 改成 `0.0.0.0` 放到公网
- **不要**关掉 token 校验
- token 不会出现在日志和 `/agent config` 输出里，但会出现在 `config/agentinminecraft.json` 里，
  所以别把这个文件发给别人

chat 模式是给 agent 上的牵引绳：它保留身体的动作（走、挖、放、用、打），
但禁止直接改世界和传送。**agent 自己切不回来**，要真人输入 `/agent work`——
能自己解开的限制不叫限制。
