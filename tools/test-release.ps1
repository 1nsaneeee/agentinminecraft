#requires -Version 7.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$qaParent = [System.IO.Path]::GetFullPath((Join-Path $workspace 'build/release-qa'))
$qaRoot = Join-Path $qaParent ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N'))
$checks = 0
[void][System.IO.Directory]::CreateDirectory($qaRoot)

function Assert-Check([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "FAIL $Name" }
    $script:checks++
    Write-Output "PASS $Name"
}

function Get-QAPath([string]$RelativePath) {
    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $qaRoot $RelativePath))
    $prefix = $qaRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Test path escapes its named temporary directory'
    }
    return $fullPath
}

function Hash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

function Snapshot([string]$Directory) {
    $rows = @()
    foreach ($file in Get-ChildItem -LiteralPath $Directory -File -Recurse -Force | Sort-Object FullName) {
        $rows += [System.IO.Path]::GetRelativePath($Directory, $file.FullName).Replace('\', '/') + '=' + (Hash $file.FullName)
    }
    return [string]::Join("`n", $rows)
}

function New-ModJar([string]$Path, [string]$ModId, [string]$Version, [int]$PayloadMiB = 0, [switch]$MisleadingDescription, [switch]$AdditionalForeignMod) {
    if (-not $Path.StartsWith($qaRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Fixture jar must stay inside release-qa'
    }
    [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($Path))
    $description = if ($MisleadingDescription) { "description='''`n[[mods]]`nmodId=`"agentinminecraft`"`nversion=`"99.0.0`"`n'''`n" } else { '' }
    $metadata = "modLoader=`"javafml`"`nloaderVersion=`"[4,)`"`nlicense=`"MIT`"`n[[mods]]`nmodId=`"$ModId`"`nversion=`"$Version`"`n" + $description +
        "[[dependencies.$ModId]]`nmodId=`"agentinminecraft`"`ntype=`"optional`"`nversionRange=`"[0,)`"`nordering=`"NONE`"`nside=`"BOTH`"`n"
    if ($AdditionalForeignMod) { $metadata += "[[mods]]`nmodId=`"foreign_fixture`"`nversion=`"1.0.0`"`n" }
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
    try {
        $zip = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            $entry = $zip.CreateEntry('META-INF/neoforge.mods.toml')
            $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
            try { $writer.Write($metadata) } finally { $writer.Dispose() }
            $entry = $zip.CreateEntry('dev/mcagent/McAgent.class', [System.IO.Compression.CompressionLevel]::NoCompression)
            $payload = $entry.Open()
            try {
                $bytes = [System.Text.Encoding]::UTF8.GetBytes("synthetic fixture $ModId $Version")
                $payload.Write($bytes, 0, $bytes.Length)
                if ($PayloadMiB) {
                    $buffer = [byte[]]::new(1048576)
                    for ($index = 0; $index -lt $PayloadMiB; $index++) { $payload.Write($buffer, 0, $buffer.Length) }
                }
            } finally { $payload.Dispose() }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
}

function New-Instance([string]$Name, [switch]$OldJars, [int]$LargeFirstJarMiB = 0) {
    $directory = Get-QAPath "instances/$Name"
    [void][System.IO.Directory]::CreateDirectory((Join-Path $directory 'config'))
    $config = Join-Path $directory 'config/agentinminecraft.json'
    [System.IO.File]::WriteAllBytes($config, [byte[]](@(239, 187, 191) + [System.Text.Encoding]::UTF8.GetBytes(
        "{`r`n  `"token`": `"synthetic-preserve-token`",`n  `"unknownFutureField`": {`"keep`": [1, true, `"sentinel`"]}`r`n}`n")))
    [System.IO.File]::WriteAllText((Join-Path $directory 'config/mcagent.json'),
        '{"token":"synthetic-legacy-token","unknownLegacyField":{"future":42}}' + "`r`n", [System.Text.UTF8Encoding]::new($false))
    if ($OldJars) {
        $mods = Join-Path $directory 'mods'
        [void][System.IO.Directory]::CreateDirectory($mods)
        New-ModJar (Join-Path $mods 'AgentInMinecraft-0.3.2.jar') 'agentinminecraft' '0.3.2' $LargeFirstJarMiB
        New-ModJar (Join-Path $mods 'mcagent-0.2.9.jar') 'mcagent' '0.2.9'
        New-ModJar (Join-Path $mods 'mcagent-0.3.1.jar') 'agentinminecraft' '0.3.1'
        New-ModJar (Join-Path $mods 'mcagent-qa.jar') 'mcagent_qa' '1.0.0'
        New-ModJar (Join-Path $mods 'unrelated-1.0.0.jar') 'unrelated' '1.0.0'
        New-ModJar (Join-Path $mods 'mcagent-unrelated.jar') 'unrelated_fixture' '1.0.0' -MisleadingDescription
    }
    return $directory
}

function Copy-Package([string]$Name) {
    $destination = Get-QAPath "packages/$Name"
    [void][System.IO.Directory]::CreateDirectory($destination)
    foreach ($file in Get-ChildItem -LiteralPath $script:packageDirectory -File -Recurse) {
        $relative = [System.IO.Path]::GetRelativePath($script:packageDirectory, $file.FullName)
        $target = Get-QAPath "packages/$Name/$relative"
        [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($target))
        [System.IO.File]::Copy($file.FullName, $target, $false)
    }
    return $destination
}

function Write-Manifest([string]$Directory, $Manifest) {
    [System.IO.File]::WriteAllText((Join-Path $Directory 'release-manifest.json'), ($Manifest | ConvertTo-Json -Depth 20), [System.Text.UTF8Encoding]::new($false))
}

function Assert-Rejected([string]$Name, [scriptblock]$Action, [string]$Instance, [string]$MessagePattern = '.') {
    $before = Snapshot $Instance
    $failure = $null
    try { & $Action | Out-Null } catch { $failure = $_ }
    Assert-Check ($null -ne $failure -and $failure.Exception.Message -match $MessagePattern) "$Name rejected"
    Assert-Check ((Snapshot $Instance) -ceq $before) "$Name preserved the instance"
}

try {
    $versionSource = [System.IO.File]::ReadAllText((Join-Path $workspace 'src/main/java/dev/mcagent/McAgent.java'))
    $version = [regex]::Match($versionSource, 'public\s+static\s+final\s+String\s+VERSION\s*=\s*"([^"]+)"').Groups[1].Value
    if (-not $version) { throw 'No runtime version found' }
    $firstOutput = Get-QAPath 'generated/first'
    $secondOutput = Get-QAPath 'generated/second'
    & (Join-Path $PSScriptRoot 'package-release.ps1') -OutputDirectory $firstOutput
    & (Join-Path $PSScriptRoot 'package-release.ps1') -OutputDirectory $secondOutput
    $zipPath = Join-Path $firstOutput "AgentInMinecraft-$version.zip"
    $secondZip = Join-Path $secondOutput "AgentInMinecraft-$version.zip"
    Assert-Check ((Hash $zipPath) -ceq (Hash $secondZip)) 'release ZIP is byte-for-byte reproducible'
    $archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $names = [string[]]@($archive.Entries.FullName)
        $sorted = [string[]]$names.Clone()
        [Array]::Sort($sorted, [System.StringComparer]::Ordinal)
        Assert-Check ([string]::Join('|', $names) -ceq [string]::Join('|', $sorted)) 'ZIP entries are sorted'
        Assert-Check (@($archive.Entries | Where-Object { $_.LastWriteTime.DateTime.ToString('yyyy-MM-ddTHH:mm:ss') -cne '2020-01-01T00:00:00' }).Count -eq 0) 'ZIP entries have fixed timestamps'
        Assert-Check (@($names | Where-Object { $_ -match '(^|/)(harness|test|test-fixtures|archive|build|config|node_modules)(/|$)|mcagent-qa\.jar|mcagent-native|\.log$|\.env$' }).Count -eq 0) 'ZIP excludes QA, tests, archives, logs and credentials'
    } finally { $archive.Dispose() }
    $script:packageDirectory = Get-QAPath 'packages/pristine'
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $script:packageDirectory)
    $manifest = Get-Content -LiteralPath (Join-Path $script:packageDirectory 'release-manifest.json') -Raw | ConvertFrom-Json -Depth 20
    Assert-Check ($manifest.schemaVersion -eq 1 -and $manifest.modId -ceq 'agentinminecraft' -and $manifest.version -ceq $version) 'manifest matches the runtime identity'
    Assert-Check ($names.Count -eq $manifest.files.Count + 1) 'manifest accounts for every ZIP file'
    foreach ($entry in $manifest.files) {
        $file = Join-Path $script:packageDirectory $entry.path
        if (([System.IO.FileInfo]$file).Length -ne $entry.size -or (Hash $file) -cne $entry.sha256) { throw "Bad packaged hash: $($entry.path)" }
    }
    Assert-Check $true 'all packaged SHA256 values and sizes match extracted files'
    $installer = Join-Path $script:packageDirectory 'install.ps1'
    $fresh = New-Instance 'fresh'
    $before = Snapshot $fresh
    $preview = & $installer -InstancePath $fresh
    Assert-Check ($preview.Status -ceq 'Preview' -and (Snapshot $fresh) -ceq $before -and -not (Test-Path -LiteralPath (Join-Path $fresh 'mods'))) 'default preview creates no instance files or directories'
    $configBefore = Snapshot (Join-Path $fresh 'config')
    $installed = & $installer -InstancePath $fresh -Apply
    Assert-Check ($installed.Status -ceq 'Installed' -and (Hash $installed.TargetJar) -ceq (Hash (Join-Path $script:packageDirectory $manifest.modJar))) 'fresh install copies the exact formal jar'
    Assert-Check ((Snapshot (Join-Path $fresh 'config')) -ceq $configBefore) 'fresh install preserves current and legacy configuration bytes'
    $afterInstall = Snapshot $fresh
    $again = & $installer -InstancePath $fresh -Apply
    Assert-Check ($again.Status -ceq 'Unchanged' -and (Snapshot $fresh) -ceq $afterInstall) 'repeated apply is idempotent'

    $upgrade = New-Instance 'upgrade' -OldJars
    $upgradeMods = Join-Path $upgrade 'mods'
    $oldHashes = @{}
    foreach ($file in Get-ChildItem -LiteralPath $upgradeMods -File) { $oldHashes[$file.Name] = Hash $file.FullName }
    $upgradeConfig = Snapshot (Join-Path $upgrade 'config')
    $upgraded = & $installer -InstancePath $upgrade -Apply
    Assert-Check ($upgraded.Status -ceq 'Installed' -and $upgraded.ReplacedJars.Count -eq 3) 'upgrade identifies legacy mcagent ID, transitional ID and current own jars'
    foreach ($name in @('AgentInMinecraft-0.3.2.jar', 'mcagent-0.2.9.jar', 'mcagent-0.3.1.jar')) {
        Assert-Check (-not (Test-Path -LiteralPath (Join-Path $upgradeMods $name)) -and (Hash (Join-Path $upgraded.BackupDirectory $name)) -ceq $oldHashes[$name]) "upgrade retains verified backup $name outside mods"
    }
    foreach ($name in @('mcagent-qa.jar', 'unrelated-1.0.0.jar', 'mcagent-unrelated.jar')) {
        Assert-Check ((Hash (Join-Path $upgradeMods $name)) -ceq $oldHashes[$name]) "upgrade preserves $name despite dependency or description mentions"
    }
    Assert-Check ((Snapshot (Join-Path $upgrade 'config')) -ceq $upgradeConfig) 'upgrade preserves existing current and legacy configuration bytes'
    Assert-Check (-not $upgraded.BackupDirectory.StartsWith($upgradeMods + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) 'upgrade backups are outside mods'

    $wrongJar = Get-QAPath "fixtures/wrong-version/AgentInMinecraft-$version.jar"
    New-ModJar $wrongJar 'agentinminecraft' '0.0.0'
    $wrongJarFailure = $null
    try { & (Join-Path $PSScriptRoot 'package-release.ps1') -JarPath $wrongJar -OutputDirectory (Get-QAPath 'generated/rejected-version') | Out-Null }
    catch { $wrongJarFailure = $_ }
    Assert-Check ($null -ne $wrongJarFailure -and $wrongJarFailure.Exception.Message -match 'Built jar metadata does not match') 'packager rejects version drift in an otherwise correctly named jar'

    $ambiguous = New-Instance 'ambiguous-jar' -OldJars
    New-ModJar (Get-QAPath 'instances/ambiguous-jar/mods/mcagent-bundled.jar') 'mcagent' '0.2.9' -AdditionalForeignMod
    Assert-Rejected 'bundled own and foreign mods' { & $installer -InstancePath $ambiguous -Apply } $ambiguous 'ambiguously named jar'
    $corrupt = New-Instance 'corrupt-candidate' -OldJars
    [System.IO.File]::WriteAllText((Get-QAPath 'instances/corrupt-candidate/mods/mcagent-corrupt.jar'), 'not a jar')
    Assert-Rejected 'corrupt candidate jar' { & $installer -InstancePath $corrupt -Apply } $corrupt 'Cannot establish ownership'
    $missingMetadata = New-Instance 'missing-jar-metadata' -OldJars
    $missingPath = Get-QAPath 'instances/missing-jar-metadata/mods/mcagent-no-metadata.jar'
    $emptyArchive = [System.IO.Compression.ZipFile]::Open($missingPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try { [void]$emptyArchive.CreateEntry('README.txt') } finally { $emptyArchive.Dispose() }
    Assert-Rejected 'candidate jar missing metadata' { & $installer -InstancePath $missingMetadata -Apply } $missingMetadata 'Cannot establish ownership'

    $bad = Copy-Package 'tampered'
    [System.IO.File]::AppendAllText((Join-Path $bad 'docs/MCP.md'), 'tampered')
    $target = New-Instance 'tampered' -OldJars
    Assert-Rejected 'tampered package' { & (Join-Path $bad 'install.ps1') -InstancePath $target -Apply } $target 'Release file verification failed'
    $bad = Copy-Package 'missing-file'
    [System.IO.File]::Delete((Get-QAPath 'packages/missing-file/LICENSE'))
    $target = New-Instance 'missing-file' -OldJars
    Assert-Rejected 'missing payload file' { & (Join-Path $bad 'install.ps1') -InstancePath $target -Apply } $target 'Release file verification failed'
    $bad = Copy-Package 'schema'
    $modified = Get-Content -LiteralPath (Join-Path $bad 'release-manifest.json') -Raw | ConvertFrom-Json -Depth 20
    $modified.schemaVersion = 2
    Write-Manifest $bad $modified
    $target = New-Instance 'schema' -OldJars
    Assert-Rejected 'unsupported schema' { & (Join-Path $bad 'install.ps1') -InstancePath $target -Apply } $target 'invalid integer'
    $bad = Copy-Package 'missing-manifest-field'
    $modified = Get-Content -LiteralPath (Join-Path $bad 'release-manifest.json') -Raw | ConvertFrom-Json -Depth 20
    $modified.PSObject.Properties.Remove('files')
    Write-Manifest $bad $modified
    $target = New-Instance 'missing-manifest-field' -OldJars
    Assert-Rejected 'missing manifest field' { & (Join-Path $bad 'install.ps1') -InstancePath $target -Apply } $target 'schema does not match'
    $bad = Copy-Package 'traversal'
    $modified = Get-Content -LiteralPath (Join-Path $bad 'release-manifest.json') -Raw | ConvertFrom-Json -Depth 20
    $modified.files[0].path = '../outside.txt'
    Write-Manifest $bad $modified
    $target = New-Instance 'traversal' -OldJars
    Assert-Rejected 'manifest path traversal' { & (Join-Path $bad 'install.ps1') -InstancePath $target -Apply } $target 'Unsafe release path'
    $bad = Copy-Package 'unlisted'
    [System.IO.File]::WriteAllText((Join-Path $bad 'unexpected.txt'), 'synthetic extra file')
    $target = New-Instance 'unlisted' -OldJars
    Assert-Rejected 'unlisted payload' { & (Join-Path $bad 'install.ps1') -InstancePath $target -Apply } $target 'unlisted file'

    $locked = New-Instance 'locked-world' -OldJars
    $lockPath = Get-QAPath 'instances/locked-world/saves/fixture-world/session.lock'
    [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($lockPath))
    [System.IO.File]::WriteAllText($lockPath, 'synthetic session lock')
    $lockedBefore = Snapshot $locked
    $lock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $lockFailure = $null
    try { & $installer -InstancePath $locked -Apply | Out-Null } catch { $lockFailure = $_ }
    finally { $lock.Dispose() }
    Assert-Check ($null -ne $lockFailure) 'locked world is rejected'
    Assert-Check ((Snapshot $locked) -ceq $lockedBefore) 'locked world rejection preserves the instance'

    foreach ($pendingState in @('preparing', 'rollback-incomplete')) {
        $pendingInstance = New-Instance "pending-$pendingState" -OldJars
        $pendingDirectory = Get-QAPath "instances/pending-$pendingState/.agentinminecraft-backups/interrupted"
        [void][System.IO.Directory]::CreateDirectory($pendingDirectory)
        $recordPath = Join-Path $pendingDirectory 'transaction.json'
        [System.IO.File]::WriteAllText($recordPath, ([ordered]@{ SchemaVersion = 1; ModId = 'agentinminecraft'; State = $pendingState } | ConvertTo-Json))
        [System.IO.File]::Move((Get-QAPath "instances/pending-$pendingState/mods/AgentInMinecraft-0.3.2.jar"),
            (Get-QAPath "instances/pending-$pendingState/.agentinminecraft-backups/interrupted/AgentInMinecraft-0.3.2.jar"))
        Assert-Rejected "$pendingState preview" { & $installer -InstancePath $pendingInstance } $pendingInstance ([regex]::Escape($recordPath))
        Assert-Rejected "$pendingState apply" { & $installer -InstancePath $pendingInstance -Apply } $pendingInstance ([regex]::Escape($recordPath))
    }

    $outside = Get-QAPath 'outside-link-target'
    [void][System.IO.Directory]::CreateDirectory($outside)
    [System.IO.File]::WriteAllText((Join-Path $outside 'sentinel.txt'), 'must remain untouched')
    $outsideHash = Hash (Join-Path $outside 'sentinel.txt')
    $bad = Copy-Package 'linked-payload'
    [void](New-Item -ItemType Junction -Path (Get-QAPath 'packages/linked-payload/linked-data') -Target $outside)
    $modified = Get-Content -LiteralPath (Join-Path $bad 'release-manifest.json') -Raw | ConvertFrom-Json -Depth 20
    $modified.files += [PSCustomObject]@{ path = 'linked-data/sentinel.txt'; sha256 = $outsideHash;
        size = ([System.IO.FileInfo](Join-Path $outside 'sentinel.txt')).Length }
    Write-Manifest $bad $modified
    $target = New-Instance 'linked-payload' -OldJars
    Assert-Rejected 'linked payload escape' { & (Join-Path $bad 'install.ps1') -InstancePath $target -Apply } $target 'Linked paths'
    $linked = New-Instance 'linked-mods'
    [void](New-Item -ItemType Junction -Path (Get-QAPath 'instances/linked-mods/mods') -Target $outside)
    $linkedFailure = $null
    try { & $installer -InstancePath $linked -Apply | Out-Null } catch { $linkedFailure = $_ }
    Assert-Check ($null -ne $linkedFailure -and $linkedFailure.Exception.Message -match 'Linked paths') 'linked mods escape is rejected'
    Assert-Check ((Hash (Join-Path $outside 'sentinel.txt')) -ceq $outsideHash -and @(Get-ChildItem -LiteralPath $outside).Count -eq 1) 'linked destination remains untouched'

    $preparation = New-Instance 'failed-preparation' -OldJars
    $obstruction = Get-QAPath 'instances/failed-preparation/.agentinminecraft-backups'
    [System.IO.File]::WriteAllText($obstruction, 'synthetic backup destination conflict')
    Assert-Rejected 'backup preparation failure' { & $installer -InstancePath $preparation -Apply } $preparation
    [System.IO.File]::Delete($obstruction)
    $retry = & $installer -InstancePath $preparation -Apply
    Assert-Check ($retry.Status -ceq 'Installed') 'retry after preparation failure installs normally'

    if (-not ('ReleaseRollbackFault' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Threading;
public sealed class ReleaseRollbackFault : IDisposable {
    private readonly FileSystemWatcher watcher;
    private readonly string collisionName;
    private int entered;
    public bool Triggered { get; private set; }
    public string Error { get; private set; }
    public ReleaseRollbackFault(string root, string firstName, string collisionName) {
        this.collisionName = collisionName;
        watcher = new FileSystemWatcher(root, firstName) {
            IncludeSubdirectories = true, NotifyFilter = NotifyFilters.FileName
        };
        watcher.Created += OnCreated;
        watcher.EnableRaisingEvents = true;
    }
    private void OnCreated(object sender, FileSystemEventArgs args) {
        if (Interlocked.Exchange(ref entered, 1) != 0) return;
        try {
            string target = Path.Combine(Path.GetDirectoryName(args.FullPath), collisionName);
            using (var stream = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                byte[] bytes = System.Text.Encoding.UTF8.GetBytes("synthetic destination conflict");
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush(true);
            }
            Triggered = true;
        } catch (Exception error) { Error = error.GetType().Name; }
    }
    public void Dispose() { watcher.EnableRaisingEvents = false; watcher.Dispose(); }
}
'@
    }
    $rollback = New-Instance 'partial-rollback' -OldJars -LargeFirstJarMiB 32
    $rollbackMods = Join-Path $rollback 'mods'
    $rollbackBefore = Snapshot $rollbackMods
    $rollbackConfig = Snapshot (Join-Path $rollback 'config')
    $backupRoot = Get-QAPath 'instances/partial-rollback/.agentinminecraft-backups'
    [void][System.IO.Directory]::CreateDirectory($backupRoot)
    $fault = [ReleaseRollbackFault]::new($backupRoot, 'AgentInMinecraft-0.3.2.jar', 'mcagent-0.2.9.jar')
    $rollbackFailure = $null
    try { & $installer -InstancePath $rollback -Apply | Out-Null } catch { $rollbackFailure = $_ }
    finally { $fault.Dispose() }
    Assert-Check ($fault.Triggered -and -not $fault.Error -and $null -ne $rollbackFailure) 'filesystem fault interrupts the second backup move after the first'
    Assert-Check ((Snapshot $rollbackMods) -ceq $rollbackBefore -and (Snapshot (Join-Path $rollback 'config')) -ceq $rollbackConfig) 'partial failure restores all original jar and config bytes'
    $transactions = @(Get-ChildItem -LiteralPath $backupRoot -File -Recurse -Filter 'transaction.json')
    Assert-Check ($transactions.Count -eq 1 -and (Get-Content -LiteralPath $transactions[0].FullName -Raw | ConvertFrom-Json).State -ceq 'rolled-back') 'partial failure leaves an explicit rollback record'
    $retry = & $installer -InstancePath $rollback -Apply
    Assert-Check ($retry.Status -ceq 'Installed' -and (Hash $retry.TargetJar) -ceq (Hash (Join-Path $script:packageDirectory $manifest.modJar))) 'retry after a partial rollback installs the verified jar'
    Write-Output "RELEASE_CHECKS=$checks"
    Write-Output "RELEASE_QA_ROOT=$qaRoot"
} catch {
    Write-Output "RELEASE_QA_ROOT=$qaRoot"
    throw
}
