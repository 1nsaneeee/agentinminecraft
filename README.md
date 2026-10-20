# Agent In Minecraft

> **⚠ 目前只支持 DeepSeek Harness。**  
> **Client and Server**
>
> mod 本身暴露的是标准 MCP + WebSocket，理论上任何 MCP 客户端都能连；
> 但游戏内行为依赖智能体的插件系统，所以
> 这些**配套设施只针对 DeepSeek Harness 实现**。换别的 harness 需要自己写对接层。

*把一个正在运行的 Minecraft 客户端/整合包，变成 AI agent 可以直接操作的进程*

从 0.3 起，它还能给 agent 一具**会走会挖会打的身体**——一个没有客户端连接、
但服务端和其他玩家都当成正常玩家的玩家实体。

## 特色功能

- 实时监听玩家对话，实时做出响应
- 通过游戏内暴露的接口实现正常玩家的操作——走路、挖矿、放方块、打架
- 自动寻路：给个坐标就能到，会跑,会跳,真是一个活力的美少女
- 真实挖掘：按手里的工具和方块硬度计时，不是瞬间破坏
- 35 个接口：读方块、读机器 NBT、读任务书、截图、执行命令
- 两种不同的模式, `work` 可帮你随意改造世界，
- 一个永远爱用户的心

## 快速开始

**懒人路线**：把下面这一句发给你的 AI agent，它会自己下载、安装、配置。

```text
依照 https://github.com/1nsaneeee/agentinminecraft/blob/main/docs/AI-INSTALL.md
执行本 Mod 的安装步骤。遇到不确定的路径先问我，不要覆盖已有配置。
```

**手动路线**，三步：

1. 把 `AgentInMinecraft-0.3.0.jar` 放进实例的 `mods/`
2. 启动游戏，进一个世界——`config/agentinminecraft.json` 自动生成，token 自动写好
3. 打开 `http://127.0.0.1:38121/health`，看到 `{"ok":true,...}` 就成了

唯一必须你手动做的只有一步：**重启游戏**。

## Agent 的身体

| 它能做什么 | 说明 |
|---|---|
| 走路 | 给它坐标，自己寻路过去。会绕路、会跳坑、会爬台阶 |
| 挖掘 | 按真实工具和硬度计时，挖完正常掉落 |
| 放置 | 放方块、开箱子、拉杆、门，都是正常右键 |
| 使用 | 吃、喝、扔、射 |
| 战斗 | 正常伤害计算，会掉血 |
| 换皮肤 | `skinFile` 指向一张 64×64 png |


## 两种模式

```
/agent work    成为创世神
/agent chat    游戏搭子，会吐槽玩家的毒舌美少女
```

- `work`：所有操作可用。改世界还需要 `allowWrite=true`（默认关闭）：
  `/agent config set allowWrite true`
- `chat`：保留身体的基本动作

## 游戏内命令

| 命令 | 作用 |
|---|---|
| `/agent work` `/agent chat` | 切换模式 |
| `/agent new` `/agent session [n]` | 新建 / 切换会话 |
| `/agent config` | 查看全部配置（token 打码） |
| `/agent config set <key> <value>` | 改一项并写盘 |
| `/agent config reload` | 重读配置，不用重启 |

改 `host` / `port` / `enabled` 要重启才生效。

## 配置

`<game_dir>/config/agentinminecraft.json`

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉就不启动，重启生效 |
| `host` / `port` | `127.0.0.1` / `38121` | 绑定地址，重启生效 |
| `token` | 自动生成 | 48 位 hex，只能改文件 |
| `allowCommands` | `true` | 是否允许执行命令 |
| `allowWrite` | `false` | 世界写入与传送的总开关 |
| `playerName` | `Deepseek` | 身体的名字 |
| `agentName` | `Deepseek` | 没有身体时发言用的署名 |
| `skinFile` / `skinPlayer` / `skinModel` | 空 / 空 / `auto` | 皮肤、套用名单、模型 |
| `greetOnJoin` | `true` | 进世界时打印一行"桥接已就绪" |
| `maxOutputChars` / `maxLogLines` | `16000` / `500` | 输出截断、日志上限 |

## 环境与兼容

| 装在哪 | 有什么 | 缺什么 |
|---|---|---|
| 服务端（推荐，或单人存档） | MCP、WebSocket、身体全部可用 | 截图不可用；皮肤不显示 |
| 客户端（额外） | 能看到 Agent 的皮肤、能用截图 | 只装客户端、没有服务端时，世界类工具全部不可用 |

客户端没装的玩家照常正常游戏，只是会看到默认皮肤。

## 已知限制

- 还没做 TLS 和 HTTP 压缩（只绑回环，够用）
- 撤销栈 16 层，只覆盖 `mc_fill` 的改动；`mc_set_block` 不进撤销栈
- 导航是局部搜索（±10 格、6000 节点），需要绕远路时会退回直线走
- 皮肤只有装了同一个 mod 的客户端看得到
- 服务端卡住时身体也停——移动和物理由 mod 每 tick 手动驱动

## 安全

这是游戏内 RCE 级别的能力。默认值按"单人测试存档"设计：只绑回环、强制 token、
写操作默认关。

**不要**把 `host` 改成 `0.0.0.0` 放到公网，也不要关认证。

## 文档

| 文档 | 给谁 |
|---|---|
| [docs/AI-INSTALL.md](docs/AI-INSTALL.md) | 给 agent 的安装提示词 |
| [docs/MCP.md](docs/MCP.md) | 35 个工具、协议、鉴权和拒绝语义 |
| [docs/BUILD.md](docs/BUILD.md) | 构建、测试、发布、目录结构 |
| [harness/INSTALL.md](harness/INSTALL.md) | 编排侧（插件、profile、skill）的安装与卸载 |
