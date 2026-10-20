#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Upload the built jar to a CurseForge project.

.DESCRIPTION
    Uses the CurseForge upload API:
        POST https://minecraft.curseforge.com/api/projects/{projectId}/upload

    Credentials are never read from files or arguments - only from environment
    variables, so they cannot end up in a shell history or a git diff:

        $env:CURSEFORGE_TOKEN      API token (Account -> API Tokens)
        $env:CURSEFORGE_PROJECT_ID numeric project id (from the project URL)

    The project must already exist on CurseForge; creating it is a web-side step
    (uploading a file to a non-existent project fails with 404).

.PARAMETER Jar
    Jar to upload. Defaults to build/mcagent-<version>.jar.

.PARAMETER Changelog
    Markdown changelog file. Defaults to dist/curseforge/changelog.md.

.PARAMETER ReleaseType
    release | beta | alpha. Defaults to release.

.PARAMETER DryRun
    Print the metadata that would be sent and exit without calling the API.
#>
#Requires -Version 7.0
param(
    [string]$Jar,
    [string]$Changelog,
    [ValidateSet('release', 'beta', 'alpha')][string]$ReleaseType = 'release',
    [string[]]$GameVersions = @('1.21.1', 'NeoForge'),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

$version = (Select-String -Path (Join-Path $root 'build.ps1') -Pattern "^\`$version\s*=\s*'([^']+)'" |
    Select-Object -First 1).Matches[0].Groups[1].Value
if (-not $version) { throw 'could not read $version from build.ps1' }

if (-not $Jar) { $Jar = Join-Path $root "build\mcagent-$version.jar" }
if (-not (Test-Path $Jar)) { throw "jar not found: $Jar (run .\build.ps1 -NoInstall first)" }

if (-not $Changelog) { $Changelog = Join-Path $root 'docs\curseforge\changelog.md' }
$changelogText = if (Test-Path $Changelog) { Get-Content $Changelog -Raw } else { "See the release notes on GitHub." }

$metadata = [ordered]@{
    changelog    = $changelogText
    changelogType = 'markdown'
    displayName  = "mcagent $version"
    gameVersions = $GameVersions
    releaseType  = $ReleaseType
} | ConvertTo-Json -Depth 4 -Compress

if ($DryRun) {
    Write-Host "DRY RUN - nothing will be uploaded"
    Write-Host "  file         : $Jar ($([math]::Round((Get-Item $Jar).Length/1KB,1)) KiB)"
    Write-Host "  project id   : $($env:CURSEFORGE_PROJECT_ID)"
    Write-Host "  release type : $ReleaseType"
    Write-Host "  game versions: $($GameVersions -join ', ')"
    Write-Host "  metadata     : $metadata"
    return
}

if (-not $env:CURSEFORGE_TOKEN) { throw 'CURSEFORGE_TOKEN is not set' }
if (-not $env:CURSEFORGE_PROJECT_ID) { throw 'CURSEFORGE_PROJECT_ID is not set' }

$uri = "https://minecraft.curseforge.com/api/projects/$($env:CURSEFORGE_PROJECT_ID)/upload"
Write-Host "uploading $Jar to project $($env:CURSEFORGE_PROJECT_ID) ..."

$response = Invoke-RestMethod -Uri $uri -Method Post `
    -Headers @{ 'X-Api-Token' = $env:CURSEFORGE_TOKEN } `
    -Form @{ metadata = $metadata; file = Get-Item $Jar } `
    -TimeoutSec 300

if ($response -and $response.id) {
    Write-Host "uploaded: file id $($response.id)"
    if ($response.url) { Write-Host "url: $($response.url)" }
} else {
    Write-Host 'the API did not return a file id; check the project and token'
    $response | ConvertTo-Json -Depth 6
}
