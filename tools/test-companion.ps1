#!/usr/bin/env pwsh
# Deterministic companion control tests. Never connects to Minecraft.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Java\jdk-21.0.10\bin' }
$out = Join-Path $root 'build\companion-tests'
$lib = if ($env:MCAGENT_PRISM_LIBRARIES) { $env:MCAGENT_PRISM_LIBRARIES } else { Join-Path $env:APPDATA 'PrismLauncher/libraries' }
$gson = Join-Path $lib 'com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$sources = @(
    (Join-Path $root 'archive\mcp-0.4\java\CompanionLease.java'),
    (Join-Path $root 'archive\mcp-0.4\java\CompanionMemory.java'),
    (Join-Path $root 'archive\mcp-0.4\java\CompanionChatControl.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\ActionJournal.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\Json.java'),
    (Join-Path $PSScriptRoot 'companion-tests\CompanionLeaseRegression.java'),
    (Join-Path $PSScriptRoot 'companion-tests\ActionJournalRegression.java'),
    (Join-Path $PSScriptRoot 'companion-tests\CompanionMemoryRegression.java'),
    (Join-Path $PSScriptRoot 'companion-tests\CompanionChatRegression.java')
)
& "$jdk\javac.exe" -proc:none -encoding UTF-8 -cp $gson -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'companion test compilation failed' }
& "$jdk\java.exe" -cp "$out;$gson" dev.mcagent.CompanionLeaseRegression
if ($LASTEXITCODE -ne 0) { throw 'companion regressions failed' }
& "$jdk\java.exe" -cp "$out;$gson" dev.mcagent.ActionJournalRegression
if ($LASTEXITCODE -ne 0) { throw 'action journal regressions failed' }
& "$jdk\java.exe" -cp "$out;$gson" dev.mcagent.CompanionMemoryRegression $out
if ($LASTEXITCODE -ne 0) { throw 'companion memory regressions failed' }
& "$jdk\java.exe" -cp "$out;$gson" dev.mcagent.CompanionChatRegression
if ($LASTEXITCODE -ne 0) { throw 'companion chat regressions failed' }
