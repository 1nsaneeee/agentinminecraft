# 让 AI 替你装

这份文件就是一段可以直接粘给 agent 的提示词。装好 **DeepSeek Harness**（或任何能读文件、
能执行命令的 agent），把下面整段复制过去，剩下的它会自己做完。

---

## 提示词（复制这一段）

```text
帮我安装 Agent In Minecraft —— 一个把 Minecraft 暴露给 AI agent 的 NeoForge mod。

【环境】
- Minecraft 1.21.1 + NeoForge 21.1.x
- 我要装的是我平时在玩的那个实例，目录里应该有 mods/ 和 config/

【步骤】

1. 下载
   从 https://github.com/1nsaneeee/agentinminecraft/releases/latest 下载
   名字形如 AgentInMinecraft-<版本>.zip 的附件，解压到临时目录。

2. 确认实例目录
   找到我实际在玩的 .minecraft 实例（含 mods/ 的那个）。
   如果不确定是哪个，把候选列给我，不要自己猜着装。

3. 装 mod jar
   把 AgentInMinecraft-<版本>.jar 复制到 <实例>/mods/。
   如果 mods/ 里已经存在 mcagent-*.jar（改名前的旧版本），先删掉它再放新的。
   ⚠ 两个同名 mod 会抢同一个端口，导致桥接起不来。

4. 装编排侧（可选，但推荐）
   - 把压缩包里的 harness/dsh-mc-chat 整个复制到 ~/.dsh/plugins/dsh-mc-chat
   - 在 ~/.dsh/profiles/<我的profile>/node_modules/ 下建一个指向它的目录链接
     （Windows 用 New-Item -ItemType Junction，不要用 Copy-Item）
   - 把 harness/profile-patch-snippet.yml 的内容追加到
     ~/.dsh/profiles/<我的profile>/cordis.patch.yml
   - 把 harness/skills/minecraft-agent-bridge 复制到 ~/.dsh/skills/

5. 自检
   做完后逐条确认并把结果告诉我：
   - <实例>/mods/AgentInMinecraft-<版本>.jar 存在，且没有 mcagent-*.jar
   - 插件目录存在，profile 里 mc-chat 的 insert 块存在且只有一份
   - 告诉我游戏启动后应该用哪条命令验证桥接

【约束】
- 不要删除 mods/ 里与本次无关的任何文件
- 不要改我的存档
- 找不到路径、或者发现已有配置文件里有我不认识的字段时，先问我，不要覆盖
- 需要重启游戏才能生效，做完提醒我重启
```

---

## 它实际会做什么

| 步骤 | 动作 | 风险 |
|---|---|---|
| 1 | 下载 release 压缩包到临时目录 | 无 |
| 2 | 确认实例路径 | 无（不确定时会问你） |
| 3 | 复制 jar、删掉改名前的旧 jar | 删的只是 `mcagent-*.jar`，我们自己的旧包 |
| 4 | 装 harness 插件、改 profile、装 skill | 只新增，不删改其它配置 |
| 5 | 给出验证步骤 | 无 |

提示词里明确要求：**不确定就问、不碰存档、不删无关文件**。如果你的 agent 不看这些就动手，
换个 agent。

## 你还要做的

只有两件：

1. **重启游戏**（FML 没有热重载）
2. 进一个世界后，让它跑一遍验证：

```powershell
curl http://127.0.0.1:38121/health
# {"ok":true,"name":"agentinminecraft",...}
```

## 装完先试这三条

```text
mc_status           看游戏状态、TPS、你的坐标
mc_player_spawn     给 agent 造一具身体
mc_player_walk      让它走到你身边（会自动寻路、跳坑）
```

能走、能挖、能打，就成了。

## 出问题时先看

| 症状 | 先查 |
|---|---|
| 端口连不上 | 游戏起来了吗？`config/agentinminecraft.json` 存在吗？ |
| `no server running` | 游戏停在主菜单，还没进世界 |
| 8080/38121 被占 | `mods/` 里是否有两个同名 mod（旧 jar 没删） |
| 说话没反应 | harness 插件装了吗？profile 里的 insert 块在吗？ |

更细的排查见 [../harness/INSTALL.md](../harness/INSTALL.md)。
