#!/usr/bin/env pwsh
# Isolated transport regressions; never connects to or changes a running game.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Java\jdk-21.0.10\bin' }
$lib = if ($env:MCAGENT_PRISM_LIBRARIES) { $env:MCAGENT_PRISM_LIBRARIES } else { Join-Path $env:APPDATA 'PrismLauncher/libraries' }
$cp = @(
    "$lib\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar",
    "$lib\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar"
) -join ';'
$out = Join-Path $root 'build\transport-tests'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$sources = @('HttpCore', 'Json', 'AgentMode', 'ExternalTaskLease') |
    ForEach-Object { Join-Path $root "src\main\java\dev\mcagent\$_.java" }
$sources += @('WebSocketConn', 'WsHub') | ForEach-Object { Join-Path $root "archive/mcp-0.4/java/$_.java" }
$sources += Get-ChildItem (Join-Path $PSScriptRoot 'tests') -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
& "$jdk\javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'transport test compilation failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.TransportRegression
if ($LASTEXITCODE -ne 0) { throw 'transport regressions failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.WsLifecycleRegression
if ($LASTEXITCODE -ne 0) { throw 'WebSocket lifecycle regressions failed' }

# Compile the real configuration class separately from the transport fixture's stub.
$configOut = Join-Path $root 'build\config-tests'
New-Item -ItemType Directory -Path $configOut -Force | Out-Null
$configSources = @('AgentConfig', 'AgentMode', 'Json') |
    ForEach-Object { Join-Path $root "src\main\java\dev\mcagent\$_.java" }
$configSources += Join-Path $PSScriptRoot 'config-tests\ConfigReloadRegression.java'
& "$jdk\javac.exe" -proc:none -encoding UTF-8 -cp "$out;$cp" -d $configOut $configSources
if ($LASTEXITCODE -ne 0) { throw 'configuration regression compilation failed' }
& "$jdk\java.exe" -cp "$configOut;$out;$cp" dev.mcagent.ConfigReloadRegression
if ($LASTEXITCODE -ne 0) { throw 'configuration reload regressions failed' }
