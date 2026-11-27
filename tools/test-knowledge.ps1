#!/usr/bin/env pwsh
# Read-only command discovery regressions against the launcher's Brigadier.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Java\jdk-21.0.10\bin' }
$lib = if ($env:MCAGENT_PRISM_LIBRARIES) { $env:MCAGENT_PRISM_LIBRARIES } else { Join-Path $env:APPDATA 'PrismLauncher/libraries' }
$cp = @(
    "$lib\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar",
    "$lib\com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar"
) -join ';'
$out = Join-Path $root 'build\knowledge-tests'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$sources = @(
    (Join-Path $root 'src\main\java\dev\mcagent\Json.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\CommandKnowledge.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\CommandReceipt.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\EditJobJournal.java'),
    (Join-Path $PSScriptRoot 'knowledge-tests\EditJobJournalRegression.java'),
    (Join-Path $PSScriptRoot 'knowledge-tests\CommandReceiptRegression.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\ExternalTaskLease.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\McpSessions.java'),
    (Join-Path $PSScriptRoot 'knowledge-tests\McpSessionsRegression.java'),
    (Join-Path $PSScriptRoot 'knowledge-tests\ExternalTaskLeaseRegression.java'),
    (Join-Path $PSScriptRoot 'knowledge-tests\ToolMetadataRegression.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\ToolArguments.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\ToolRegistry.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\McpTool.java'),
    (Join-Path $root 'src\main\java\dev\mcagent\McpResult.java'),
    (Join-Path $PSScriptRoot 'knowledge-tests\ToolRegistryRegression.java'),
    (Join-Path $PSScriptRoot 'knowledge-tests\CommandKnowledgeRegression.java')
)
& "$jdk\javac.exe" -proc:none -encoding UTF-8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'knowledge test compilation failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.McpSessionsRegression
if ($LASTEXITCODE -ne 0) { throw 'MCP session regressions failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.CommandKnowledgeRegression
if ($LASTEXITCODE -ne 0) { throw 'knowledge regressions failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.ToolRegistryRegression
if ($LASTEXITCODE -ne 0) { throw 'tool registry regressions failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.CommandReceiptRegression
if ($LASTEXITCODE -ne 0) { throw 'command receipt regressions failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.EditJobJournalRegression $out
if ($LASTEXITCODE -ne 0) { throw 'edit job journal regressions failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.ExternalTaskLeaseRegression
if ($LASTEXITCODE -ne 0) { throw 'external task lease regressions failed' }
& "$jdk\java.exe" -cp "$out;$cp" dev.mcagent.ToolMetadataRegression
if ($LASTEXITCODE -ne 0) { throw 'tool metadata regressions failed' }
