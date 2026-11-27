#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$InstancePath,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-NoReparsePoint([string]$Path) {
    $current = [System.IO.Path]::GetFullPath($Path)
    while ($current) {
        if ([System.IO.File]::Exists($current) -or [System.IO.Directory]::Exists($current)) {
            $attributes = [System.IO.File]::GetAttributes($current)
            if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Linked paths cannot be used for installation: $current"
            }
        }
        $parent = [System.IO.Directory]::GetParent($current)
        if ($null -eq $parent) { break }
        $current = $parent.FullName
    }
}

function Get-ChildPath([string]$Root, [string]$RelativePath) {
    if (-not $RelativePath -or $RelativePath.Length -gt 240 -or $RelativePath -notmatch '^[A-Za-z0-9._/-]+$') {
        throw 'Invalid release path'
    }
    foreach ($part in $RelativePath.Split('/')) {
        if (-not $part -or $part -in @('.', '..') -or $part.EndsWith('.') -or
            $part -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)') {
            throw 'Unsafe release path'
        }
    }
    $rootPrefix = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $fullPath = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($rootPrefix, $RelativePath))
    if (-not $fullPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Release path escapes its directory'
    }
    Assert-NoReparsePoint $fullPath
    return $fullPath
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-StreamSha256([System.IO.Stream]$Stream) {
    $Stream.Position = 0
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { return [System.Convert]::ToHexString($algorithm.ComputeHash($Stream)).ToLowerInvariant() }
    finally { $algorithm.Dispose(); $Stream.Position = 0 }
}

function Find-TripleQuote([string]$Text, [string]$Quote, [int]$Start = 0) {
    $offset = $Start
    while ($offset -lt $Text.Length) {
        $index = $Text.IndexOf($Quote, $offset, [System.StringComparison]::Ordinal)
        if ($index -lt 0) { return -1 }
        $slashes = 0
        for ($position = $index - 1; $position -ge 0 -and $Text[$position] -eq '\'; $position--) { $slashes++ }
        if ($Quote -eq "'''" -or $slashes % 2 -eq 0) { return $index }
        $offset = $index + 3
    }
    return -1
}

function Read-JarMods([string]$Path) {
    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
        $entries = @($archive.Entries | Where-Object { $_.FullName -ceq 'META-INF/neoforge.mods.toml' })
        if ($entries.Count -eq 0) { return }
        if ($entries.Count -ne 1 -or $entries[0].Length -gt 1048576) { throw [System.IO.InvalidDataException]::new('Invalid mod metadata') }
        $metadataStream = $entries[0].Open()
        $buffer = [byte[]]::new(1048577)
        $count = 0
        try {
            while ($count -lt $buffer.Length) {
                $read = $metadataStream.Read($buffer, $count, $buffer.Length - $count)
                if ($read -eq 0) { break }
                $count += $read
            }
        } finally { $metadataStream.Dispose() }
        if ($count -gt 1048576) { throw [System.IO.InvalidDataException]::new('Expanded mod metadata is too large') }
        $text = [System.Text.UTF8Encoding]::new($false, $true).GetString($buffer, 0, $count)
        $records = [System.Collections.Generic.List[object]]::new()
        $section = $null
        $multiline = $null
        foreach ($line in $text -split "`r?`n") {
            if ($null -ne $multiline) {
                if ((Find-TripleQuote $line $multiline) -ge 0) { $multiline = $null }
                continue
            }
            if ($line -match '^\s*[^#=]+\s*=\s*("""|\x27\x27\x27)') {
                $quote = $Matches[1]
                $start = $line.IndexOf($quote, [System.StringComparison]::Ordinal) + 3
                if ((Find-TripleQuote $line $quote $start) -lt 0) { $multiline = $quote }
                continue
            }
            if ($line -match '^\s*\[\[mods\]\]\s*(?:#.*)?$') {
                $section = [ordered]@{ ModId = $null; Version = $null }
                $records.Add($section)
            } elseif ($line -match '^\s*\[') {
                $section = $null
            } elseif ($null -ne $section) {
                $field = [regex]::Match($line, '^\s*(?<key>modId|version)\s*=\s*(?:"(?<double>[^"]+)"|''(?<single>[^'']+)'')\s*(?:#.*)?$')
                if ($field.Success) {
                    $key = if ($field.Groups['key'].Value -ceq 'modId') { 'ModId' } else { 'Version' }
                    if ($null -ne $section[$key]) { throw [System.IO.InvalidDataException]::new('Duplicate mod metadata') }
                    $section[$key] = if ($field.Groups['double'].Success) { $field.Groups['double'].Value } else { $field.Groups['single'].Value }
                } elseif ($line -match '^\s*(modId|version)\s*=') {
                    throw [System.IO.InvalidDataException]::new('Unsupported mod metadata field')
                }
            }
        }
        if ($null -ne $multiline) { throw [System.IO.InvalidDataException]::new('Unterminated mod metadata') }
        foreach ($record in $records) {
            if ($null -eq $record.ModId -or $null -eq $record.Version) { throw [System.IO.InvalidDataException]::new('Incomplete mod metadata') }
        }
        foreach ($record in $records) { [PSCustomObject]$record }
    } catch [System.IO.InvalidDataException] {
        # Unreadable metadata cannot establish ownership of an installed jar.
        return
    } finally {
        if ($null -ne $archive) { $archive.Dispose() }
    }
}

function Assert-Integer($Value, [long]$Minimum, [long]$Maximum) {
    if (($Value -isnot [int] -and $Value -isnot [long]) -or $Value -lt $Minimum -or $Value -gt $Maximum) {
        throw 'Release manifest contains an invalid integer'
    }
}

function Assert-Properties($Value, [string[]]$Expected) {
    if ($null -eq $Value -or $Value -isnot [PSCustomObject]) { throw 'Release manifest contains an invalid object' }
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    if ([string]::Join('|', $actual) -cne [string]::Join('|', @($Expected | Sort-Object))) {
        throw 'Release manifest schema does not match version 1'
    }
}

function Read-Release([string]$PackageRoot) {
    Assert-NoReparsePoint $PackageRoot
    $manifestPath = Get-ChildPath $PackageRoot 'release-manifest.json'
    if (-not [System.IO.File]::Exists($manifestPath) -or ([System.IO.FileInfo]$manifestPath).Length -gt 1048576) {
        throw 'release-manifest.json is missing or too large'
    }
    $raw = [System.IO.File]::ReadAllText($manifestPath, [System.Text.UTF8Encoding]::new($false, $true))
    $manifest = ConvertFrom-Json -InputObject $raw -Depth 20
    Assert-Properties $manifest @('schemaVersion', 'modId', 'version', 'modJar', 'files')
    Assert-Integer $manifest.schemaVersion 1 1
    if ($manifest.modId -cne 'agentinminecraft' -or $manifest.version -isnot [string] -or
        $manifest.version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?$' -or
        $manifest.version.Length -gt 96 -or $manifest.modJar -cne "AgentInMinecraft-$($manifest.version).jar") {
        throw 'Release manifest has an invalid mod identity'
    }
    if ($manifest.files -isnot [array] -or $manifest.files.Count -lt 2 -or $manifest.files.Count -gt 256) {
        throw 'Release manifest has an invalid file list'
    }
    $listed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest.files) {
        Assert-Properties $entry @('path', 'sha256', 'size')
        if ($entry.path -isnot [string] -or -not $listed.Add($entry.path) -or $entry.path -ceq 'release-manifest.json' -or
            $entry.sha256 -isnot [string] -or $entry.sha256 -notmatch '^[0-9a-fA-F]{64}$') {
            throw 'Release manifest has a duplicate or invalid file entry'
        }
        Assert-Integer $entry.size 0 268435456
        $filePath = Get-ChildPath $PackageRoot $entry.path
        if (-not [System.IO.File]::Exists($filePath) -or ([System.IO.FileInfo]$filePath).Length -ne $entry.size -or
            (Get-Sha256 $filePath) -cne $entry.sha256.ToLowerInvariant()) {
            throw "Release file verification failed: $($entry.path)"
        }
    }
    if (-not $listed.Contains($manifest.modJar) -or -not $listed.Contains('install.ps1')) {
        throw 'Release manifest omits the mod jar or installer'
    }
    $pendingDirectories = [System.Collections.Generic.Stack[string]]::new()
    $pendingDirectories.Push($PackageRoot)
    $seen = 0
    while ($pendingDirectories.Count -gt 0) {
        foreach ($item in Get-ChildItem -LiteralPath $pendingDirectories.Pop() -Force) {
            $seen++
            if ($seen -gt 1024) { throw 'Release contains too many entries' }
            Assert-NoReparsePoint $item.FullName
            if ($item.PSIsContainer) { $pendingDirectories.Push($item.FullName); continue }
            $relative = [System.IO.Path]::GetRelativePath($PackageRoot, $item.FullName).Replace('\', '/')
            if ($relative -cne 'release-manifest.json' -and -not $listed.Contains($relative)) {
                throw "Release contains an unlisted file: $relative"
            }
        }
    }
    $jarPath = Get-ChildPath $PackageRoot $manifest.modJar
    $modRecords = @(Read-JarMods $jarPath)
    if ($modRecords.Count -ne 1 -or $modRecords[0].ModId -cne $manifest.modId -or $modRecords[0].Version -cne $manifest.version) {
        throw 'Packaged jar does not match the release mod ID and version'
    }
    $jarEntry = @($manifest.files | Where-Object { $_.path -ceq $manifest.modJar })[0]
    return [PSCustomObject]@{ Manifest = $manifest; JarPath = $jarPath; Hash = $jarEntry.sha256.ToLowerInvariant() }
}

function Assert-InstanceStopped([string]$Root, [object[]]$OwnJars) {
    $needle = $Root.Replace('/', '\').TrimEnd('\')
    foreach ($process in Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'") {
        if ([string]::IsNullOrWhiteSpace($process.CommandLine)) {
            throw "Cannot verify the instance state while Java process $($process.ProcessId) has no readable command line"
        }
        $command = $process.CommandLine.Replace('/', '\')
        $position = $command.IndexOf($needle, [System.StringComparison]::OrdinalIgnoreCase)
        if ($position -ge 0) {
            $end = $position + $needle.Length
            if ($end -eq $command.Length -or $command[$end] -in @('\', '"', "'", ' ', "`t", "`r", "`n")) {
                throw "Minecraft or Java is using this instance (PID $($process.ProcessId)). Exit it before installing"
            }
        }
    }
    $worldDirectories = [System.Collections.Generic.List[string]]::new()
    foreach ($directory in Get-ChildItem -LiteralPath $Root -Directory -Force) {
        Assert-NoReparsePoint $directory.FullName
        if ($directory.Name -ceq 'saves') {
            foreach ($world in Get-ChildItem -LiteralPath $directory.FullName -Directory -Force) {
                Assert-NoReparsePoint $world.FullName
                $worldDirectories.Add($world.FullName)
            }
        } else { $worldDirectories.Add($directory.FullName) }
    }
    foreach ($world in $worldDirectories) {
        $lockPath = Join-Path $world 'session.lock'
        if (-not [System.IO.File]::Exists($lockPath)) { continue }
        Assert-NoReparsePoint $lockPath
        $lock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::None)
        $lock.Dispose()
    }
    foreach ($jar in $OwnJars) {
        $lock = [System.IO.File]::Open($jar.Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::None)
        $lock.Dispose()
    }
}

function Assert-NoPendingInstallation([string]$Root) {
    $backupRoot = Get-ChildPath $Root '.agentinminecraft-backups'
    if ([System.IO.File]::Exists($backupRoot)) { throw 'The installation backup directory is occupied by a file' }
    if (-not [System.IO.Directory]::Exists($backupRoot)) { return }
    foreach ($directory in Get-ChildItem -LiteralPath $backupRoot -Directory -Force) {
        Assert-NoReparsePoint $directory.FullName
        $recordPath = Get-ChildPath $directory.FullName 'transaction.json'
        if (-not [System.IO.File]::Exists($recordPath)) {
            if (@(Get-ChildItem -LiteralPath $directory.FullName -Force).Count) {
                throw "Installation backup has no recovery record: $recordPath"
            }
            continue
        }
        if (([System.IO.FileInfo]$recordPath).Length -gt 1048576) { throw "Installation recovery record is too large: $recordPath" }
        try {
            $record = [System.IO.File]::ReadAllText($recordPath, [System.Text.UTF8Encoding]::new($false, $true)) | ConvertFrom-Json -Depth 12
            $stateProperty = $record.PSObject.Properties['State']
            if ($null -eq $stateProperty -or $stateProperty.Value -isnot [string]) { throw 'Missing transaction state' }
        } catch { throw "Installation recovery record is unreadable: $recordPath" }
        if ($stateProperty.Value -cnotin @('installed', 'rolled-back')) {
            throw "Unfinished installation requires recovery before preview or apply: $recordPath"
        }
    }
}

$packageRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$release = Read-Release $packageRoot
$instanceRoot = [System.IO.Path]::GetFullPath($InstancePath)
if (-not [System.IO.Directory]::Exists($instanceRoot) -or
    $instanceRoot.TrimEnd('\', '/') -ceq [System.IO.Path]::GetPathRoot($instanceRoot).TrimEnd('\', '/')) {
    throw 'InstancePath must name an existing instance directory below a drive root'
}
Assert-NoReparsePoint $instanceRoot
Assert-NoPendingInstallation $instanceRoot
$modsDirectory = Get-ChildPath $instanceRoot 'mods'
if ([System.IO.File]::Exists($modsDirectory)) { throw 'The instance mods path is a file' }
$targetPath = Get-ChildPath $modsDirectory $release.Manifest.modJar
$ownJars = [System.Collections.Generic.List[object]]::new()
if ([System.IO.Directory]::Exists($modsDirectory)) {
    foreach ($file in Get-ChildItem -LiteralPath $modsDirectory -File -Filter '*.jar' | Sort-Object Name) {
        Assert-NoReparsePoint $file.FullName
        $modRecords = @(Read-JarMods $file.FullName)
        if ($file.Name -match '^(AgentInMinecraft-|mcagent-).+\.jar$' -and $modRecords.Count -eq 0) {
            throw "Cannot establish ownership of an existing candidate jar. Preserve it and inspect its metadata: $($file.FullName)"
        }
        if (-not @($modRecords | Where-Object { $_.ModId -cin @('agentinminecraft', 'mcagent') }).Count) { continue }
        $currentName = $file.Name -match '^AgentInMinecraft-.+\.jar$'
        $legacyName = $file.Name -match '^mcagent-.+\.jar$'
        $recognized = $modRecords.Count -eq 1 -and
            (($currentName -and $modRecords[0].ModId -ceq 'agentinminecraft') -or
            ($legacyName -and $modRecords[0].ModId -cin @('agentinminecraft', 'mcagent')))
        if (-not $recognized) {
            throw "An ambiguously named jar contains this mod ID: $($file.Name)"
        }
        $ownJars.Add([PSCustomObject]@{ Name = $file.Name; Path = $file.FullName; Hash = Get-Sha256 $file.FullName; Size = $file.Length })
    }
}
if ([System.IO.Directory]::Exists($targetPath) -or ([System.IO.File]::Exists($targetPath) -and
    -not @($ownJars | Where-Object { [string]::Equals($_.Path, $targetPath, [System.StringComparison]::OrdinalIgnoreCase) }).Count)) {
    throw 'The target jar name is occupied by a file or directory whose ownership could not be verified'
}
Assert-InstanceStopped $instanceRoot ($ownJars.ToArray())
$unchanged = $ownJars.Count -eq 1 -and [string]::Equals($ownJars[0].Path, $targetPath, [System.StringComparison]::OrdinalIgnoreCase) -and $ownJars[0].Hash -ceq $release.Hash
if (-not $Apply -or $unchanged) {
    [PSCustomObject]@{ Status = $(if ($unchanged) { 'Unchanged' } else { 'Preview' }); Version = $release.Manifest.version;
        InstancePath = $instanceRoot; TargetJar = $targetPath; ReplacedJars = @($ownJars | ForEach-Object { $_.Name }); BackupDirectory = $null }
    return
}

$transactionId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N')
$backupDirectory = Get-ChildPath $instanceRoot ".agentinminecraft-backups/$transactionId"
$stagePath = Get-ChildPath $modsDirectory ".agentinminecraft-stage-$transactionId.tmp"
$transactionPath = Get-ChildPath $backupDirectory 'transaction.json'
$moved = [System.Collections.Generic.List[object]]::new()
$locks = [System.Collections.Generic.List[System.IO.FileStream]]::new()
$installed = $false
$transaction = [ordered]@{ SchemaVersion = 1; ModId = 'agentinminecraft'; Version = $release.Manifest.version;
    State = 'preparing'; InstalledJar = $release.Manifest.modJar; InstalledSha256 = $release.Hash; Originals = @() }
try {
    foreach ($jar in $ownJars) {
        $stream = [System.IO.File]::Open($jar.Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Delete)
        $locks.Add($stream)
        if ($stream.Length -ne $jar.Size -or (Get-StreamSha256 $stream) -cne $jar.Hash) { throw 'An installed jar changed after preview validation' }
        $moved.Add([PSCustomObject]@{ Original = $jar.Path; Backup = Get-ChildPath $backupDirectory $jar.Name;
            Name = $jar.Name; Sha256 = $jar.Hash; Size = $jar.Size; Stream = $stream; Moved = $false })
    }
    [void][System.IO.Directory]::CreateDirectory($modsDirectory)
    Assert-NoReparsePoint $modsDirectory
    [System.IO.File]::Copy($release.JarPath, $stagePath, $false)
    if ((Get-Sha256 $stagePath) -cne $release.Hash) { throw 'Staged jar hash mismatch' }
    Assert-InstanceStopped $instanceRoot @()
    [void][System.IO.Directory]::CreateDirectory($backupDirectory)
    Assert-NoReparsePoint $backupDirectory
    $transaction.Originals = @($moved | ForEach-Object { [ordered]@{ Name = $_.Name; Sha256 = $_.Sha256; Size = $_.Size } })
    [System.IO.File]::WriteAllText($transactionPath, ($transaction | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    foreach ($item in $moved) {
        Assert-NoReparsePoint $item.Original
        Assert-NoReparsePoint $item.Backup
        [System.IO.File]::Move($item.Original, $item.Backup)
        $item.Moved = $true
        $item.Stream.Dispose()
        if ((Get-Sha256 $item.Backup) -cne $item.Sha256) { throw 'Backed-up jar hash mismatch' }
    }
    Assert-NoReparsePoint $targetPath
    [System.IO.File]::Move($stagePath, $targetPath)
    $installed = $true
    if ((Get-Sha256 $targetPath) -cne $release.Hash) { throw 'Installed jar hash mismatch' }
    foreach ($item in $moved) {
        if ((Get-Sha256 $item.Backup) -cne $item.Sha256) { throw 'Final backup hash mismatch' }
    }
    $transaction.State = 'installed'
    [System.IO.File]::WriteAllText($transactionPath, ($transaction | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
} catch {
    $failure = $_
    $rollbackErrors = [System.Collections.Generic.List[string]]::new()
    if ($installed) {
        try {
            if ((Get-Sha256 $targetPath) -cne $release.Hash) { throw 'Installed jar changed during rollback' }
            [System.IO.File]::Delete($targetPath)
        } catch { $rollbackErrors.Add('new jar could not be removed safely') }
    }
    for ($index = $moved.Count - 1; $index -ge 0; $index--) {
        $item = $moved[$index]
        if (-not $item.Moved) { continue }
        try {
            Assert-NoReparsePoint $item.Backup
            Assert-NoReparsePoint $item.Original
            if ((Get-Sha256 $item.Backup) -cne $item.Sha256) { throw 'Backup changed during rollback' }
            [System.IO.File]::Move($item.Backup, $item.Original)
        } catch { $rollbackErrors.Add("original jar could not be restored: $($item.Name)") }
    }
    if ([System.IO.Directory]::Exists($backupDirectory)) {
        $transaction.State = if ($rollbackErrors.Count) { 'rollback-incomplete' } else { 'rolled-back' }
        try { [System.IO.File]::WriteAllText($transactionPath, ($transaction | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false)) }
        catch { $rollbackErrors.Add('transaction record could not be updated') }
    }
    if ($rollbackErrors.Count) {
        throw "Installation failed and requires recovery from $backupDirectory. $([string]::Join(', ', $rollbackErrors))"
    }
    throw $failure
} finally {
    foreach ($stream in $locks) { $stream.Dispose() }
    if ([System.IO.File]::Exists($stagePath)) {
        if ((Get-Sha256 $stagePath) -ceq $release.Hash) { [System.IO.File]::Delete($stagePath) }
    }
}
[PSCustomObject]@{ Status = 'Installed'; Version = $release.Manifest.version; InstancePath = $instanceRoot;
    TargetJar = $targetPath; ReplacedJars = @($ownJars | ForEach-Object { $_.Name }); BackupDirectory = $backupDirectory }
