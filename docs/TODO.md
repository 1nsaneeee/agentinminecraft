# TODO

待办清单。每条都写清**为什么现在没做**和**大概怎么做**，免得下次只剩一句“那个要改的”。

## 代码

### Agent 视角相机（POV）

**为什么想要**：`mc_screenshot` 抓的是本机客户端画面。Agent 的身体是纯服务端玩家，没有客户端、没有渲染循环，所以它**不存在视角**——你看到的它是你的客户端渲染的。

**怎么做**：客户端加相机覆盖。

```
mixin → Camera.setup / Camera.tick
  if (followed != null) {
      camera 位置 = 对方眼睛坐标
      camera 朝向 = 对方 yaw/pitch
  }
```

配套工具：`mc_camera_follow <player>` / `mc_camera_off`。

**成本**：多一个渲染层 mixin；要测“相机卡住关不掉”的情况；只影响本机。

**价值**：能验证它到底看到了什么（寻路时“它眼里的坑”），也能拍第一人称截图。

---

### 多 harness 支持

**现状**：mod 的 MCP/WS 是 LLM 无关的，但 `harness/dsh-mc-chat` 这半个只为 DeepSeek Harness 写。
README 首行因此必须写“只支持 DSH”。

**怎么做**：把插件依赖的 DSH API（`agents.create`、`agent.followup`、会话列表）抽成一层薄接口，
不同 harness 各写一个 adapter。mod 侧不用动。

**成本**：中。要先定义清“harness 需要提供什么”。

---

### `mc_set_block` 进撤销栈

**现状**：`mc_fill` 的改动可撤销（16 层），`mc_set_block` 不进栈。
**为什么没做**：单方块替换的成本低，先按“小改动不用撤销”处理。
**怎么做**：记录旧 blockstate 时复用 `EditHistory`，把单格也记成一次操作。

---

### 导航：2 格台阶与更大搜索范围

**现状**：`AgentNav` 只把“上 1 格”当台阶；2 格高差要靠跳，边里没连。
搜索盒 ±10 格、6000 节点，需要绕远路时退回直线 steering。

**怎么做**：加一条“跳上 2 格”的边（需要助跑空间判定）；盒子和节点数做成参数。

---

### 可选角色包

**想法**：`skinFile` 换一张 png 就换一个角色。可以带几张预置皮肤（需要手工像素化，
插画不能直接当皮肤用——皮肤是 64×64 UV 展开图，脸只有 8×8）。

**成本**：每张要人画，效果可预期但不惊艳。

---

## 发布

- [ ] **CurseForge 上传**：等 `CURSEFORGE_TOKEN` 和项目 ID。
      项目必须先网页建好，API 不能往不存在的项目传。
      脚本已就绪：`tools/publish-curseforge.ps1`（`-DryRun` 可先看要传什么）。
- [ ] **实机截图**：CurseForge 列表页最缺证据。计划 3~5 张：
      站在一起 / 游戏内对话 / 挖掘瞬间 / 跳跃半空 / `/agent config` 输出。
      纯净实例已就绪：`1.21.1(Mod dev)`。
- [ ] **项目名**：现在叫 `Agent In Minecraft`，CurseForge 表单建议避免游戏名。
      若被审核要求改名，仓库、README、hero、文档要一起改（约 10 分钟）。

## 已决定不做

- **Java 包名 `dev.mcagent` 与日志前缀 `[mcagent]`**：内部标识，不在用户视野，
  改名牵动 35 个文件和 mixin 配置，收益为零。
- **给假玩家补客户端确认链路**：挖掘、传送都绕开了它，继续绕开比模拟一个客户端便宜得多。
