#!/usr/bin/env pwsh
# Requires the QA mod and its copied save. No world-edit command is sent here.
param([string]$Instance = 'E:\Minecraft\.minecraft\versions\1.21.1(1)\.minecraft')
$ErrorActionPreference = 'Stop'
$cfg = Get-Content -LiteralPath (Join-Path $Instance 'config\mcagent.json') -Raw | ConvertFrom-Json
$uri = "http://127.0.0.1:$($cfg.port)"
$headers = @{Authorization = "Bearer $($cfg.token)"}
function Call-Tool([string]$name, [hashtable]$arguments) {
    $body = @{jsonrpc='2.0'; id=1; method='tools/call'; params=@{name=$name; arguments=$arguments}} | ConvertTo-Json -Depth 8 -Compress
    (Invoke-RestMethod -Uri "$uri/mcp" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 10).result
}
$start = Call-Tool 'mc_command' @{command='mcagentqa chat'; permission=4}
if ($start.isError -or $start.content.text -notlike '*chat mode for 100 ticks*') { throw 'QA chat window did not start' }
$rejected = Call-Tool 'mc_command' @{command='list'}
if (-not $rejected.isError) { throw 'MCP executed a command in chat mode' }
Write-Output 'PASS: live MCP rejects chat-mode commands'
$ws = [Net.WebSockets.ClientWebSocket]::new()
$ws.Options.SetRequestHeader('Authorization', "Bearer $($cfg.token)")
$deadline = [Threading.CancellationTokenSource]::new(10000)
try {
    $null = $ws.ConnectAsync([uri]"ws://127.0.0.1:$($cfg.port)/ws", $deadline.Token).GetAwaiter().GetResult()
    $bytes = [Text.Encoding]::UTF8.GetBytes('{"type":"exec","command":"list"}')
    $null = $ws.SendAsync([ArraySegment[byte]]::new($bytes), [Net.WebSockets.WebSocketMessageType]::Text, $true, $deadline.Token).GetAwaiter().GetResult()
    do {
        $message = [IO.MemoryStream]::new()
        do {
            $buffer = [byte[]]::new(16384)
            $received = $ws.ReceiveAsync([ArraySegment[byte]]::new($buffer), $deadline.Token).GetAwaiter().GetResult()
            $message.Write($buffer, 0, $received.Count)
        } while (-not $received.EndOfMessage)
        $reply = [Text.Encoding]::UTF8.GetString($message.ToArray()) | ConvertFrom-Json
        $message.Dispose()
    } while ($reply.type -notin @('error','exec_result'))
    if ($reply.type -ne 'error' -or $reply.message -notlike '*chat mode*') { throw 'WS executed a command in chat mode' }
    Write-Output 'PASS: live WebSocket rejects chat-mode commands'
} finally {
    $ws.Dispose()
    $deadline.Dispose()
}
$tcp = [Net.Sockets.TcpClient]::new()
try {
    $tcp.Connect('127.0.0.1', $cfg.port)
    $stream = $tcp.GetStream()
    $stream.ReadTimeout = 3000
    # The size alone must be rejected before allocating or waiting for a body.
    $wire = [Text.Encoding]::ASCII.GetBytes("POST /mcp HTTP/1.1`r`nHost: 127.0.0.1`r`nTransfer-Encoding: chunked`r`nConnection: close`r`n`r`n800001`r`n")
    $stream.Write($wire, 0, $wire.Length)
    if ($stream.ReadByte() -ne -1) { throw 'oversized chunk was not rejected before reading its body' }
    Write-Output 'PASS: live HTTP rejects oversized chunk before reading payload or token'
} finally {
    $tcp.Dispose()
}
if (-not (Invoke-RestMethod -Uri "$uri/health" -TimeoutSec 3).ok) { throw 'bridge is unhealthy after rejection' }
Write-Output 'PASS: bridge remains healthy'
