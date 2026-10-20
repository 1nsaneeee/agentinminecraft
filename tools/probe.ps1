#!/usr/bin/env pwsh
# Talk to the mcagent MCP endpoint directly (used to verify the bridge before
# trusting the harness wiring). Reads the token from the game's own config.
param(
    [int]$Port = 38121,
    [string]$Token,
    [string]$Tool,
    [string]$ArgsJson = '{}',
    [string]$ConfigPath = 'E:\Minecraft\.minecraft\versions\1.21.1(1)\.minecraft\config\mcagent.json'
)

$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Port"

if (-not $Token) {
    if (-not (Test-Path $ConfigPath)) { throw "no token and no config at $ConfigPath" }
    $Token = (Get-Content $ConfigPath -Raw | ConvertFrom-Json).token
}
$headers = @{ Authorization = "Bearer $Token" }

function Invoke-Mcp([hashtable]$payload) {
    $body = $payload | ConvertTo-Json -Depth 8 -Compress
    Invoke-RestMethod -Method Post -Uri "$base/mcp" -Headers $headers -ContentType 'application/json' -Body $body
}

Write-Host '--- health ---' -ForegroundColor Cyan
try {
    Invoke-RestMethod -Uri "$base/health" | ConvertTo-Json -Depth 5
} catch {
    Write-Host "health failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'The game is probably not running, or the mod did not load.' -ForegroundColor Yellow
    exit 1
}

Write-Host "`n--- initialize ---" -ForegroundColor Cyan
Invoke-Mcp @{
    jsonrpc = '2.0'; id = 1; method = 'initialize'
    params  = @{ protocolVersion = '2025-06-18'; capabilities = @{}; clientInfo = @{ name = 'probe'; version = '0' } }
} | ConvertTo-Json -Depth 6

if ($Tool) {
    Write-Host "`n--- tools/call $Tool ---" -ForegroundColor Cyan
    $argsText = if ($ArgsJson -and $ArgsJson.Trim()) { $ArgsJson.Trim() } else { '{}' }
    # Raw string concat keeps this working on Windows PowerShell 5.1, which
    # lacks ConvertFrom-Json -AsHashtable.
    $body = '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"' + $Tool + '","arguments":' + $argsText + '}}'
    $resp = Invoke-RestMethod -Method Post -Uri "$base/mcp" -Headers $headers `
        -ContentType 'application/json' -Body $body
    if ($resp.result.content) {
        foreach ($item in $resp.result.content) {
            if ($item.type -eq 'text') { Write-Output $item.text }
            else { Write-Output "[$($item.type) content, $($item.data.Length) base64 chars]" }
        }
    } else {
        $resp | ConvertTo-Json -Depth 10
    }
} else {
    Write-Host "`n--- tools/list ---" -ForegroundColor Cyan
    $list = Invoke-Mcp @{ jsonrpc = '2.0'; id = 2; method = 'tools/list'; params = @{} }
    $list.result.tools | ForEach-Object { "  {0,-16} {1}" -f $_.name, ($_.description -split "`n")[0] }
}
