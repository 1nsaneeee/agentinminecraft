#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../build/releases'),
    [string]$JarPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression

function Get-Sha256Bytes([byte[]]$Bytes) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}

# Read actual [[mods]] tables. Dependency tables and multiline descriptions may
# mention a ModID without declaring that this jar owns it.
function Read-ModTables([string]$Text) {
    $mods = [Collections.Generic.List[object]]::new()
    $current = $null
    $multiline = $null
    foreach ($rawLine in [regex]::Split($Text, '\r?\n')) {
        $line = $rawLine.Trim()
        if ($null -ne $multiline) {
            $end = $line.IndexOf($multiline, [StringComparison]::Ordinal)
            if ($end -ge 0) {
                $after = $line.Substring($end + 3).Trim()
                if ($after -and -not $after.StartsWith('#')) { throw 'Unsupported content after a multiline TOML string' }
                $multiline = $null
            }
            continue
        }
        if (-not $line -or $line.StartsWith('#')) { continue }
        if ($line.StartsWith('[')) {
            if ([regex]::IsMatch($line, '^\[\[mods\]\]\s*(?:#.*)?$')) {
                $current = [ordered]@{ modId = $null; version = $null }
                $mods.Add($current)
            } else { $current = $null }
            continue
        }
        $triple = [regex]::Match($line, '^[^=#]+\s*=\s*(?<quote>"""|''{3})')
        if ($triple.Success) {
            $quote = $triple.Groups['quote'].Value
            $content = $line.Substring($triple.Groups['quote'].Index + 3)
            if ($content.IndexOf($quote, [StringComparison]::Ordinal) -lt 0) { $multiline = $quote }
            continue
        }
        if ($null -eq $current) { continue }
        $field = [regex]::Match($line, '^(?<key>modId|version)\s*=\s*(?:"(?<double>[^"]*)"|''(?<single>[^'']*)'')\s*(?:#.*)?$')
        if ($field.Success) {
            $key = $field.Groups['key'].Value
            if ($null -ne $current[$key]) { throw "Duplicate $key in a mods table" }
            $current[$key] = if ($field.Groups['double'].Success) { $field.Groups['double'].Value } else { $field.Groups['single'].Value }
        } elseif ([regex]::IsMatch($line, '^(modId|version)\s*=')) {
            throw 'Unsupported ModID or version value in TOML metadata'
        }
    }
    if ($null -ne $multiline) { throw 'Unterminated multiline TOML string' }
    return $mods.ToArray()
}

function Get-JarModMetadata([string]$Path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $metadata = @($archive.Entries | Where-Object FullName -CEQ 'META-INF/neoforge.mods.toml')
        if ($metadata.Count -ne 1 -or $metadata[0].Length -gt 1048576) {
            throw 'The jar must contain exactly one bounded NeoForge metadata file'
        }
        $stream = $metadata[0].Open()
        try {
            $buffer = [byte[]]::new(1048577)
            $length = 0
            while ($length -lt $buffer.Length) {
                $read = $stream.Read($buffer, $length, $buffer.Length - $length)
                if ($read -eq 0) { break }
                $length += $read
            }
            if ($length -gt 1048576) { throw 'NeoForge metadata exceeds the bounded read limit' }
            $text = [Text.UTF8Encoding]::new($false, $true).GetString($buffer, 0, $length).TrimStart([char]0xfeff)
        } finally { $stream.Dispose() }
        return Read-ModTables $text
    } finally { $archive.Dispose() }
}

function Assert-NoLinks([string]$Path) {
    $cursor = $Path
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Linked paths are not accepted for package sources or output: $cursor"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if (-not $parent -or $parent -eq $cursor) { break }
        $cursor = $parent
    }
}

function Assert-InDirectory([string]$Path, [string]$Directory) {
    $prefix = $Directory.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes the intended output directory: $Path"
    }
}

function Assert-PackagedReference([string]$Source, [string]$Target, $Payload) {
    $targetText = [Net.WebUtility]::HtmlDecode($Target.Trim())
    if ($targetText.StartsWith('<') -and $targetText.EndsWith('>')) { $targetText = $targetText.Substring(1, $targetText.Length - 2) }
    if (-not $targetText -or $targetText.StartsWith('#') -or
        [regex]::IsMatch($targetText, '^(?:https?:|mailto:|data:|//)', [Text.RegularExpressions.RegexOptions]::IgnoreCase)) { return }
    $targetText = [regex]::Match($targetText, '^[^?#]*').Value
    $targetText = [Uri]::UnescapeDataString($targetText)
    if (-not $targetText) { return }
    if ($targetText.Contains('\') -or $targetText.StartsWith('/') -or [regex]::IsMatch($targetText, '^[A-Za-z][A-Za-z0-9+.-]*:')) {
        throw "Unsupported local release reference in $Source`: $Target"
    }
    $parts = [Collections.Generic.List[string]]::new()
    $folder = [IO.Path]::GetDirectoryName($Source).Replace('\', '/')
    if ($folder) { foreach ($part in $folder.Split('/')) { $parts.Add($part) } }
    foreach ($part in $targetText.Split('/')) {
        if (-not $part -or $part -eq '.') { continue }
        if ($part -eq '..') {
            if (-not $parts.Count) { throw "Release reference escapes the package in $Source`: $Target" }
            $parts.RemoveAt($parts.Count - 1)
        } else { $parts.Add($part) }
    }
    $reference = [string]::Join('/', $parts)
    if (-not $Payload.ContainsKey($reference)) { throw "Release reference is not packaged: $Source -> $reference" }
}

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$javaSource = [IO.File]::ReadAllText((Join-Path $root 'src/main/java/dev/mcagent/McAgent.java'))
$versions = [regex]::Matches($javaSource, 'public\s+static\s+final\s+String\s+VERSION\s*=\s*"([^"]+)"\s*;')
$modIds = [regex]::Matches($javaSource, 'public\s+static\s+final\s+String\s+MOD_ID\s*=\s*"([^"]+)"\s*;')
if ($versions.Count -ne 1 -or $modIds.Count -ne 1) { throw 'Cannot derive one runtime version and ModID from McAgent.java' }
$version = $versions[0].Groups[1].Value
$modId = $modIds[0].Groups[1].Value
if ($modId -cne 'agentinminecraft' -or -not [regex]::IsMatch($version, '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$')) {
    throw 'Unexpected runtime ModID or version format'
}
$sourceMods = @(Read-ModTables ([IO.File]::ReadAllText((Join-Path $root 'src/main/resources/META-INF/neoforge.mods.toml'))))
if ($sourceMods.Count -ne 1 -or $sourceMods[0].modId -cne $modId -or $sourceMods[0].version -cne $version) {
    throw 'Source NeoForge metadata does not match the runtime version and ModID'
}
$jarName = "AgentInMinecraft-$version.jar"
if (-not $JarPath) { $JarPath = Join-Path $root "build/$jarName" }
$jar = [IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($JarPath))
Assert-NoLinks $jar
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Build the formal jar first with build.ps1 -NoInstall: $jar" }
if ([IO.Path]::GetFileName($jar) -cne $jarName) { throw 'The formal jar filename does not match the runtime version' }
$jarMods = @(Get-JarModMetadata $jar)
if ($jarMods.Count -ne 1 -or $jarMods[0].modId -cne $modId -or $jarMods[0].version -cne $version) {
    throw 'Built jar metadata does not match the current runtime version and ModID'
}
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    if (@($archive.Entries | Where-Object FullName -CEQ 'dev/mcagent/McAgent.class').Count -ne 1) { throw 'The formal jar is missing McAgent.class' }
    if (@($archive.Entries | Where-Object FullName -Match '^dev/mcagent/(Companion[^/]*|ChatLog|WsHub|WebSocketConn|fakeplayer/AgentIdle|NativeAgent|NativeBodies|NativeGameTools|NativeAgentConfig|ChatCompletionLoop|ChatCompletionHttp)(\$|\.class)').Count) {
        throw 'A retired chat, transport or built-in LLM class is present in the formal jar'
    }
} finally { $archive.Dispose() }

$files = @(
    'install.ps1', 'README.md', 'LICENSE', 'docs/AI-INSTALL.md', 'docs/MCP.md'
)
$payload = [Collections.Generic.SortedDictionary[string, byte[]]]::new([StringComparer]::Ordinal)
$payload.Add($jarName, [IO.File]::ReadAllBytes($jar))
foreach ($relative in $files) {
    # The ZIP's offline guide links only to documents present in the installed package.
    $source = if ($relative -eq 'README.md') { 'tools/release/README.md.in' } else { $relative }
    $path = [IO.Path]::GetFullPath((Join-Path $root $source))
    Assert-InDirectory $path $root
    Assert-NoLinks $path
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "A required release file is missing: $relative" }
    $payload.Add($relative, [IO.File]::ReadAllBytes($path))
}
$utf8 = [Text.UTF8Encoding]::new($false, $true)
foreach ($relative in @($payload.Keys)) {
    if (-not ($relative.EndsWith('.md') -or $relative.EndsWith('.svg'))) { continue }
    $text = $utf8.GetString($payload[$relative])
    $references = [Collections.Generic.List[string]]::new()
    foreach ($match in [regex]::Matches($text, '!?(?:\[[^\]\r\n]*\])\(\s*(?<target><[^>\r\n]+>|[^\s)]+)(?:\s+["''][^"'']*["''])?\s*\)')) {
        $references.Add($match.Groups['target'].Value)
    }
    foreach ($match in [regex]::Matches($text, '(?m)^\s{0,3}\[[^\]\r\n]+\]:\s*(?<target><[^>\r\n]+>|\S+)')) {
        $references.Add($match.Groups['target'].Value)
    }
    foreach ($match in [regex]::Matches($text, '(?i)\b(?:src|href|xlink:href)\s*=\s*["''](?<target>[^"'']+)["'']')) {
        $references.Add($match.Groups['target'].Value)
    }
    foreach ($reference in $references) { Assert-PackagedReference $relative $reference $payload }
}

$manifestFiles = @(
    foreach ($relative in $payload.Keys) {
        [ordered]@{ path = $relative; sha256 = Get-Sha256Bytes $payload[$relative]; size = $payload[$relative].LongLength }
    }
)
$manifest = [ordered]@{ schemaVersion = 1; modId = $modId; version = $version; modJar = $jarName; files = $manifestFiles }
$manifestText = ($manifest | ConvertTo-Json -Depth 6).Replace("`r`n", "`n") + "`n"
$payload.Add('release-manifest.json', $utf8.GetBytes($manifestText))
$output = [IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory))
Assert-NoLinks $output
if (Test-Path -LiteralPath $output -PathType Leaf) { throw 'OutputDirectory is a file' }
[IO.Directory]::CreateDirectory($output) | Out-Null
$destination = Join-Path $output "AgentInMinecraft-$version.zip"
$temporary = Join-Path $output ('.release-' + [guid]::NewGuid().ToString('N') + '.tmp')
Assert-InDirectory $destination $output
Assert-InDirectory $temporary $output
try {
    $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true, $utf8)
        try {
            foreach ($relative in $payload.Keys) {
                $entry = $zip.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = [DateTimeOffset]::new(2020, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
                $entry.ExternalAttributes = 0
                $entryStream = $entry.Open()
                try { $entryStream.Write($payload[$relative], 0, $payload[$relative].Length) }
                finally { $entryStream.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
    $zipHash = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    if (Test-Path -LiteralPath $destination) {
        Assert-NoLinks $destination
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf) -or
            (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $zipHash) {
            throw "A different release ZIP already exists. Preserve it and choose another OutputDirectory: $destination"
        }
    } else { [IO.File]::Move($temporary, $destination) }
    Write-Output "PACKAGE_ZIP=$destination"
    Write-Output "PACKAGE_VERSION=$version"
    Write-Output "PACKAGE_PAYLOAD_FILES=$($manifestFiles.Count)"
    Write-Output "PACKAGE_SHA256=$zipHash"
} finally {
    if (Test-Path -LiteralPath $temporary -PathType Leaf) {
        Assert-InDirectory $temporary $output
        Assert-NoLinks $temporary
        Remove-Item -LiteralPath $temporary
    }
}
