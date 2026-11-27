# Agent In Minecraft

在 Minecraft 里指给 Agent 看，在你自己的 Agent 里提问。这个 NeoForge 模组把正在运行的世界接到标准 MCP 客户端：Agent 能看到你选中的位置、读取机器和容器、查询当前实例的模组与配方，也能在获得授权后编辑世界。

**0.5.0-rc.3 预发布** · Minecraft Java 1.21.1 · NeoForge 21.1.x · Java 21。单人世界和独立服务器均已实测。模型与对话由外部 Agent 提供，使用模组无需 DeepSeek 专用组件。
[下载预发布 ZIP](https://github.com/1nsaneeee/agentinminecraft/releases/tag/v0.5.0-rc.3) · [安装与客户端配置](docs/AI-INSTALL.md) · [从 0.3.x 迁移](docs/releases/0.5.0-rc.3.md#从-03x-升级)

## 「帮我看看这排炉子怎么了」

在一排四座熔炉前，有管理权限的玩家对准两端，分别执行 `/agent select corner a` 和 `/agent select corner b`，然后在外部 Agent 里问这句话。玩家不用抄坐标，也不用告诉 Agent 先查哪台机器。

实机盲测中，旁边还有一排距离玩家更近、正在工作的炉子。三个新会话分别找到了选中排的三种故障。最有意思的一轮：炉子里有煤却没有矿；Agent 往上查到装着 64 个铁矿石的漏斗，再沿着 `enabled=false` 找到锁住它的红石块。调查期间没有改动方块或物品。[三轮结果、证据与模型答错的地方](docs/releases/0.5.0-rc.3.md#实机验收)

## 接入自己的世界

1. 关闭目标游戏实例或专服，解压发布 ZIP。把 JAR 放入游戏目录的 `mods`，或使用包内安装器预览、安装。升级时只保留一份本模组 JAR。
2. 进入世界，在外部客户端添加 **Streamable HTTP** MCP 服务。默认地址为 `http://127.0.0.1:38121/mcp`，Bearer token 从该实例的 `config/agentinminecraft.json` 读取。安装说明提供可复制的配置示例。
3. 让 Agent 调用 `mc_status`。看到当前世界和自己的玩家身份，就可以选中方块、实体或区域开始提问。多人在线时，在委托里说一下自己的游戏名。[完整接入步骤](docs/AI-INSTALL.md)

## 世界向 Agent 提供什么

| 想做的事 | Agent 可以依据什么行动 |
| --- | --- |
| 查机器为什么停工 | 读选区、方块状态、方块实体和容器，跨游戏 tick 再读一次，核对机器是否真的运转。 |
| 弄清当前整合包的机制 | 查已装模组、版本、当前配方和标签；玩法需要额外资料时，由外部 Agent 自行查证。自定义机器能查到的细节取决于它暴露的数据。 |
| 修改并核验世界 | 先预览选区编辑，再应用、读回，受管编辑有对应的撤销记录。玩家可用 `/agent stop` 停止操作。 |
| 让 Agent 实际动手 | 现有 FakePlayer 可以移动、观察、放置和使用物品，供需要角色身体的工程操作调用。 |

编辑权限与命令权限独立。默认 `allowWrite=false`、`allowCommands=true`；原版命令可能改动世界，命令效果不会自动进入受管编辑的撤销记录。[工具、权限与停止](docs/MCP.md)

**For MCP client developers:** connect over authenticated Streamable HTTP, initialize a session, then use `tools/list` to discover the running version’s tools. The old WebSocket and in-game chat/session commands have been retired. [Migration and release notes](docs/releases/0.5.0-rc.3.md) · [Build and tests](docs/BUILD.md) · [Changelog](CHANGELOG.md)
