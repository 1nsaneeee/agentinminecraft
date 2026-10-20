#!/usr/bin/env pwsh
# Isolated transport regressions; never connects to or changes a running game.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = 'C:\Program Files\Java\jdk-21.0.10\bin'
$lib = 'C:\Users\CJ\AppData\Roaming\PrismLauncher\libraries'
$cp = @(
    "$lib\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar",
    "$lib\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar"
) -join ';'
$out = Join-Path $root 'build\transport-tests'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$sources = @('HttpCore', 'WebSocketConn', 'WsHub', 'Json', 'AgentMode') |
    ForEach-Object { Join-Path $root "src\main\java\dev\mcagent\$_.java" }
$sources += Get-ChildItem (Join-Path $PSScriptRoot 'tests') -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
& "$jdk\javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'transport test compilation failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.TransportRegression
if ($LASTEXITCODE -ne 0) { throw 'transport regressions failed' }
