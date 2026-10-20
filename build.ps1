#!/usr/bin/env pwsh
# Build the mcagent mod with plain javac against the launcher's own library cache.
# No Gradle, no network: every jar below is already on disk because the game runs from it.
param(
    [switch]$NoInstall,
    [string]$Instance = 'E:\Minecraft\.minecraft\versions\1.21.1(1)\.minecraft',
    # Where to find optional integration jars (FTB Quests and friends) for the
    # compile classpath. Kept separate from -Instance on purpose: installing into a
    # clean instance must not change what the build can compile against, otherwise
    # a pack without FTB fails to build at all.
    [string]$Libs = 'E:\Minecraft\.minecraft\versions\1.21.1(1)\.minecraft'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$version = '0.3.0'
$name = 'AgentInMinecraft'
$lib = 'C:\Users\CJ\AppData\Roaming\PrismLauncher\libraries'
$jdk = 'C:\Program Files\Java\jdk-21.0.10'
$out = Join-Path $root 'build'
$classes = Join-Path $out 'classes'

$required = @(
    "$lib\net\neoforged\neoforge\21.1.233\neoforge-21.1.233-client.jar",
    "$lib\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar",
    "$lib\net\neoforged\neoforge\21.1.233\neoforge-21.1.233-universal.jar",
    "$lib\net\neoforged\fancymodloader\loader\4.0.42\loader-4.0.42.jar",
    "$lib\net\neoforged\bus\8.0.5\bus-8.0.5.jar",
    "$lib\net\neoforged\coremods\7.0.3\coremods-7.0.3.jar",
    "$lib\net\neoforged\mergetool\2.0.0\mergetool-2.0.0-api.jar",
    "$lib\com\mojang\authlib\6.0.54\authlib-6.0.54.jar",
    "$lib\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar",
    "$lib\com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar",
    "$lib\com\mojang\datafixerupper\8.0.16\datafixerupper-8.0.16.jar",
    "$lib\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar"
)

foreach ($jar in $required) {
    if (-not (Test-Path $jar)) { throw "missing library jar: $jar" }
}

# Optional integrations: compiled against the pack's own mod jars when present.
# The classes are only touched reflectively at runtime, so a pack without these
# mods still loads fine.
foreach ($pattern in @('ftb-quests-*.jar', 'ftb-library-*.jar', 'ftb-teams-*.jar')) {
    $hit = Get-ChildItem (Join-Path $Libs 'mods') -Filter $pattern -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $hit) {
        throw "optional integration jar '$pattern' not found in $Libs\mods - pass -Libs <instance with that mod>"
    }
    $required += $hit.FullName
}

# Connection extends io.netty SimpleChannelInboundHandler, so the netty jars have to be
# on the compile classpath even though the running game supplies them.
$required += Get-ChildItem "$lib\io\netty" -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty FullName

# Mixin annotations live in sponge-mixin, the same jar NeoForge loads at runtime.
$mixinJar = Get-ChildItem "$lib\net\fabricmc\sponge-mixin" -Recurse -Filter "sponge-mixin-*.jar" -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if (-not $mixinJar) { throw "missing sponge-mixin jar under $lib" }
$required += $mixinJar.FullName

$cp = ($required -join ';')

# The entrypoint annotation and the metadata must agree. They are two separate
# strings in two files, and a mismatch only surfaces at startup as
# "contains mod entrypoint class ... for mod with id X, which does not exist",
# which looks like a packaging bug rather than a one-word typo.
$declaredId = (Select-String -Path (Join-Path $root 'src\main\resources\META-INF\neoforge.mods.toml') `
        -Pattern '^modId="([^"]+)"' | Select-Object -First 1).Matches[0].Groups[1].Value
$sourceId = (Select-String -Path (Join-Path $root 'src\main\java\dev\mcagent\McAgent.java') `
        -Pattern 'MOD_ID = "([^"]+)"' | Select-Object -First 1).Matches[0].Groups[1].Value
if ($declaredId -ne $sourceId) {
    throw "mod id mismatch: toml says '$declaredId', McAgent.MOD_ID says '$sourceId'"
}
Write-Host "mod id: $declaredId" -ForegroundColor DarkGray

if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "compiling $($sources.Count) source files..." -ForegroundColor Cyan

& "$jdk\bin\javac.exe" -proc:none -encoding UTF-8 -nowarn -Xlint:none -d $classes -cp $cp $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Copy-Item (Join-Path $root 'src\main\resources\*') $classes -Recurse -Force

$jarPath = Join-Path $out "$name-$version.jar"
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& "$jdk\bin\jar.exe" --create --file $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

Write-Host "built $jarPath ($([math]::Round((Get-Item $jarPath).Length/1KB,1)) KiB)" -ForegroundColor Green

if (-not $NoInstall) {
    $mods = Join-Path $Instance 'mods'
    if (-not (Test-Path $mods)) { throw "mods folder not found: $mods" }
    # Remove both the current name and the pre-rename one: identical mod id twice in
    # mods/ makes FML refuse to load, and the old jar would also fight for the port.
    Get-ChildItem $mods -Filter 'mcagent-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-ChildItem $mods -Filter "$name-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jarPath (Join-Path $mods "$name-$version.jar") -Force
    Write-Host "installed into $mods" -ForegroundColor Green
}
