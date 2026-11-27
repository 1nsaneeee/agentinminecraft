# 安装与外部 Agent 接入

退出目标游戏实例或停止专服。解压完整 ZIP，保持 JAR、安装脚本和清单在同一目录。通过以下 PowerShell 7 命令预览并安装，将路径换成目标实例里直接包含 `mods` 的游戏目录。

```powershell
./install.ps1 -InstancePath 'D:/Games/MyInstance/minecraft'
./install.ps1 -InstancePath 'D:/Games/MyInstance/minecraft' -Apply
```

安装器校验包内清单，只替换相同 Mod ID 的 JAR，升级时打印旧 JAR 的备份位置。手动安装时先移走旧版 JAR。配置文件、存档和外部 Agent profile 由你管理。

启动 Minecraft 1.21.1（NeoForge 21.1.x），进入世界。在实例的 `config/agentinminecraft.json` 中读取 `host`、`port` 和 `token`。默认地址是 `http://127.0.0.1:38121/mcp`。将类型设为 Streamable HTTP，设置请求头 `Authorization: Bearer <token>`。在支持环境变量的客户端，可用以下 PowerShell 将凭据放入当前进程环境，再从同一终端启动该客户端。

```powershell
$cfg = Get-Content -LiteralPath 'D:/Games/MyInstance/minecraft/config/agentinminecraft.json' -Raw | ConvertFrom-Json
$env:MCAGENT_TOKEN = $cfg.token
```

例如，支持以下 TOML 配置的客户端可以引用环境变量。

```toml
[mcp_servers.minecraft]
url = "http://127.0.0.1:38121/mcp"
bearer_token_env_var = "MCAGENT_TOKEN"
```

在客户端列出工具并调用 `mc_status`。看到 `running=true`、当前世界和真人玩家 UUID 即已接通。默认地址指向运行游戏的同一台电脑。401 时检查当前实例的 token，404 会话失效时重新初始化。详见 [MCP 使用说明](MCP.md)。
