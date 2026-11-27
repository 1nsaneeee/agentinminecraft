# 从源码构建

玩家可以从 [GitHub Releases](https://github.com/1nsaneeee/agentinminecraft/releases/tag/v0.5.0-rc.3) 下载 JAR 或完整 ZIP。以下是源码仓库的开发入口，无需 Gradle。

构建使用 Windows、PowerShell 7、JDK 21 和 Prism Launcher 的本地库。当前编译 classpath 固定为 Minecraft 1.21.1、NeoForge 21.1.233 及其依赖，发布包的实机版本为 NeoForge 21.1.264。构建前需用启动器准备对应库。源码含既有的 FTB Quests 可选集成；**编译**需向 `-Libs` 提供一个包含匹配的 FTB Quests、FTB Library、FTB Teams JAR 的实例。**运行**正式模组无需安装它们。

```powershell
$env:JAVA_HOME = 'D:/Tools/jdk-21'
# Prism 库使用默认位置时可省略此行。
$env:MCAGENT_PRISM_LIBRARIES = 'D:/PrismLauncher/libraries'
./build.ps1 -NoInstall -Libs 'D:/Games/DevelopmentInstance/minecraft'
./tools/test.ps1
./tools/test-knowledge.ps1
./tools/test-companion.ps1
./tools/test-release.ps1
./tools/package-release.ps1 -OutputDirectory 'build/releases/local'
```

`-NoInstall` 只编译并将 JAR 写入 `build/`。测试分别覆盖传输与配置、MCP 会话与知识工具、身体动作与归档兼容逻辑、安装与回滚。归档的聊天与 WebSocket 类只用于历史回归，不编入生产 JAR。打包脚本拒绝混入退役类，检查包内链接并生成文件清单和 ZIP 的 SHA256。

同一份 JAR 和相同的包内文件可以复现 ZIP 字节。重新编译时，JDK 的 JAR 工具会写入归档时间；比较两个构建时应同时核对归档内的 class 和资源内容。[发布说明与实机验证](releases/0.5.0-rc.3.md)
