#!/usr/bin/env pwsh
# Builds the optional QA mod. Installation is deliberately a separate step.
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'build.ps1') -NoInstall
$qaClasses = Join-Path $root 'build\live-test-classes'
New-Item -ItemType Directory -Path $qaClasses -Force | Out-Null
& "$jdk\bin\javac.exe" -proc:none -encoding UTF-8 -cp "$classes;$cp" -d $qaClasses (Join-Path $PSScriptRoot 'live-tests\LiveRegression.java')
if ($LASTEXITCODE -ne 0) { throw 'live regression compilation failed' }
Copy-Item (Join-Path $PSScriptRoot 'live-tests\resources\*') $qaClasses -Recurse -Force
& "$jdk\bin\jar.exe" --create --file (Join-Path $root 'build\mcagent-qa.jar') -C $qaClasses .
if ($LASTEXITCODE -ne 0) { throw 'live regression packaging failed' }
Write-Output 'built build/mcagent-qa.jar (only run in a copied mcagent-review-* save)'
