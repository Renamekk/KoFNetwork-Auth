@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "KOFAUTH_DEPLOY_SELF=%~f0"
set "KOFAUTH_DEPLOY_ACTION=%~1"
if not defined KOFAUTH_DEPLOY_ACTION set "KOFAUTH_DEPLOY_ACTION=deploy"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$p=$env:KOFAUTH_DEPLOY_SELF; $m='#==KOFAUTH_POWERSHELL=='; $r=[IO.File]::ReadAllText($p); $i=$r.LastIndexOf($m); if($i -lt 0){[Console]::Error.WriteLine('Embedded PowerShell payload was not found.'); exit 2}; $s=$r.Substring($i+$m.Length); & ([ScriptBlock]::Create($s)) -SelfPath $p -Action $env:KOFAUTH_DEPLOY_ACTION"
set "KOFAUTH_EXIT=%ERRORLEVEL%"
if not "%KOFAUTH_EXIT%"=="0" (
  echo.
  echo KoFAuth command failed with exit code %KOFAUTH_EXIT%.
  if /I not "%KOFAUTH_DEPLOY_ACTION%"=="runner-backend" if /I not "%KOFAUTH_DEPLOY_ACTION%"=="runner-limbo" if /I not "%KOFAUTH_DEPLOY_ACTION%"=="runner-velocity" pause
)
exit /b %KOFAUTH_EXIT%

#==KOFAUTH_POWERSHELL==
param(
    [Parameter(Mandatory = $true)][string] $SelfPath,
    [Parameter(Mandatory = $true)][string] $Action
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$script:Ascii = [System.Text.Encoding]::ASCII
try { [Console]::InputEncoding = $script:Utf8NoBom } catch {}
$script:SelfPath = [IO.Path]::GetFullPath($SelfPath)
$script:Root = [IO.Path]::GetDirectoryName($script:SelfPath)
$script:Release = Join-Path $script:Root 'deploy'
$script:Managed = Join-Path $script:Root '_kofauth'
$script:Runtime = Join-Path $script:Managed 'runtime'
$script:Config = Join-Path $script:Managed 'config'
$script:Artifacts = Join-Path $script:Managed 'artifacts'
$script:Backups = Join-Path $script:Managed 'backups'
$script:Commands = Join-Path $script:Managed 'commands'
$script:PidDir = Join-Path $script:Runtime 'pids'
$script:CommandDir = Join-Path $script:Runtime 'control'
$script:Limbo = Join-Path $script:Managed 'limbo'
$script:EnvFile = Join-Path $script:Config 'kofauth.env'
$script:WebConfig = Join-Path $script:Config 'webapi'
$script:StateFile = Join-Path $script:Runtime 'state.json'
$script:ProjectFile = Join-Path $script:Runtime 'compose-project.txt'
$script:OverrideFile = Join-Path $script:Runtime 'compose.windows.yml'
$script:PendingTransactionFile = Join-Path $script:Runtime 'pending-transaction.json'
$script:LastGoodDirectory = Join-Path $script:Runtime 'last-good'
$script:BootstrapIdentityFile = Join-Path $script:Runtime 'bootstrap-identity.json'
$script:ReleaseComposeFile = Join-Path $script:Release 'docker-compose.yml'
$script:ComposeFile = Join-Path $script:Runtime 'compose.base.yml'

# The first Windows test stand used _kofauth\proxy.  Keep using it when it
# contains the real proxy data; silently creating a second empty Velocity tree
# would orphan velocity.toml, plugins and its forwarding secret.
$newVelocity = Join-Path $script:Managed 'velocity'
$legacyVelocity = Join-Path $script:Managed 'proxy'
$storedVelocityLayout = $null
if ([IO.File]::Exists($script:StateFile)) {
    try {
        $earlyState = [IO.File]::ReadAllText($script:StateFile) | ConvertFrom-Json
        if ($earlyState.PSObject.Properties.Name -contains 'velocityDirectory') { $storedVelocityLayout = [string]$earlyState.velocityDirectory }
    } catch {}
}
$newVelocityEvidence = [IO.File]::Exists((Join-Path $newVelocity 'velocity.jar')) -or [IO.File]::Exists((Join-Path $newVelocity 'velocity.toml')) -or [IO.Directory]::Exists((Join-Path $newVelocity 'plugins'))
$legacyVelocityEvidence = [IO.File]::Exists((Join-Path $legacyVelocity 'velocity.jar')) -or [IO.File]::Exists((Join-Path $legacyVelocity 'velocity.toml')) -or [IO.Directory]::Exists((Join-Path $legacyVelocity 'plugins'))
$script:VelocityLayoutConflict = $false
if ($storedVelocityLayout -eq '_kofauth\proxy') { $script:Velocity = $legacyVelocity }
elseif ($storedVelocityLayout -eq '_kofauth\velocity') { $script:Velocity = $newVelocity }
elseif ($newVelocityEvidence -and $legacyVelocityEvidence) { $script:Velocity = $newVelocity; $script:VelocityLayoutConflict = $true }
elseif ($legacyVelocityEvidence) { $script:Velocity = $legacyVelocity }
else { $script:Velocity = $newVelocity }
$script:TransactionStamp = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$script:TransactionBackup = Join-Path $script:Backups $script:TransactionStamp
$script:LogFile = $null
$script:LockHandle = $null
$script:ProjectName = $null
$script:Env = @{}
$script:State = $null
$script:ConfigChanged = New-Object 'System.Collections.Generic.HashSet[string]'
$script:Conflicts = New-Object 'System.Collections.Generic.List[string]'
$script:ManagedDesired = [ordered]@{}
$script:InteractiveStart = $false
$script:RollbackEntries = New-Object 'System.Collections.Generic.List[object]'
$script:RollbackTracking = $false
$script:RollbackInProgress = $false
$script:DeploymentStarted = $false
$script:InitialRunning = @{}
$script:InitialServices = @{}
$script:TransactionPhase = 'none'
$script:DatabaseMayHaveMigrated = $false
$script:DataInfrastructureMayHaveChanged = $false

function Write-Info([string] $Message) {
    Write-Host ('[INFO]  ' + $Message) -ForegroundColor Cyan
    if ($script:LogFile) { [IO.File]::AppendAllText($script:LogFile, ('[INFO]  ' + $Message + [Environment]::NewLine), $script:Utf8NoBom) }
}

function Write-Ok([string] $Message) {
    Write-Host ('[OK]    ' + $Message) -ForegroundColor Green
    if ($script:LogFile) { [IO.File]::AppendAllText($script:LogFile, ('[OK]    ' + $Message + [Environment]::NewLine), $script:Utf8NoBom) }
}

function Write-WarningLine([string] $Message) {
    Write-Host ('[WARN]  ' + $Message) -ForegroundColor Yellow
    if ($script:LogFile) { [IO.File]::AppendAllText($script:LogFile, ('[WARN]  ' + $Message + [Environment]::NewLine), $script:Utf8NoBom) }
}

function Fail([string] $Message) {
    throw $Message
}

function Assert-VelocityLayout {
    if ($script:VelocityLayoutConflict) {
        Fail 'Both _kofauth\proxy and _kofauth\velocity contain proxy data, while state.json does not select one. Move the unused directory aside or restore state.json; neither tree was changed.'
    }
}

function Get-ObjectValue($Object, [string] $Name, $Default = $null) {
    if ($Object -is [System.Collections.IDictionary] -and $Object.Contains($Name)) { return $Object[$Name] }
    if ($null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name) { return $Object.$Name }
    return $Default
}

function Ensure-Directory([string] $Path) {
    if (-not [IO.Directory]::Exists($Path)) { [IO.Directory]::CreateDirectory($Path) | Out-Null }
}

function Write-AtomicText([string] $Path, [string] $Text, [System.Text.Encoding] $Encoding) {
    Ensure-Directory ([IO.Path]::GetDirectoryName($Path))
    $temporary = $Path + '.new-' + [Guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($temporary, $Text, $Encoding)
    try {
        if ([IO.File]::Exists($Path)) {
            $discard = $Path + '.replace-' + [Guid]::NewGuid().ToString('N')
            [IO.File]::Replace($temporary, $Path, $discard, $true)
            if ([IO.File]::Exists($discard)) { [IO.File]::Delete($discard) }
        } else {
            [IO.File]::Move($temporary, $Path)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Get-RelativeSafePath([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if ($full.StartsWith($script:Root, [StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($script:Root.Length).TrimStart('\', '/')
    }
    return ([IO.Path]::GetFileName($full))
}

function Backup-File([string] $Path) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $relative = Get-RelativeSafePath $Path
    $destination = Join-Path $script:TransactionBackup $relative
    $existed = [IO.File]::Exists($fullPath)
    $tracking = $script:RollbackTracking -and -not $script:RollbackInProgress
    $alreadyTracked = $false
    if ($tracking) {
        foreach ($entry in $script:RollbackEntries) {
            if ([string]::Equals([IO.Path]::GetFullPath([string]$entry.Path), $fullPath, [StringComparison]::OrdinalIgnoreCase)) { $alreadyTracked = $true; break }
        }
    }
    if ($alreadyTracked) { return }

    # A backup must become durable before its journal entry.  Otherwise a
    # power loss during File.Copy could leave a partial JAR which recovery
    # would later trust and promote over the last working one.
    if ($existed -and -not [IO.File]::Exists($destination)) {
        Ensure-Directory ([IO.Path]::GetDirectoryName($destination))
        $temporary = $destination + '.new-' + [Guid]::NewGuid().ToString('N')
        try {
            [IO.File]::Copy($fullPath, $temporary, $false)
            if ((Get-FileHash256 $temporary) -ne (Get-FileHash256 $fullPath)) { Fail ('Backup SHA-256 verification failed for ' + $fullPath) }
            [IO.File]::Move($temporary, $destination)
        } finally {
            if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
        }
    } elseif ($existed -and $tracking -and (Get-FileHash256 $destination) -ne (Get-FileHash256 $fullPath)) {
        Fail ('An existing transaction backup does not match its source: ' + $destination)
    }

    if ($tracking) {
        $script:RollbackEntries.Add([pscustomobject]@{ Path=$fullPath; Existed=$existed; Backup=$destination })
        Write-TransactionJournal $script:TransactionPhase
    }
}

function Convert-MapToOrdered($Map) {
    $output = [ordered]@{}
    if ($Map -is [System.Collections.IDictionary]) {
        foreach ($key in $Map.Keys) { $output[[string]$key] = [bool]$Map[$key] }
    } elseif ($null -ne $Map) {
        foreach ($property in $Map.PSObject.Properties) { $output[$property.Name] = [bool]$property.Value }
    }
    return $output
}

function Write-TransactionJournal([string] $Phase) {
    if (-not $script:DeploymentStarted) { return }
    $script:TransactionPhase = $Phase
    $entries = @()
    foreach ($entry in $script:RollbackEntries) {
        $entries += [ordered]@{ path=[string]$entry.Path; existed=[bool]$entry.Existed; backup=[string]$entry.Backup }
    }
    $journal = [ordered]@{
        schemaVersion = 1
        transactionStamp = $script:TransactionStamp
        phase = $script:TransactionPhase
        databaseMayHaveMigrated = $script:DatabaseMayHaveMigrated
        dataInfrastructureMayHaveChanged = $script:DataInfrastructureMayHaveChanged
        projectName = $script:ProjectName
        entries = $entries
        initialRunning = Convert-MapToOrdered $script:InitialRunning
        initialServices = Convert-MapToOrdered $script:InitialServices
        backupDirectory = $script:TransactionBackup
        updatedAt = [DateTime]::UtcNow.ToString('o')
    }
    Write-AtomicText $script:PendingTransactionFile (($journal | ConvertTo-Json -Depth 8) + "`n") $script:Utf8NoBom
}

function Begin-DeploymentTransaction {
    if ([IO.File]::Exists($script:PendingTransactionFile)) { Fail ('An unresolved deployment transaction exists: ' + $script:PendingTransactionFile) }
    $script:RollbackEntries.Clear()
    $script:RollbackInProgress = $false
    $script:DatabaseMayHaveMigrated = $false
    $script:DataInfrastructureMayHaveChanged = $false
    $script:DeploymentStarted = $true
    $script:RollbackTracking = $true
    $script:TransactionPhase = 'runtime-mutation'
    Write-TransactionJournal $script:TransactionPhase
}

function Save-LastKnownGoodInputs {
    Ensure-Directory $script:LastGoodDirectory
    $entries = @(
        [pscustomobject]@{ Source=$script:EnvFile; Target=(Join-Path $script:LastGoodDirectory 'kofauth.env') },
        [pscustomobject]@{ Source=$script:ComposeFile; Target=(Join-Path $script:LastGoodDirectory 'compose.base.yml') }
    )
    # Both snapshot files belong to the same committed generation. Register
    # their old versions in the still-open transaction before replacing either
    # one, so crash recovery can never accept a mixed pair.
    if ($script:DeploymentStarted -and $script:RollbackTracking -and -not $script:RollbackInProgress) {
        foreach ($entry in $entries) { Backup-File $entry.Target }
    }
    foreach ($entry in $entries) {
        if (-not [IO.File]::Exists($entry.Source)) { continue }
        $temporary = $entry.Target + '.new-' + [Guid]::NewGuid().ToString('N')
        [IO.File]::Copy($entry.Source, $temporary, $false)
        if ([IO.File]::Exists($entry.Target)) {
            $discard = $entry.Target + '.replace-' + [Guid]::NewGuid().ToString('N')
            [IO.File]::Replace($temporary, $entry.Target, $discard, $true)
            if ([IO.File]::Exists($discard)) { [IO.File]::Delete($discard) }
        } else { [IO.File]::Move($temporary, $entry.Target) }
    }
}

function Ensure-LastKnownGoodBaseline {
    if (-not $script:State) { return }
    $lastEnv = Join-Path $script:LastGoodDirectory 'kofauth.env'
    $lastCompose = Join-Path $script:LastGoodDirectory 'compose.base.yml'
    $stateEnvironment = [string](Get-ObjectValue $script:State 'runtimeEnvironmentFingerprint' '')
    $stateCompose = [string](Get-ObjectValue $script:State 'composeDefinitionHash' '')
    if (-not [IO.File]::Exists($lastEnv) -or -not [IO.File]::Exists($lastCompose)) {
        if ($stateEnvironment -and $stateEnvironment -ne (Get-RuntimeEnvironmentFingerprint)) { Fail 'Last-known-good env snapshot is missing and the current env differs from committed state. Restore the prior env before deploying.' }
        if ($stateCompose -and $stateCompose -ne (Get-FileHash256 $script:ComposeFile)) { Fail 'Last-known-good Compose snapshot is missing and compose.base.yml differs from committed state. Restore it before deploying.' }
        Save-LastKnownGoodInputs
        return
    }
    $savedEnv = Read-EnvMap $lastEnv
    $currentEnv = $script:Env
    try { $script:Env = $savedEnv; $savedEnvironmentFingerprint = Get-RuntimeEnvironmentFingerprint }
    finally { $script:Env = $currentEnv }
    if ($stateEnvironment -and $savedEnvironmentFingerprint -ne $stateEnvironment) { Fail 'The last-known-good env snapshot does not match committed state. Restore _kofauth\runtime\last-good and state.json from the same backup.' }
    if ($stateCompose -and (Get-FileHash256 $lastCompose) -ne $stateCompose) { Fail 'The last-known-good Compose snapshot does not match committed state. Restore _kofauth\runtime\last-good and state.json from the same backup.' }
}

function Restore-LastKnownGoodInputs {
    foreach ($entry in @(
        [pscustomobject]@{ Source=(Join-Path $script:LastGoodDirectory 'kofauth.env'); Target=$script:EnvFile; Name='kofauth.env' },
        [pscustomobject]@{ Source=(Join-Path $script:LastGoodDirectory 'compose.base.yml'); Target=$script:ComposeFile; Name='compose.base.yml' }
    )) {
        if (-not [IO.File]::Exists($entry.Source)) { continue }
        if ((Get-FileHash256 $entry.Source) -eq (Get-FileHash256 $entry.Target)) { continue }
        $failedDirectory = Join-Path $script:TransactionBackup 'failed-request'
        Ensure-Directory $failedDirectory
        if ([IO.File]::Exists($entry.Target)) { [IO.File]::Copy($entry.Target, (Join-Path $failedDirectory $entry.Name), $true) }
        [IO.File]::Copy($entry.Source, $entry.Target, $true)
    }
    if ([IO.File]::Exists($script:EnvFile)) { $script:Env = Read-EnvMap $script:EnvFile }
}

function Get-FileHash256([string] $Path) {
    if (-not [IO.File]::Exists($Path)) { return $null }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Install-FileAtomically([string] $Source, [string] $Destination, [string] $Label) {
    $sourceHash = Get-FileHash256 $Source
    $destinationHash = Get-FileHash256 $Destination
    if ($sourceHash -eq $destinationHash) {
        Write-Info ($Label + ' unchanged: ' + $sourceHash.Substring(0, 12))
        return $false
    }

    Ensure-Directory ([IO.Path]::GetDirectoryName($Destination))
    $temporary = $Destination + '.new-' + [Guid]::NewGuid().ToString('N')
    [IO.File]::Copy($Source, $temporary, $false)
    if ((Get-FileHash256 $temporary) -ne $sourceHash) {
        [IO.File]::Delete($temporary)
        Fail ($Label + ': staged copy failed SHA-256 verification.')
    }

    Backup-File $Destination
    $attempt = 0
    while ($true) {
        try {
            if ([IO.File]::Exists($Destination)) {
                $replaceBackup = $Destination + '.replace-' + [Guid]::NewGuid().ToString('N')
                [IO.File]::Replace($temporary, $Destination, $replaceBackup, $true)
                if ([IO.File]::Exists($replaceBackup)) { [IO.File]::Delete($replaceBackup) }
            } else {
                [IO.File]::Move($temporary, $Destination)
            }
            break
        } catch {
            $attempt++
            if ($attempt -ge 6) { throw }
            Start-Sleep -Milliseconds (250 * $attempt)
        }
    }

    if ((Get-FileHash256 $Destination) -ne $sourceHash) {
        Fail ($Label + ': promoted file failed SHA-256 verification.')
    }
    Write-Ok ($Label + ' installed: ' + $sourceHash.Substring(0, 12))
    return $true
}

function Read-ZipEntry([string] $Jar, [string] $EntryName) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $entry = $zip.GetEntry($EntryName)
        if (-not $entry) { return $null }
        $reader = New-Object IO.StreamReader($entry.Open(), [Text.Encoding]::UTF8, $true)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

function Test-ZipEntry([string] $Jar, [string] $EntryName) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
    try { return $null -ne $zip.GetEntry($EntryName) } finally { $zip.Dispose() }
}

function Validate-ReleaseJar([string] $Path, [ValidateSet('paper','velocity','webapi')] [string] $Kind) {
    if (-not [IO.File]::Exists($Path)) {
        Fail ('Missing release artifact: ' + $Path + [Environment]::NewLine +
              'In the source repository run: deploy.bat package' + [Environment]::NewLine +
              'Then copy deploy.bat and the deploy folder to the server folder.')
    }
    try {
        if ($Kind -eq 'paper') {
            $metadata = Read-ZipEntry $Path 'plugin.yml'
            if (-not $metadata -or $metadata -notmatch '(?im)^\s*name\s*:\s*[''\"]?KoFAuth[''\"]?\s*$' -or
                $metadata -notmatch '(?im)^\s*main\s*:\s*net\.kofnetwork\.auth\.paper\.KoFAuthPaper\s*$' -or
                -not (Test-ZipEntry $Path 'net/kofnetwork/auth/paper/KoFAuthPaper.class')) {
                Fail ($Path + ' is not the KoFAuth Paper plugin JAR (metadata/main class mismatch).')
            }
        }
        if ($Kind -eq 'velocity') {
            $metadataText = Read-ZipEntry $Path 'velocity-plugin.json'
            if (-not $metadataText) { Fail ($Path + ' is not the KoFAuth Velocity plugin JAR (metadata missing).') }
            $metadata = $metadataText | ConvertFrom-Json
            if ([string]$metadata.id -ne 'kofauth' -or [string]$metadata.main -ne 'net.kofnetwork.auth.velocity.KoFAuthVelocity' -or
                -not (Test-ZipEntry $Path 'net/kofnetwork/auth/velocity/KoFAuthVelocity.class')) {
                Fail ($Path + ' is not the KoFAuth Velocity plugin JAR (metadata/main class mismatch).')
            }
        }
        if ($Kind -eq 'webapi' -and -not (Test-ZipEntry $Path 'BOOT-INF/classes/net/kofnetwork/auth/webapi/KoFAuthWebApiApplication.class')) {
            Fail ($Path + ' is not an executable KoFAuth WebAPI JAR.')
        }
    } catch {
        if ($_.Exception.Message -like '*KoFAuth*JAR*' -or $_.Exception.Message -like '*executable*') { throw }
        Fail ($Path + ' is not a readable JAR/ZIP: ' + $_.Exception.Message)
    }
}

function Get-ServerMetadata([string] $Path) {
    if (-not [IO.File]::Exists($Path)) { Fail ('server.jar is missing next to deploy.bat: ' + $Path) }
    try {
        $versionText = Read-ZipEntry $Path 'version.json'
        $manifest = Read-ZipEntry $Path 'META-INF/MANIFEST.MF'
        if (-not $versionText -or -not $manifest) { Fail ('server.jar is not a runnable Paper/Purpur server JAR.') }
        if ($manifest -notmatch '(?im)^Main-Class:\s*.*(?:paper|purpur).*clip') {
            Fail ('server.jar must be a Paper or Purpur paperclip JAR; vanilla/Spigot cores are not supported by this deployer.')
        }
        $version = $versionText | ConvertFrom-Json
        $java = 21
        if ($version.PSObject.Properties.Name -contains 'java_version') { $java = [int]$version.java_version }
        return [pscustomobject]@{ Minecraft = [string]$version.id; Java = $java }
    } catch {
        if ($_.Exception.Message -like 'server.jar*' -or $_.Exception.Message -like '*Paper*Purpur*') { throw }
        Fail ('Cannot inspect server.jar: ' + $_.Exception.Message)
    }
}

function Get-JavaPathAndVersion([int] $Required) {
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $command) { $command = Get-Command java -ErrorAction SilentlyContinue }
    if (-not $command) { Fail ('Java is not in PATH. Install a Java ' + $Required + ' x64 runtime and reopen the terminal.') }
    $probe = New-Object Diagnostics.ProcessStartInfo
    $probe.FileName = $command.Source
    $probe.Arguments = '-version'
    $probe.UseShellExecute = $false
    $probe.RedirectStandardOutput = $true
    $probe.RedirectStandardError = $true
    $probe.CreateNoWindow = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $probe
    if (-not $process.Start()) { Fail ('Cannot start Java version probe: ' + $command.Source) }
    $output = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($output -notmatch 'version\s+"(?<major>\d+)') { Fail ('Cannot determine Java version from: ' + $output.Trim()) }
    $major = [int]$Matches.major
    if ($major -lt $Required) { Fail ('Java ' + $Required + '+ is required, but PATH points to Java ' + $major + ': ' + $command.Source) }
    return [pscustomobject]@{ Path = $command.Source; Major = $major }
}

function Test-DockerReady {
    if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue) -and -not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Fail 'Docker Desktop is not installed or docker.exe is not in PATH.'
    }
    & docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) { Fail 'Docker Desktop engine is not running.' }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { Fail 'Docker Compose v2 is not available.' }
}

function New-RandomBytes([int] $Count) {
    $bytes = New-Object byte[] $Count
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return $bytes
}

function New-RandomHex([int] $Count) {
    return ([BitConverter]::ToString((New-RandomBytes $Count))).Replace('-', '').ToLowerInvariant()
}

function New-RandomBase64([int] $Count) {
    return [Convert]::ToBase64String((New-RandomBytes $Count))
}

function Read-EnvMap([string] $Path) {
    $map = @{}
    if (-not [IO.File]::Exists($Path)) { return $map }
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*(?:export\s+)?(?<key>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.*)$') {
            $value = $Matches.value.Trim()
            if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $map[$Matches.key] = $value
        }
    }
    return $map
}

function Set-EnvLine([System.Collections.Generic.List[string]] $Lines, [string] $Key, [string] $Value, [switch] $OnlyBlank) {
    $found = $false
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match ('^\s*' + [regex]::Escape($Key) + '\s*=\s*(?<value>.*)$')) {
            $found = $true
            $current = $Matches.value.Trim().Trim('"').Trim("'")
            if (-not $OnlyBlank -or [string]::IsNullOrWhiteSpace($current)) { $Lines[$i] = $Key + '=' + $Value }
            break
        }
    }
    if (-not $found) { $Lines.Add($Key + '=' + $Value) }
}

function Test-ExistingManagedInstallation {
    $signs = @(
        (Join-Path $script:Root 'plugins\KoFAuth'),
        (Join-Path $script:Managed 'artifacts\kofauth-paper.jar'),
        (Join-Path $script:Runtime 'state.json'),
        (Join-Path $script:Limbo 'plugins\KoFAuth'),
        (Join-Path $script:Velocity 'plugins\kofauth')
    )
    foreach ($sign in $signs) { if (Test-Path -LiteralPath $sign) { return $true } }
    foreach ($candidate in @(
        Get-ChildItem -LiteralPath (Join-Path $script:Root 'plugins') -Filter '*.jar' -File -ErrorAction SilentlyContinue
        Get-ChildItem -LiteralPath (Join-Path $script:Limbo 'plugins') -Filter '*.jar' -File -ErrorAction SilentlyContinue
        Get-ChildItem -LiteralPath (Join-Path $script:Velocity 'plugins') -Filter '*.jar' -File -ErrorAction SilentlyContinue
    )) {
        try {
            if ((Test-ZipEntry $candidate.FullName 'net/kofnetwork/auth/paper/KoFAuthPaper.class') -or (Test-ZipEntry $candidate.FullName 'net/kofnetwork/auth/velocity/KoFAuthVelocity.class')) { return $true }
        } catch {}
    }
    return $false
}

function Ensure-Environment([string] $MinecraftVersion) {
    $existingInstall = Test-ExistingManagedInstallation
    $created = $false
    if (-not [IO.File]::Exists($script:EnvFile)) {
        $legacy = Join-Path $script:Release '.env'
        $template = Join-Path $script:Release '.env.example'
        if ($existingInstall -and [IO.File]::Exists($legacy)) {
            Ensure-Directory $script:Config
            [IO.File]::Copy($legacy, $script:EnvFile, $false)
            Write-Info 'Imported existing deploy\.env into persistent _kofauth\config\kofauth.env.'
        } elseif ($existingInstall) {
            Fail 'KoFAuth runtime data exists, but no persistent env file was found. Restore the old secrets; generating replacements would make encrypted data unreadable.'
        } elseif ([IO.File]::Exists($template)) {
            Ensure-Directory $script:Config
            [IO.File]::Copy($template, $script:EnvFile, $false)
            $created = $true
            if ([IO.File]::Exists($legacy)) {
                Write-WarningLine 'Ignored adjacent deploy\.env on this fresh installation. New database/encryption secrets are being generated from .env.example instead of reusing a copied machine secret.'
            }
        } else {
            Fail ('Neither ' + $template + ' nor an existing .env file exists.')
        }
    }

    $lines = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in [IO.File]::ReadAllLines($script:EnvFile)) { $lines.Add($line) }
    $before = [string]::Join("`n", $lines)
    $current = Read-EnvMap $script:EnvFile
    $required = @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','REDIS_PASSWORD','ENCRYPTION_KEY','JWT_SECRET','FORWARDING_SECRET')
    $hasBlankRequired = $false
    foreach ($key in $required) { if (-not $current.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$current[$key])) { $hasBlankRequired = $true } }
    if ($hasBlankRequired -and $existingInstall -and -not $created) {
        Fail 'The persistent env has blank required secrets while runtime data already exists. Restore the original values instead of generating new ones.'
    }

    Set-EnvLine $lines 'MYSQL_ROOT_PASSWORD' (New-RandomHex 24) -OnlyBlank
    Set-EnvLine $lines 'MYSQL_PASSWORD' (New-RandomHex 24) -OnlyBlank
    Set-EnvLine $lines 'REDIS_PASSWORD' (New-RandomHex 24) -OnlyBlank
    Set-EnvLine $lines 'ENCRYPTION_KEY' (New-RandomBase64 32) -OnlyBlank
    Set-EnvLine $lines 'JWT_SECRET' (New-RandomHex 32) -OnlyBlank
    Set-EnvLine $lines 'FORWARDING_SECRET' (New-RandomHex 24) -OnlyBlank
    Set-EnvLine $lines 'PAPER_VERSION' $MinecraftVersion -OnlyBlank
    Set-EnvLine $lines 'MINECRAFT_PORT' '25565' -OnlyBlank
    Set-EnvLine $lines 'MINECRAFT_BIND' '0.0.0.0' -OnlyBlank
    Set-EnvLine $lines 'LOBBY_ADDRESS' 'host.docker.internal:25566' -OnlyBlank
    Set-EnvLine $lines 'MYSQL_BIND' '127.0.0.1' -OnlyBlank
    Set-EnvLine $lines 'MYSQL_PORT' '3306' -OnlyBlank
    Set-EnvLine $lines 'REDIS_BIND' '127.0.0.1' -OnlyBlank
    Set-EnvLine $lines 'REDIS_PORT' '6379' -OnlyBlank
    Set-EnvLine $lines 'WEBAPI_BIND' '127.0.0.1' -OnlyBlank
    Set-EnvLine $lines 'WEBAPI_PORT' '8080' -OnlyBlank
    if ($created) {
        # .env.example also serves the Linux all-in-Docker installer.  Its
        # nonblank lobby/profile/public-WebAPI defaults are deliberately
        # replaced for this native Windows topology.
        Set-EnvLine $lines 'RCON_PASSWORD' (New-RandomHex 18)
        Set-EnvLine $lines 'BOT_API_KEY' (New-RandomHex 32)
        Set-EnvLine $lines 'PAPER_VERSION' $MinecraftVersion
        Set-EnvLine $lines 'LOBBY_ADDRESS' 'host.docker.internal:25566'
        Set-EnvLine $lines 'COMPOSE_PROFILES' ''
        Set-EnvLine $lines 'MYSQL_BIND' '127.0.0.1'
        Set-EnvLine $lines 'REDIS_BIND' '127.0.0.1'
        Set-EnvLine $lines 'WEBAPI_BIND' '127.0.0.1'
    }

    $after = [string]::Join("`n", $lines)
    if ($after -ne $before) {
        Backup-File $script:EnvFile
        Write-AtomicText $script:EnvFile ($after + "`n") $script:Utf8NoBom
        if ($created) { Write-Ok 'Created persistent environment and generated cryptographic secrets.' }
        else { Write-Ok 'Filled only blank required values in the persistent environment.' }
    }
    $script:Env = Read-EnvMap $script:EnvFile
    foreach ($key in $required) {
        if (-not $script:Env.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$script:Env[$key])) { Fail ('Required env value is blank: ' + $key) }
    }
    if ($created) { Write-BootstrapIdentityMarker }
    if ((Get-EnvValue 'WEBAPI_BIND' '127.0.0.1') -notin @('127.0.0.1','localhost','::1')) {
        Write-WarningLine 'WEBAPI_BIND is not loopback. This imported/custom setting exposes WebAPI directly; put TLS/reverse-proxy protection in front of it.'
    }
}

function Get-EnvValue([string] $Key, [string] $Default = '') {
    if ($script:Env.ContainsKey($Key) -and -not [string]::IsNullOrWhiteSpace([string]$script:Env[$Key])) { return [string]$script:Env[$Key] }
    return $Default
}

function Get-RuntimeEnvironmentFingerprint {
    $builder = New-Object Text.StringBuilder
    foreach ($key in @($script:Env.Keys | Sort-Object)) {
        [void]$builder.Append([string]$key).Append('=').Append([string]$script:Env[$key]).Append("`n")
    }
    $bytes = $script:Utf8NoBom.GetBytes($builder.ToString())
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','') } finally { $sha.Dispose() }
}

function Get-DataInfrastructureFingerprint {
    $builder = New-Object Text.StringBuilder
    foreach ($key in @('MYSQL_BIND','MYSQL_PORT','MYSQL_MAX_CONNECTIONS','MYSQL_BUFFER_POOL','REDIS_BIND','REDIS_PORT','REDIS_MAXMEMORY')) {
        [void]$builder.Append($key).Append('=').Append((Get-EnvValue $key)).Append("`n")
    }
    $bytes = $script:Utf8NoBom.GetBytes($builder.ToString())
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','') } finally { $sha.Dispose() }
}

function Get-IdentityEnvironmentFingerprint {
    $builder = New-Object Text.StringBuilder
    foreach ($key in @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','REDIS_PASSWORD','ENCRYPTION_KEY','JWT_SECRET','FORWARDING_SECRET')) {
        [void]$builder.Append($key).Append('=').Append((Get-EnvValue $key)).Append("`n")
    }
    $bytes = $script:Utf8NoBom.GetBytes($builder.ToString())
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','') } finally { $sha.Dispose() }
}

function Write-BootstrapIdentityMarker([string] $Project = '') {
    $marker = [ordered]@{
        schemaVersion = 1
        identityEnvironmentFingerprint = (Get-IdentityEnvironmentFingerprint)
        projectName = $Project
        createdAt = [DateTime]::UtcNow.ToString('o')
    }
    Write-AtomicText $script:BootstrapIdentityFile (($marker | ConvertTo-Json -Depth 3) + "`n") $script:Utf8NoBom
}

function Test-BootstrapIdentityMarker {
    if ([IO.File]::Exists($script:StateFile) -or -not [IO.File]::Exists($script:BootstrapIdentityFile)) { return $false }
    try { $marker = [IO.File]::ReadAllText($script:BootstrapIdentityFile) | ConvertFrom-Json }
    catch { return $false }
    if ([string](Get-ObjectValue $marker 'identityEnvironmentFingerprint' '') -ne (Get-IdentityEnvironmentFingerprint)) { return $false }
    $markedProject = [string](Get-ObjectValue $marker 'projectName' '')
    if ($markedProject -and $script:ProjectName -and $markedProject -ne $script:ProjectName) { return $false }
    return $true
}

function Get-BootstrapMarkedProject {
    if (-not (Test-BootstrapIdentityMarker)) { return '' }
    try {
        $marker = [IO.File]::ReadAllText($script:BootstrapIdentityFile) | ConvertFrom-Json
        $project = [string](Get-ObjectValue $marker 'projectName' '')
        if ($project -match '^[a-z0-9][a-z0-9_-]{2,62}$') { return $project }
    } catch {}
    return ''
}

function Validate-EnvironmentValues {
    foreach ($heap in @(
        [pscustomobject]@{ Key='HEAP_LOBBY'; Default='2G' },
        [pscustomobject]@{ Key='HEAP_LIMBO'; Default='768M' },
        [pscustomobject]@{ Key='HEAP_VELOCITY'; Default='512M' }
    )) {
        $value = Get-EnvValue $heap.Key $heap.Default
        if ($value -notmatch '^\d+[mMgG]$') { Fail ('Invalid ' + $heap.Key + '=' + $value + '; expected e.g. 2G or 768M.') }
    }
    $ports = [ordered]@{ MINECRAFT_PORT='25565'; MYSQL_PORT='3306'; REDIS_PORT='6379'; WEBAPI_PORT='8080' }
    $seen = @{}
    foreach ($entry in $ports.GetEnumerator()) {
        $raw = Get-EnvValue $entry.Key $entry.Value
        $parsed = 0
        if (-not [int]::TryParse($raw, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) { Fail ('Invalid ' + $entry.Key + '=' + $raw) }
        if ($parsed -in @(25566,25567)) { Fail ($entry.Key + '=' + $parsed + ' conflicts with the managed backend/Limbo port.') }
        if ($seen.ContainsKey($parsed)) { Fail ($entry.Key + '=' + $parsed + ' conflicts with ' + $seen[$parsed] + '.') }
        $seen[$parsed] = $entry.Key
    }
    foreach ($key in @('MINECRAFT_BIND','MYSQL_BIND','REDIS_BIND','WEBAPI_BIND')) {
        $value = Get-EnvValue $key $(if ($key -eq 'MINECRAFT_BIND') { '0.0.0.0' } else { '127.0.0.1' })
        if ($value -match '[\s:/]') { Fail ('Invalid ' + $key + '=' + $value + '; use a host/IP without a port.') }
    }
}

function Get-LabeledContainerId([string] $Project, [string] $Service) {
    $id = (& docker ps -aq --filter ('label=com.docker.compose.project=' + $Project) --filter ('label=com.docker.compose.service=' + $Service) 2>$null | Select-Object -First 1)
    if (-not $id) { return $null }
    return ([string]$id).Trim()
}

function Get-ContainerEnvironment([string] $ContainerId) {
    $map = @{}
    if (-not $ContainerId) { return $map }
    $json = [string](& docker inspect --format '{{json .Config.Env}}' $ContainerId 2>$null | Select-Object -First 1)
    if (-not $json) { return $map }
    $parsed = $json | ConvertFrom-Json
    foreach ($entry in @($parsed)) {
        if ([string]$entry -match '^(?<key>[^=]+)=(?<value>.*)$') { $map[$Matches.key] = $Matches.value }
    }
    return $map
}

function Test-ProjectMatchesSecrets([string] $Project, [switch] $RequireComplete) {
    $mysqlId = Get-LabeledContainerId $Project 'mysql'
    $redisId = Get-LabeledContainerId $Project 'redis'
    $webId = Get-LabeledContainerId $Project 'webapi'
    if (-not $mysqlId -and -not $redisId -and -not $webId) { return $false }
    if ($RequireComplete -and (-not $mysqlId -or -not $redisId -or -not $webId)) {
        Fail ('A partial legacy Compose project named ' + $Project + ' exists. It was not adopted because its identity cannot be verified safely.')
    }
    $comparisons = @()
    if ($mysqlId) {
        $mysqlEnv = Get-ContainerEnvironment $mysqlId
        $comparisons += [pscustomobject]@{ Actual=$mysqlEnv['MYSQL_ROOT_PASSWORD']; Expected=(Get-EnvValue 'MYSQL_ROOT_PASSWORD') }
        $comparisons += [pscustomobject]@{ Actual=$mysqlEnv['MYSQL_PASSWORD']; Expected=(Get-EnvValue 'MYSQL_PASSWORD') }
    }
    if ($redisId) {
        $redisEnv = Get-ContainerEnvironment $redisId
        $comparisons += [pscustomobject]@{ Actual=$redisEnv['REDIS_PASSWORD']; Expected=(Get-EnvValue 'REDIS_PASSWORD') }
    }
    if ($webId) {
        $webEnv = Get-ContainerEnvironment $webId
        $comparisons += [pscustomobject]@{ Actual=$webEnv['KOFAUTH_SECURITY_ENCRYPTION_KEY']; Expected=(Get-EnvValue 'ENCRYPTION_KEY') }
        $comparisons += [pscustomobject]@{ Actual=$webEnv['KOFAUTH_SECURITY_JWT_SECRET']; Expected=(Get-EnvValue 'JWT_SECRET') }
    }
    foreach ($comparison in $comparisons) {
        $actual = [string]$comparison.Actual
        $expected = [string]$comparison.Expected
        if ([string]::IsNullOrWhiteSpace($actual) -or -not [string]::Equals($actual, $expected, [StringComparison]::Ordinal)) {
            Fail ('Compose project ' + $Project + ' already exists, but its secret fingerprint does not match the supplied deploy\.env. It was not touched. Restore the matching env or choose a machine without that project.')
        }
    }
    return $true
}

function Ensure-ProjectName {
    if ([IO.File]::Exists($script:ProjectFile)) {
        $value = [IO.File]::ReadAllText($script:ProjectFile).Trim()
        if ($value -notmatch '^[a-z0-9][a-z0-9_-]{2,62}$') { Fail ('Invalid stored Compose project name: ' + $script:ProjectFile) }
        $script:ProjectName = $value
        $containers = @(& docker ps -aq --filter ('label=com.docker.compose.project=' + $script:ProjectName) 2>$null)
        $volumes = @(& docker volume ls -q --filter ('label=com.docker.compose.project=' + $script:ProjectName) 2>$null)
        if (-not [IO.File]::Exists($script:StateFile)) {
            $bootstrapResume = Test-BootstrapIdentityMarker
            if ($bootstrapResume) {
                if ($containers.Count -gt 0) { [void](Test-ProjectMatchesSecrets $script:ProjectName) }
                $mysqlId = Get-LabeledContainerId $script:ProjectName 'mysql'
                $resumeVolume = Get-ProjectVolumeName 'mysql-data'
                if ($mysqlId -and -not $resumeVolume) { Fail 'The interrupted fresh install has a MySQL container without its expected labeled mysql-data volume. It was not resumed.' }
                if ($mysqlId) { Assert-MySqlVolumeIdentity $resumeVolume }
                if ($volumes.Count -gt 0 -and -not $resumeVolume) { Fail 'The interrupted fresh install has project volumes but no identifiable mysql-data volume. It was not resumed.' }
                Write-BootstrapIdentityMarker $script:ProjectName
                Write-Info 'Resuming a previous fresh bootstrap using its matching local identity marker.'
            } elseif ($containers.Count -gt 0) {
                [void](Test-ProjectMatchesSecrets $script:ProjectName -RequireComplete)
                $legacyVolume = Get-ProjectVolumeName 'mysql-data'
                if (-not $legacyVolume) { Fail 'The legacy MySQL container has no labeled managed mysql-data volume. Automatic adoption could switch to an empty database, so it was refused.' }
                Assert-MySqlVolumeIdentity $legacyVolume
            }
            elseif ($volumes.Count -gt 0) { Fail 'compose-project.txt points to persistent Docker volumes, but no committed state or complete container set can verify their identity. Restore state.json/containers; the database was not touched.' }
            elseif (Test-ExistingManagedInstallation) { Fail 'KoFAuth runtime exists with compose-project.txt but no committed state or verifiable Docker services. Restore state.json before deploying.' }
        } elseif ($containers.Count -gt 0) {
            [void](Test-ProjectMatchesSecrets $script:ProjectName)
        }
        return
    }
    $markedProject = Get-BootstrapMarkedProject
    if ($markedProject) {
        $script:ProjectName = $markedProject
        $containers = @(& docker ps -aq --filter ('label=com.docker.compose.project=' + $script:ProjectName) 2>$null)
        if ($containers.Count -gt 0) { [void](Test-ProjectMatchesSecrets $script:ProjectName) }
        $resumeVolume = Get-ProjectVolumeName 'mysql-data'
        if ((Get-LabeledContainerId $script:ProjectName 'mysql') -and -not $resumeVolume) { Fail 'Marked bootstrap MySQL has no managed mysql-data volume.' }
        if ($resumeVolume) { Assert-MySqlVolumeIdentity $resumeVolume }
        Write-AtomicText $script:ProjectFile ($script:ProjectName + "`n") $script:Ascii
        Write-Ok ('Restored interrupted bootstrap project id: ' + $script:ProjectName)
        return
    }
    $legacyIdentityConflict = $false
    $legacyMatches = $false
    try { $legacyMatches = Test-ProjectMatchesSecrets 'kofauth' -RequireComplete }
    catch {
        # A brand-new root may share a Docker host with another KoFAuth
        # installation that legitimately owns the historical project name.
        # CLI -p gives this root an isolated project, so leave the foreign
        # containers/volumes untouched. An existing local runtime must instead
        # fail closed: silently switching its database would lose identity.
        if ((Test-ExistingManagedInstallation) -and -not (Test-BootstrapIdentityMarker)) { throw }
        $legacyIdentityConflict = $true
        Write-WarningLine 'A different/partial Docker project named kofauth already exists. It was not touched; this fresh server will receive an isolated project id.'
    }
    if ($legacyMatches) {
        $script:ProjectName = 'kofauth'
        $legacyVolume = Get-ProjectVolumeName 'mysql-data'
        if (-not $legacyVolume) { Fail 'Legacy project kofauth has no labeled managed mysql-data volume. Automatic adoption was refused to avoid switching databases.' }
        Assert-MySqlVolumeIdentity $legacyVolume
        Write-AtomicText $script:ProjectFile ($script:ProjectName + "`n") $script:Ascii
        Write-Ok 'Safely adopted legacy Compose project kofauth after matching all secret fingerprints.'
        return
    }
    $legacyVolumes = @(& docker volume ls -q --filter 'label=com.docker.compose.project=kofauth' 2>$null)
    if ($legacyVolumes.Count -gt 0 -and -not $legacyIdentityConflict) {
        Fail 'Legacy KoFAuth Docker volumes exist without a verifiable complete container set. Restore compose-project.txt or the old containers; a new empty database was not created.'
    }
    if ((Test-ExistingManagedInstallation) -and -not (Test-BootstrapIdentityMarker)) {
        Fail 'Runtime data exists but compose-project.txt is missing, and no matching legacy Compose project could be verified. Restore the project id before deploying.'
    }
    $script:ProjectName = 'kofauth-' + (New-RandomHex 4)
    Write-AtomicText $script:ProjectFile ($script:ProjectName + "`n") $script:Ascii
    if (Test-BootstrapIdentityMarker) { Write-BootstrapIdentityMarker $script:ProjectName }
    Write-Ok ('Created stable Compose project id: ' + $script:ProjectName)
}

function Get-ProjectVolumeName([string] $VolumeKey) {
    $names = @(& docker volume ls -q --filter ('label=com.docker.compose.project=' + $script:ProjectName) --filter ('label=com.docker.compose.volume=' + $VolumeKey) 2>$null)
    if ($names.Count -eq 0) { return $null }
    if ($names.Count -gt 1) { Fail ('Multiple Docker volumes claim ' + $script:ProjectName + '/' + $VolumeKey + '; deployment stopped for manual review.') }
    return ([string]$names[0]).Trim()
}

function Assert-MySqlVolumeIdentity([string] $ExpectedVolume) {
    if (-not $ExpectedVolume) { Fail 'The committed MySQL volume identity is blank.' }
    $actualVolume = Get-ProjectVolumeName 'mysql-data'
    if (-not $actualVolume -or $actualVolume -ne $ExpectedVolume) {
        Fail ('The managed MySQL volume identity is missing or changed. Expected ' + $ExpectedVolume + '; an empty/foreign database was not used.')
    }
    $mysqlId = Get-LabeledContainerId $script:ProjectName 'mysql'
    if (-not $mysqlId) { return }
    $mountsJson = [string](& docker inspect --format '{{json .Mounts}}' $mysqlId 2>$null | Select-Object -First 1)
    if (-not $mountsJson) { Fail ('Could not inspect MySQL container mounts for ' + $mysqlId + '.') }
    try { $mounts = $mountsJson | ConvertFrom-Json }
    catch { Fail ('MySQL container mount metadata is unreadable for ' + $mysqlId + '.') }
    $matched = $false
    foreach ($mount in @($mounts)) {
        if ([string]$mount.Destination -eq '/var/lib/mysql' -and [string]$mount.Type -eq 'volume' -and [string]$mount.Name -eq $ExpectedVolume) { $matched = $true; break }
    }
    if (-not $matched) { Fail ('MySQL container ' + $mysqlId + ' is not mounted to committed volume ' + $ExpectedVolume + ' at /var/lib/mysql. It was not touched.') }
}

function Ensure-ComposeBase {
    if (-not [IO.File]::Exists($script:ReleaseComposeFile)) { Fail ('Missing ' + $script:ReleaseComposeFile) }
    $desiredContent = [IO.File]::ReadAllText($script:ReleaseComposeFile)
    $desiredContent = [regex]::Replace($desiredContent, '(?m)^(\s*context:)\s*\.\./bots\s*$', '$1 ../../bots')
    $desiredHashBytes = $script:Utf8NoBom.GetBytes($desiredContent)
    $desiredSha = [Security.Cryptography.SHA256]::Create()
    try { $desiredHash = ([BitConverter]::ToString($desiredSha.ComputeHash($desiredHashBytes))).Replace('-','') } finally { $desiredSha.Dispose() }
    if (-not [IO.File]::Exists($script:ComposeFile)) {
        # The Windows deployer deliberately owns only mysql/redis/webapi.
        # Replace the bots build context while pinning so later explicit bot
        # profiles cannot escape the persistent managed directory. The bot
        # services are never selected by this script.
        Write-AtomicText $script:ComposeFile $desiredContent $script:Utf8NoBom
        Write-Ok 'Pinned the Docker infrastructure definition in persistent runtime state.'
        return
    }
    if ((Get-FileHash256 $script:ComposeFile) -ne $desiredHash) {
        Write-WarningLine 'deploy\docker-compose.yml changed. The pinned infrastructure definition was preserved; plugin deploy will not silently recreate MySQL/Redis. Review and upgrade infrastructure separately.'
    }
}

function Convert-ToYamlSingleQuoted([string] $Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

function Ensure-ComposeOverride {
    $jar = ([IO.Path]::GetFullPath((Join-Path $script:Artifacts 'kofauth-webapi.jar'))).Replace('\','/')
    $config = ([IO.Path]::GetFullPath($script:WebConfig)).Replace('\','/')
    $content = @(
        ('name: ' + (Convert-ToYamlSingleQuoted $script:ProjectName)),
        'services:',
        '  webapi:',
        '    volumes:',
        '      - type: bind',
        ('        source: ' + (Convert-ToYamlSingleQuoted $jar)),
        '        target: /app/app.jar',
        '        read_only: true',
        '      - type: bind',
        ('        source: ' + (Convert-ToYamlSingleQuoted $config)),
        '        target: /app/config'
    ) -join "`n"
    $content += "`n"
    if (-not [IO.File]::Exists($script:OverrideFile) -or [IO.File]::ReadAllText($script:OverrideFile) -ne $content) {
        Write-AtomicText $script:OverrideFile $content $script:Utf8NoBom
    }
}

function Invoke-ComposeWithDefinition([string] $EnvironmentFile, [string] $DefinitionFile, [string[]] $Arguments, [switch] $Quiet) {
    $all = @('compose','--project-directory',$script:Managed,'-p',$script:ProjectName,'--env-file',$EnvironmentFile,'-f',$DefinitionFile,'-f',$script:OverrideFile) + $Arguments
    if ($Quiet) { & docker @all *> $null } else { & docker @all }
    if ($LASTEXITCODE -ne 0) { Fail ('Docker Compose command failed: docker ' + ($Arguments -join ' ')) }
}

function Invoke-Compose([string[]] $Arguments, [switch] $Quiet) {
    Invoke-ComposeWithDefinition $script:EnvFile $script:ComposeFile $Arguments -Quiet:$Quiet
}

function Get-ResolvedComposeConfiguration {
    $all = @('compose','--project-directory',$script:Managed,'-p',$script:ProjectName,'--env-file',$script:EnvFile,'-f',$script:ComposeFile,'-f',$script:OverrideFile,'config','--format','json')
    $json = [string]::Join("`n", @(& docker @all 2>$null))
    if (-not $json) { Fail 'Docker Compose did not return its resolved configuration.' }
    try { return ($json | ConvertFrom-Json) }
    catch { Fail 'Docker Compose returned an unreadable resolved configuration.' }
}

function Assert-ResolvedMySqlVolumeDefinition($Resolved = $null) {
    if (-not $Resolved) { $Resolved = Get-ResolvedComposeConfiguration }
    $mysql = Get-ObjectValue $Resolved.services 'mysql'
    $matches = @($mysql.volumes | Where-Object { [string]$_.target -eq '/var/lib/mysql' })
    if ($matches.Count -ne 1 -or [string]$matches[0].type -ne 'volume' -or [string]$matches[0].source -ne 'mysql-data') {
        Fail 'Pinned Compose must map the managed mysql-data volume to /var/lib/mysql exactly once. Bind/external/renamed database storage was refused.'
    }
}

function Assert-LegacyDataInfrastructureMatchesDesired {
    $resolved = Get-ResolvedComposeConfiguration
    Assert-ResolvedMySqlVolumeDefinition $resolved
    foreach ($service in @(
        [pscustomobject]@{ Name='mysql'; ContainerPort='3306/tcp'; Target=3306 },
        [pscustomobject]@{ Name='redis'; ContainerPort='6379/tcp'; Target=6379 }
    )) {
        $id = Get-LabeledContainerId $script:ProjectName $service.Name
        if (-not $id) { Fail ('Legacy adoption requires an existing ' + $service.Name + ' container so its data definition can be verified.') }
        $inspectJson = [string](& docker inspect $id 2>$null | Out-String)
        if (-not $inspectJson) { Fail ('Could not inspect legacy ' + $service.Name + ' container ' + $id + '.') }
        try { $actual = @($inspectJson | ConvertFrom-Json)[0] }
        catch { Fail ('Unreadable Docker metadata for legacy ' + $service.Name + ' container.') }
        $desired = Get-ObjectValue $resolved.services $service.Name
        if (-not $desired) { Fail ('Pinned Compose has no ' + $service.Name + ' service.') }
        if ([string]$actual.Config.Image -ne [string]$desired.image) {
            Fail ('Legacy ' + $service.Name + ' image differs from the pinned definition. Automatic adoption will not recreate data services; align it manually first.')
        }
        $separator = [string][char]0x1f
        $actualCommand = [string]::Join($separator, @($actual.Config.Cmd | ForEach-Object { [string]$_ }))
        $desiredCommand = [string]::Join($separator, @($desired.command | ForEach-Object { ([string]$_).Replace('$$','$') }))
        if ($actualCommand -ne $desiredCommand) {
            Fail ('Legacy ' + $service.Name + ' command/resource settings differ from the pinned definition. Automatic adoption was refused without changing the container.')
        }
        $desiredPort = @($desired.ports | Where-Object { [int]$_.target -eq $service.Target } | Select-Object -First 1)
        if ($desiredPort.Count -ne 1) { Fail ('Pinned Compose does not define one host binding for ' + $service.Name + ':' + $service.Target + '.') }
        $bindingProperty = $actual.HostConfig.PortBindings.PSObject.Properties[$service.ContainerPort]
        $bindings = if ($bindingProperty) { @($bindingProperty.Value) } else { @() }
        if ($bindings.Count -ne 1 -or [string]$bindings[0].HostIp -ne [string]$desiredPort[0].host_ip -or [string]$bindings[0].HostPort -ne [string]$desiredPort[0].published) {
            Fail ('Legacy ' + $service.Name + ' host bind/port differs from the pinned definition. Automatic adoption was refused without touching it.')
        }
    }
    $volume = Get-ProjectVolumeName 'mysql-data'
    Assert-MySqlVolumeIdentity $volume
    Write-Ok 'Legacy MySQL/Redis image, command, ports and MySQL volume match the pinned definition; data services will be adopted without recreation.'
}

function Get-ServiceContainerId([string] $Service) {
    $all = @('compose','--project-directory',$script:Managed,'-p',$script:ProjectName,'--env-file',$script:EnvFile,'-f',$script:ComposeFile,'-f',$script:OverrideFile,'ps','--all','-q',$Service)
    $id = (& docker @all 2>$null | Select-Object -First 1)
    if (-not $id) { return $null }
    return ([string]$id).Trim()
}

function Wait-ServiceHealthy([string] $Service, [int] $TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $id = Get-ServiceContainerId $Service
        if ($id) {
            $status = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $id 2>$null | Select-Object -First 1)
            if (([string]$status).Trim() -in @('healthy','running')) { Write-Ok ($Service + ' is ' + ([string]$status).Trim() + '.'); return }
            if (([string]$status).Trim() -eq 'unhealthy') { Fail ($Service + ' became unhealthy. Check: docker logs ' + $id) }
        }
        Start-Sleep -Seconds 2
    }
    Fail ($Service + ' did not become healthy within ' + $TimeoutSeconds + ' seconds.')
}

function Ensure-MySqlRunningForBackup([bool] $UseLastKnownGoodDefinition) {
    $id = Get-ServiceContainerId 'mysql'
    if ($id) {
        if (-not (Test-ServiceRunning 'mysql')) {
            & docker start $id *> $null
            if ($LASTEXITCODE -ne 0) { Fail ('Could not start the exact existing MySQL container ' + $id + ' for backup.') }
        }
        Wait-ServiceHealthy 'mysql' 180
        return
    }
    if (-not $UseLastKnownGoodDefinition) {
        [void](Invoke-Compose @('up','-d','--no-deps','mysql'))
        Wait-ServiceHealthy 'mysql' 180
        return
    }
    $lastEnv = Join-Path $script:LastGoodDirectory 'kofauth.env'
    $lastCompose = Join-Path $script:LastGoodDirectory 'compose.base.yml'
    if (-not [IO.File]::Exists($lastEnv) -or -not [IO.File]::Exists($lastCompose)) {
        Fail 'MySQL must be recreated for a pre-deploy dump, but the matching last-known-good env/Compose pair is missing. The database was not touched.'
    }
    [void](Invoke-ComposeWithDefinition $lastEnv $lastCompose @('up','-d','--no-deps','mysql'))
    Wait-ServiceHealthy 'mysql' 180
}

function Backup-DatabaseIfPresent {
    $id = Get-ServiceContainerId 'mysql'
    if (-not $id) { return }
    $status = (& docker inspect --format '{{.State.Running}}' $id 2>$null | Select-Object -First 1)
    if (([string]$status).Trim() -ne 'true') { return }
    Ensure-Directory $script:TransactionBackup
    $inside = '/tmp/kofauth-predeploy-' + [Guid]::NewGuid().ToString('N') + '.sql'
    Write-Info 'Creating a pre-deploy MySQL dump (secrets are not printed).'
    & docker exec $id sh -c ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --single-transaction --routines --triggers -u root kofauth > ' + $inside)
    if ($LASTEXITCODE -ne 0) { Fail 'MySQL pre-deploy dump failed; WebAPI was not replaced.' }
    $destination = Join-Path $script:TransactionBackup 'mysql-kofauth.sql'
    & docker cp ($id + ':' + $inside) $destination *> $null
    $copyCode = $LASTEXITCODE
    & docker exec $id rm -f $inside *> $null
    if ($copyCode -ne 0 -or -not [IO.File]::Exists($destination)) { Fail 'Could not copy the MySQL dump out of the container.' }
    Write-Ok ('Database backup saved: ' + $destination)
}

function Get-ComponentDefinition([string] $Component) {
    switch ($Component) {
        'backend' { return [pscustomobject]@{ Work = $script:Root; Jar = (Join-Path $script:Root 'server.jar'); ProbeHost = '127.0.0.1'; Port = 25566; Stop = 'stop'; HeapKey = 'HEAP_LOBBY'; Heap = '2G'; Extra = '--nogui' } }
        'limbo' { return [pscustomobject]@{ Work = $script:Limbo; Jar = (Join-Path $script:Limbo 'paper.jar'); ProbeHost = '127.0.0.1'; Port = 25567; Stop = 'stop'; HeapKey = 'HEAP_LIMBO'; Heap = '768M'; Extra = '--nogui' } }
        'velocity' {
            $portText = Get-EnvValue 'MINECRAFT_PORT' '25565'
            $port = 0
            if (-not [int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) { Fail ('Invalid MINECRAFT_PORT=' + $portText) }
            $bindHost = Get-EnvValue 'MINECRAFT_BIND' '0.0.0.0'
            $probeHost = if ($bindHost -eq '0.0.0.0') { '127.0.0.1' } else { $bindHost }
            return [pscustomobject]@{ Work = $script:Velocity; Jar = (Join-Path $script:Velocity 'velocity.jar'); ProbeHost = $probeHost; Port = $port; Stop = 'shutdown'; HeapKey = 'HEAP_VELOCITY'; Heap = '512M'; Extra = '' }
        }
        default { Fail ('Unknown component: ' + $Component) }
    }
}

function Test-Port([string] $HostName, [int] $Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(250)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch { return $false } finally { $client.Close() }
}

function Get-PidState([string] $Component) {
    $path = Join-Path $script:PidDir ($Component + '.json')
    if (-not [IO.File]::Exists($path)) { return $null }
    try { return ([IO.File]::ReadAllText($path) | ConvertFrom-Json) } catch { return $null }
}

function Get-ReadyState([string] $Component) {
    $path = Join-Path $script:PidDir ($Component + '.ready.json')
    if (-not [IO.File]::Exists($path)) { return $null }
    try { return ([IO.File]::ReadAllText($path) | ConvertFrom-Json) } catch { return $null }
}

function Get-FilePrefixHash([string] $Path, [int] $MaximumLength = 4096) {
    if (-not [IO.File]::Exists($Path)) { return '' }
    $stream = New-Object IO.FileStream($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        $length = [Math]::Min($MaximumLength, [int]$stream.Length)
        if ($length -eq 0) { return '' }
        $buffer = New-Object byte[] $length
        $read = $stream.Read($buffer, 0, $length)
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($buffer, 0, $read))).Replace('-','') } finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
}

function Get-LogCheckpoint([string] $WorkingDirectory) {
    $logDirectory = Join-Path $WorkingDirectory 'logs'
    $latest = Get-ChildItem -LiteralPath $logDirectory -Filter '*.log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $latest) { return [pscustomobject]@{ Path=''; Length=0L; CreationTicks=0L; PrefixHash=''; PrefixLength=0 } }
    $prefixLength = [Math]::Min(4096, [int]$latest.Length)
    return [pscustomobject]@{ Path=$latest.FullName; Length=[Int64]$latest.Length; CreationTicks=$latest.CreationTimeUtc.Ticks; PrefixHash=(Get-FilePrefixHash $latest.FullName $prefixLength); PrefixLength=$prefixLength }
}

function Read-LogSinceCheckpoint([string] $WorkingDirectory, $Checkpoint) {
    $logDirectory = Join-Path $WorkingDirectory 'logs'
    $latest = Get-ChildItem -LiteralPath $logDirectory -Filter '*.log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $latest) { return '' }
    $offset = 0L
    $oldPath = [string](Get-ObjectValue $Checkpoint 'Path' '')
    $oldCreation = [Int64](Get-ObjectValue $Checkpoint 'CreationTicks' 0L)
    $oldPrefix = [string](Get-ObjectValue $Checkpoint 'PrefixHash' '')
    $oldPrefixLength = [int](Get-ObjectValue $Checkpoint 'PrefixLength' 0)
    if ($oldPath -and $oldPrefix -and $oldPrefixLength -gt 0 -and [string]::Equals([IO.Path]::GetFullPath($oldPath), $latest.FullName, [StringComparison]::OrdinalIgnoreCase) -and $oldCreation -eq $latest.CreationTimeUtc.Ticks -and $oldPrefix -eq (Get-FilePrefixHash $latest.FullName $oldPrefixLength)) {
        $offset = [Int64](Get-ObjectValue $Checkpoint 'Length' 0L)
        if ($latest.Length -lt $offset) { $offset = 0L }
    }
    $stream = New-Object IO.FileStream($latest.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        [void]$stream.Seek($offset, [IO.SeekOrigin]::Begin)
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $true)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }
}

function Get-ExpectedComponentJar([string] $Component) {
    switch ($Component) {
        'backend' { return [IO.Path]::GetFullPath((Join-Path $script:Root 'server.jar')) }
        'limbo' { return [IO.Path]::GetFullPath((Join-Path $script:Limbo 'paper.jar')) }
        'velocity' { return [IO.Path]::GetFullPath((Join-Path $script:Velocity 'velocity.jar')) }
        default { Fail ('Unknown component: ' + $Component) }
    }
}

function Get-PidStateProcess($State) {
    if (-not $State) { return $null }
    try {
        $processId = [int](Get-ObjectValue $State 'pid' 0)
        $ticks = 0L
        if ($processId -le 0 -or -not [Int64]::TryParse([string](Get-ObjectValue $State 'startTicks' ''), [ref]$ticks)) { return $null }
        $process = Get-Process -Id $processId -ErrorAction Stop
        if ($process.StartTime.ToUniversalTime().Ticks -ne $ticks) { return $null }
        return $process
    } catch { return $null }
}

function Get-ExactOwnedProcess([string] $Component, [string] $RequiredNonce = '') {
    if (-not (Test-ExactOwnedProcess $Component $RequiredNonce)) { return $null }
    $state = Get-PidState $Component
    try {
        return (Get-Process -Id ([int](Get-ObjectValue $state 'pid' 0)) -ErrorAction Stop)
    } catch { return $null }
}

function Test-ExactOwnedProcess([string] $Component, [string] $RequiredNonce = '') {
    $state = Get-PidState $Component
    if (-not $state) { return $false }
    try {
        if ([string](Get-ObjectValue $state 'component' '') -ne $Component) { return $false }
        if ($RequiredNonce -and [string](Get-ObjectValue $state 'nonce' '') -ne $RequiredNonce) { return $false }
        $stateJar = [IO.Path]::GetFullPath([string](Get-ObjectValue $state 'jar' ''))
        $expectedJar = Get-ExpectedComponentJar $Component
        if (-not [string]::Equals($stateJar, $expectedJar, [StringComparison]::OrdinalIgnoreCase)) { return $false }
        $processId = [int](Get-ObjectValue $state 'pid' 0)
        $ticks = 0L
        if ($processId -le 0 -or -not [Int64]::TryParse([string](Get-ObjectValue $state 'startTicks' ''), [ref]$ticks)) { return $false }
        $process = Get-Process -Id $processId -ErrorAction Stop
        # The state is written only after this supervisor created Java.
        # Component + unguessable nonce + PID + exact start time reject PID reuse
        # without transient Win32_Process/CIM queries.
        return [bool]($process.StartTime.ToUniversalTime().Ticks -eq $ticks)
    } catch { return $false }
}

function Test-ManagedProcess([string] $Component) {
    $state = Get-PidState $Component
    if (-not $state -or -not (Test-ExactOwnedProcess $Component)) { return $false }
    try {
        $runnerPid = [int](Get-ObjectValue $state 'runnerPid' 0)
        $runnerTicks = 0L
        if ($runnerPid -le 0 -or -not [Int64]::TryParse([string](Get-ObjectValue $state 'runnerStartTicks' ''), [ref]$runnerTicks)) { return $false }
        $runner = Get-Process -Id $runnerPid -ErrorAction Stop
        return $runner.StartTime.ToUniversalTime().Ticks -eq $runnerTicks
    } catch { return $false }
}

function Assert-PortIsManagedOrFree([string] $Component) {
    $definition = Get-ComponentDefinition $Component
    if ((Test-Port $definition.ProbeHost $definition.Port) -and -not (Test-ManagedProcess $Component)) {
        Fail ('Endpoint ' + $definition.ProbeHost + ':' + $definition.Port + ' is occupied by an unmanaged process. Stop it manually; this deployer never taskkills unknown Java processes.')
    }
}

function Write-ControlCommand([string] $Component, [string] $Command) {
    Ensure-Directory $script:CommandDir
    $path = Join-Path $script:CommandDir ($Component + '.command')
    Write-AtomicText $path ($Command + "`n") $script:Utf8NoBom
}

function Test-RunnerLockAvailable([string] $Component) {
    $lockPath = Join-Path $script:PidDir ($Component + '.runner.lock')
    if (-not [IO.File]::Exists($lockPath)) { return $true }
    $probe = $null
    try {
        $probe = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        return $true
    } catch { return $false }
    finally { if ($probe) { $probe.Dispose() } }
}

function Wait-ComponentStopped([string] $Component, [int] $TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Test-ExactOwnedProcess $Component) -and (Test-RunnerLockAvailable $Component)) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Stop-Component([string] $Component) {
    $exactProcess = Get-ExactOwnedProcess $Component
    if (-not $exactProcess) {
        $definition = $null
        try { $definition = Get-ComponentDefinition $Component } catch {}
        if ($definition -and (Test-Port $definition.ProbeHost $definition.Port)) { Fail ('Cannot stop ' + $Component + ': endpoint is owned by an unmanaged process.') }
        Write-Info ($Component + ' is already stopped.')
        return
    }
    if (-not (Test-ManagedProcess $Component)) {
        Write-WarningLine ($Component + ' supervisor is unavailable; stopping only its exact PID/start-time-owned Java child.')
        try {
            $exactProcess.Kill()
            if (-not $exactProcess.WaitForExit(30000)) { Fail ('Exact owned ' + $Component + ' process did not exit within 30 seconds.') }
        } catch { Fail ('Could not stop exact owned ' + $Component + ' process: ' + $_.Exception.Message) }
        if (-not (Wait-ComponentStopped $Component 30)) { Fail ($Component + ' exact child exited, but its supervisor lock is still active.') }
        Write-Ok ($Component + ' exact owned child was stopped; no process discovered by name/port was killed.')
        return
    }
    $stopCommand = if ($Component -eq 'velocity') { 'shutdown' } else { 'stop' }
    Write-Info ('Stopping ' + $Component + ' gracefully with "' + $stopCommand + '"...')
    Write-ControlCommand $Component $stopCommand
    if (-not (Wait-ComponentStopped $Component 120)) {
        Fail ($Component + ' did not stop gracefully within 120 seconds. It was NOT force-killed; use its console to investigate.')
    }
    Write-Ok ($Component + ' stopped cleanly.')
}

function Stop-NewlyLaunchedComponent([string] $Component, [string] $Nonce) {
    $state = Get-PidState $Component
    if (-not $state -or [string](Get-ObjectValue $state 'nonce' '') -ne $Nonce -or -not (Test-ExactOwnedProcess $Component $Nonce)) { return }
    Write-WarningLine ('Stopping the newly launched ' + $Component + ' because its readiness check failed.')
    if (Test-ManagedProcess $Component) { try {
        $stopCommand = if ($Component -eq 'velocity') { 'shutdown' } else { 'stop' }
        Write-ControlCommand $Component $stopCommand
        if (Wait-ComponentStopped $Component 30) { return }
    } catch {} }

    # This PID and start time came from the launch nonce created by this exact
    # call.  It is safe to clean up this child only; no process is discovered or
    # killed by port/name.
    $state = Get-PidState $Component
    if (-not $state -or [string](Get-ObjectValue $state 'nonce' '') -ne $Nonce) { return }
    try {
        $process = Get-ExactOwnedProcess $Component $Nonce
        if (-not $process) { return }
        $process.Kill()
        if (-not $process.WaitForExit(30000)) { Fail ('The exact newly launched ' + $Component + ' child could not be stopped after readiness failure.') }
        if (-not (Wait-ComponentStopped $Component 30)) { Fail ($Component + ' child exited, but its supervisor lock was not released after readiness failure.') }
        Write-WarningLine ($Component + ' exact child was force-stopped after graceful cleanup timed out.')
    } catch { if (Test-ExactOwnedProcess $Component $Nonce) { throw } }
}

function Start-Component([string] $Component) {
    $definition = Get-ComponentDefinition $Component
    $existingExact = Get-ExactOwnedProcess $Component
    if ($existingExact -and -not (Test-ManagedProcess $Component)) {
        Fail ($Component + ' has an exact PID/start-time-owned Java child but its supervisor is unavailable. Stop that component before starting another copy.')
    }
    if ($existingExact) {
        if (Test-Port $definition.ProbeHost $definition.Port) {
            $pidState = Get-PidState $Component
            $readyState = Get-ReadyState $Component
            if ($readyState -and $pidState -and [string](Get-ObjectValue $readyState 'nonce' 'legacy') -eq [string](Get-ObjectValue $pidState 'nonce' 'legacy') -and [int](Get-ObjectValue $readyState 'pid' 0) -eq [int](Get-ObjectValue $pidState 'pid' -1)) {
                Write-Info ($Component + ' is already running and KoFAuth-ready.')
                return $false
            }
            Wait-KoFAuthLog $Component $(if ($Component -eq 'backend') { 'BACKEND' } elseif ($Component -eq 'limbo') { 'LIMBO' } else { '' })
            return $false
        }
        Fail ($Component + ' process exists but port ' + $definition.Port + ' is not ready. Check its console.')
    }
    Assert-PortIsManagedOrFree $Component
    if (-not [IO.File]::Exists($definition.Jar)) { Fail ($Component + ' runtime JAR is missing: ' + $definition.Jar) }
    Ensure-Directory $script:PidDir
    Ensure-Directory $script:CommandDir
    if (-not (Test-RunnerLockAvailable $Component)) { Fail ($Component + ' supervisor lock is still held. Wait for the prior STOP/cleanup to finish before START.') }
    $pidPath = Join-Path $script:PidDir ($Component + '.json')
    $exitPath = Join-Path $script:PidDir ($Component + '.exit.json')
    $readyPath = Join-Path $script:PidDir ($Component + '.ready.json')
    $commandPath = Join-Path $script:CommandDir ($Component + '.command')
    $launchPath = Join-Path $script:PidDir ($Component + '.launch.json')
    foreach ($stale in @($pidPath,$exitPath,$readyPath,$commandPath,$launchPath)) { if ([IO.File]::Exists($stale)) { [IO.File]::Delete($stale) } }
    $nonce = [Guid]::NewGuid().ToString('N')
    $launch = [ordered]@{ component=$Component; nonce=$nonce; requestedAt=[DateTime]::UtcNow.ToString('o') }
    Write-AtomicText $launchPath (($launch | ConvertTo-Json -Depth 3) + "`n") $script:Utf8NoBom
    $argument = '/d /c ""' + $script:SelfPath + '" runner-' + $Component + '"'
    Write-Info ('Starting ' + $Component + $(if ($script:InteractiveStart) { ' in its own console window...' } else { ' with a hidden supervisor...' }))
    if ($script:InteractiveStart) {
        Start-Process -FilePath $env:ComSpec -ArgumentList $argument -WorkingDirectory $definition.Work | Out-Null
    } else {
        Start-Process -FilePath $env:ComSpec -ArgumentList $argument -WorkingDirectory $definition.Work -WindowStyle Hidden | Out-Null
    }
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(240)
        while ([DateTime]::UtcNow -lt $deadline) {
            $pidState = Get-PidState $Component
            $matchingPidState = $pidState -and [string](Get-ObjectValue $pidState 'nonce') -eq $nonce
            if ($matchingPidState -and (Test-ExactOwnedProcess $Component $nonce) -and (Test-ManagedProcess $Component) -and (Test-Port $definition.ProbeHost $definition.Port)) {
                Write-Ok ($Component + ' is listening on ' + $definition.ProbeHost + ':' + $definition.Port + '.')
                Wait-KoFAuthLog $Component $(if ($Component -eq 'backend') { 'BACKEND' } elseif ($Component -eq 'limbo') { 'LIMBO' } else { '' })
                return $true
            }
            if ([IO.File]::Exists($exitPath)) {
                $exitState = $null
                try { $exitState = [IO.File]::ReadAllText($exitPath) | ConvertFrom-Json; $exitDetail = 'exit code ' + [string]$exitState.exitCode }
                catch { $exitDetail = 'an unknown exit code' }
                if ($exitState -and [string](Get-ObjectValue $exitState 'nonce') -eq $nonce) { Fail ($Component + ' exited during startup (' + $exitDetail + '). Read its latest log.') }
            }
            # Do not infer child death from a transient Windows process-query
            # miss. The supervisor writes a nonce-bound exit.json on both clean
            # and exceptional exits; only that durable handshake may fail early.
            Start-Sleep -Seconds 1
        }
        Fail ($Component + ' did not become ready within 240 seconds.')
    } catch {
        $startupFailure = $_
        Stop-NewlyLaunchedComponent $Component $nonce
        throw $startupFailure
    }
}

function Set-ProcessEnvironment([Diagnostics.ProcessStartInfo] $StartInfo, [string] $Component) {
    $mapping = [ordered]@{
        'KOFAUTH_DATABASE_MYSQL_HOST' = '127.0.0.1'
        'KOFAUTH_DATABASE_MYSQL_PORT' = (Get-EnvValue 'MYSQL_PORT' '3306')
        'KOFAUTH_DATABASE_MYSQL_DATABASE' = 'kofauth'
        'KOFAUTH_DATABASE_MYSQL_USERNAME' = 'kofauth'
        'KOFAUTH_DATABASE_MYSQL_PASSWORD' = (Get-EnvValue 'MYSQL_PASSWORD')
        'KOFAUTH_DATABASE_MYSQL_MIGRATE_ON_STARTUP' = 'false'
        'KOFAUTH_DATABASE_REDIS_ENABLED' = 'true'
        'KOFAUTH_DATABASE_REDIS_HOST' = '127.0.0.1'
        'KOFAUTH_DATABASE_REDIS_PORT' = (Get-EnvValue 'REDIS_PORT' '6379')
        'KOFAUTH_DATABASE_REDIS_PASSWORD' = (Get-EnvValue 'REDIS_PASSWORD')
        'KOFAUTH_DATABASE_REDIS_DATABASE' = '0'
        'KOFAUTH_SECURITY_ENCRYPTION_KEY' = (Get-EnvValue 'ENCRYPTION_KEY')
        'KOFAUTH_SECURITY_JWT_SECRET' = (Get-EnvValue 'JWT_SECRET')
        'KOFAUTH_SECURITY_BOT_API_KEY' = (Get-EnvValue 'BOT_API_KEY')
        'KOFAUTH_TELEGRAM_ENABLED' = (Get-EnvValue 'TELEGRAM_ENABLED' 'false')
        'KOFAUTH_TELEGRAM_BOT_USERNAME' = (Get-EnvValue 'TELEGRAM_BOT_USERNAME')
        'KOFAUTH_DISCORD_ENABLED' = (Get-EnvValue 'DISCORD_ENABLED' 'false')
        'KOFAUTH_DISCORD_BOT_GUILD_ID' = (Get-EnvValue 'DISCORD_GUILD_ID')
        'KOFAUTH_DISCORD_ACCOUNT_CHANNEL_ID' = (Get-EnvValue 'DISCORD_ACCOUNT_CHANNEL_ID')
        'KOFAUTH_DISCORD_INVITE_URL' = (Get-EnvValue 'DISCORD_INVITE_URL')
        'KOFAUTH_DISCORD_LINK_POST_CODE_TO_CHANNEL' = (Get-EnvValue 'DISCORD_POST_CODE_TO_CHANNEL' 'false')
        'KOFAUTH_MAIL_ENABLED' = (Get-EnvValue 'MAIL_ENABLED' 'false')
        'KOFAUTH_MAIL_SMTP_HOST' = (Get-EnvValue 'SMTP_HOST')
        'KOFAUTH_MAIL_SMTP_PORT' = (Get-EnvValue 'SMTP_PORT' '587')
        'KOFAUTH_MAIL_SMTP_USERNAME' = (Get-EnvValue 'SMTP_USERNAME')
        'KOFAUTH_MAIL_SMTP_PASSWORD' = (Get-EnvValue 'SMTP_PASSWORD')
        'KOFAUTH_MAIL_SMTP_STARTTLS' = (Get-EnvValue 'SMTP_STARTTLS' 'true')
        'KOFAUTH_MAIL_SMTP_SSL' = (Get-EnvValue 'SMTP_SSL' 'false')
        'KOFAUTH_MAIL_FROM_ADDRESS' = (Get-EnvValue 'MAIL_FROM' 'noreply@example.com')
        'KOFAUTH_MAIL_FROM_NAME' = (Get-EnvValue 'MAIL_FROM_NAME' 'KoF Network')
        'KOFAUTH_LOG_LEVEL' = (Get-EnvValue 'LOG_LEVEL' 'INFO')
        'TZ' = 'UTC'
    }
    if ($Component -eq 'backend') { $mapping['KOFAUTH_PAPER_MODE'] = 'BACKEND'; $mapping['PAPER_VELOCITY_SECRET'] = (Get-EnvValue 'FORWARDING_SECRET') }
    if ($Component -eq 'limbo') { $mapping['KOFAUTH_PAPER_MODE'] = 'LIMBO'; $mapping['PAPER_VELOCITY_SECRET'] = (Get-EnvValue 'FORWARDING_SECRET') }
    if ($Component -eq 'velocity') {
        $mapping['KOFAUTH_VELOCITY_LIMBO_SERVERS'] = 'limbo-1'
        $mapping['KOFAUTH_VELOCITY_HUB_SERVERS'] = 'hub-1'
    }
    # PowerShell 5.1/.NET Framework can throw while materializing
    # ProcessStartInfo.EnvironmentVariables when the inherited Windows block
    # contains both Path and PATH.  This supervisor is a dedicated process, so
    # set its process environment directly and let Java inherit it.  Secrets
    # remain out of command lines and logs.
    foreach ($entry in $mapping.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, [EnvironmentVariableTarget]::Process) }
}

function Run-ComponentSupervisor([string] $Component) {
    Ensure-Directory $script:PidDir
    Ensure-Directory $script:CommandDir
    if (-not [IO.File]::Exists($script:EnvFile)) { Fail 'Run deploy.bat deploy once before starting components.' }
    $script:Env = Read-EnvMap $script:EnvFile
    $definition = Get-ComponentDefinition $Component
    $lockPath = Join-Path $script:PidDir ($Component + '.runner.lock')
    $runnerLock = $null
    $launchNonce = $null
    $process = $null
    try {
        try { $runnerLock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
        catch { Fail ($Component + ' supervisor is already running.') }
        $launchPath = Join-Path $script:PidDir ($Component + '.launch.json')
        if (-not [IO.File]::Exists($launchPath)) { Fail ($Component + ' launch handshake is missing; start it through deploy.bat or a generated START BAT.') }
        try { $launch = [IO.File]::ReadAllText($launchPath) | ConvertFrom-Json } catch { Fail ($Component + ' launch handshake is invalid.') }
        $launchNonce = [string]$launch.nonce
        if ([string]$launch.component -ne $Component -or $launchNonce -notmatch '^[a-f0-9]{32}$') { Fail ($Component + ' launch handshake does not match this supervisor.') }
        $commandPath = Join-Path $script:CommandDir ($Component + '.command')
        if ([IO.File]::Exists($commandPath)) { [IO.File]::Delete($commandPath) }
        Assert-PortIsManagedOrFree $Component
        $java = Get-JavaPathAndVersion 21
        $heap = Get-EnvValue $definition.HeapKey $definition.Heap
        if ($heap -notmatch '^\d+[mMgG]$') { Fail ('Invalid heap value ' + $definition.HeapKey + '=' + $heap) }
        $psi = New-Object Diagnostics.ProcessStartInfo
        $psi.FileName = $java.Path
        $psi.WorkingDirectory = $definition.Work
        $psi.UseShellExecute = $false
        $psi.RedirectStandardInput = $true
        $psi.CreateNoWindow = $false
        $psi.Arguments = '-Xms256M -Xmx' + $heap + ' -Duser.timezone=UTC -jar "' + $definition.Jar + '"'
        if ($definition.Extra) { $psi.Arguments += ' ' + $definition.Extra }
        Set-ProcessEnvironment $psi $Component
        try { $Host.UI.RawUI.WindowTitle = '[KoFAuth] ' + $Component } catch {}
        Write-Host ('[KoFAuth] Starting ' + $Component + '. Use generated STOP BAT for a safe shutdown.')
        $process = New-Object Diagnostics.Process
        $process.StartInfo = $psi
        $checkpoint = Get-LogCheckpoint $definition.Work
        if (-not $process.Start()) { Fail ('Could not start Java for ' + $Component) }
        $state = [ordered]@{
            component = $Component
            nonce = $launchNonce
            pid = $process.Id
            startTicks = $process.StartTime.ToUniversalTime().Ticks.ToString()
            jar = [IO.Path]::GetFullPath($definition.Jar)
            runnerPid = $PID
            runnerStartTicks = (Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks.ToString()
            startedAt = [DateTime]::UtcNow.ToString('o')
            logPath = $checkpoint.Path
            logLength = $checkpoint.Length
            logCreationTicks = $checkpoint.CreationTicks
            logPrefixHash = $checkpoint.PrefixHash
            logPrefixLength = $checkpoint.PrefixLength
        }
        $pidPath = Join-Path $script:PidDir ($Component + '.json')
        $exitPath = Join-Path $script:PidDir ($Component + '.exit.json')
        if ([IO.File]::Exists($exitPath)) { [IO.File]::Delete($exitPath) }
        Write-AtomicText $pidPath (($state | ConvertTo-Json -Depth 4) + "`n") $script:Utf8NoBom
        if ([IO.File]::Exists($launchPath)) { [IO.File]::Delete($launchPath) }
        while (-not $process.HasExited) {
            if ([IO.File]::Exists($commandPath)) {
                $commands = [IO.File]::ReadAllLines($commandPath)
                [IO.File]::Delete($commandPath)
                foreach ($command in $commands) {
                    if (-not [string]::IsNullOrWhiteSpace($command)) { $process.StandardInput.WriteLine($command); $process.StandardInput.Flush() }
                }
            }
            try {
                if ([Console]::KeyAvailable) {
                    $line = [Console]::ReadLine()
                    if ($null -ne $line) { $process.StandardInput.WriteLine($line); $process.StandardInput.Flush() }
                }
            } catch {}
            Start-Sleep -Milliseconds 200
        }
        $process.WaitForExit()
        $exitCode = $process.ExitCode
        $exitState = [ordered]@{ component=$Component; nonce=$launchNonce; exitCode=$exitCode; exitedAt=[DateTime]::UtcNow.ToString('o') }
        Write-AtomicText $exitPath (($exitState | ConvertTo-Json -Depth 3) + "`n") $script:Utf8NoBom
        $current = Get-PidState $Component
        if ($current -and [int]$current.pid -eq $process.Id -and [IO.File]::Exists($pidPath)) { [IO.File]::Delete($pidPath) }
        if ([IO.File]::Exists($commandPath)) { [IO.File]::Delete($commandPath) }
        Write-Host ('[KoFAuth] ' + $Component + ' exited with code ' + $exitCode + '.')
        if ($exitCode -ne 0) { exit $exitCode }
    } catch {
        if ($process -and -not $process.HasExited) {
            try { $process.StandardInput.WriteLine($definition.Stop); $process.StandardInput.Flush() } catch {}
            try { [void]$process.WaitForExit(30000) } catch {}
            if (-not $process.HasExited) {
                # Exact child owned by this supervisor, never a PID discovered
                # from a port/name. Avoid leaving an unmanaged Java process if
                # state/journal I/O itself failed.
                try { $process.Kill(); [void]$process.WaitForExit(30000) } catch {}
            }
        }
        if ($launchNonce) {
            try {
                $failurePath = Join-Path $script:PidDir ($Component + '.exit.json')
                $failure = [ordered]@{ component=$Component; nonce=$launchNonce; exitCode=-1; exitedAt=[DateTime]::UtcNow.ToString('o') }
                Write-AtomicText $failurePath (($failure | ConvertTo-Json -Depth 3) + "`n") $script:Utf8NoBom
            } catch {}
        }
        throw
    } finally {
        if ($runnerLock) { $runnerLock.Dispose() }
    }
}

function Acquire-DeployLock {
    Ensure-Directory $script:Runtime
    $path = Join-Path $script:Runtime 'deploy.lock'
    try { $script:LockHandle = [IO.File]::Open($path, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { Fail 'Another KoFAuth deploy/control operation is already running.' }
}

function Initialize-Layout {
    foreach ($path in @($script:Managed,$script:Runtime,$script:Config,$script:Artifacts,$script:Backups,$script:Commands,$script:PidDir,$script:CommandDir,$script:Limbo,$script:Velocity,$script:WebConfig)) { Ensure-Directory $path }
    $script:LogFile = Join-Path $script:Runtime ('deploy-' + $script:TransactionStamp + '.log')
    [IO.File]::WriteAllText($script:LogFile, ('KoFAuth deployment ' + [DateTime]::UtcNow.ToString('o') + [Environment]::NewLine), $script:Utf8NoBom)
}

function Ensure-Eula {
    $rootEula = Join-Path $script:Root 'eula.txt'
    $accepted = $false
    if ([IO.File]::Exists($rootEula)) { $accepted = [IO.File]::ReadAllText($rootEula) -match '(?im)^eula\s*=\s*true\s*$' }
    if (-not $accepted) {
        if ($env:KOFAUTH_ACCEPT_EULA -eq '1') { $answer = 'YES' }
        else {
            Write-Host ''
            Write-Host 'Minecraft requires acceptance of the Mojang EULA: https://aka.ms/MinecraftEULA' -ForegroundColor Yellow
            $answer = Read-Host 'Type YES to accept and continue'
        }
        if ($answer -ne 'YES') { Fail 'EULA was not accepted; no Minecraft server was started.' }
        Backup-File $rootEula
        Write-AtomicText $rootEula "eula=true`n" $script:Ascii
    }
    $limboEula = Join-Path $script:Limbo 'eula.txt'
    if (-not [IO.File]::Exists($limboEula) -or [IO.File]::ReadAllText($limboEula) -notmatch '(?im)^eula\s*=\s*true\s*$') {
        Backup-File $limboEula
        Write-AtomicText $limboEula "eula=true`n" $script:Ascii
    }
}

function Validate-VelocityRuntime([string] $Path) {
    if (-not [IO.File]::Exists($Path)) { return $false }
    try {
        $manifest = Read-ZipEntry $Path 'META-INF/MANIFEST.MF'
        return ($manifest -match '(?im)^Main-Class:\s*com\.velocitypowered\.')
    } catch { return $false }
}

function Download-VelocityRuntime {
    $destination = Join-Path $script:Velocity 'velocity.jar'
    if ([IO.File]::Exists($destination)) {
        if (-not (Validate-VelocityRuntime $destination)) { Fail ('Existing Velocity runtime is invalid: ' + $destination) }
        Write-Info 'Velocity runtime already exists; automatic production upgrades are disabled.'
        return
    }
    Write-Info 'Downloading pinned Velocity 3.4.0 stable build from PaperMC Fill API...'
    $headers = @{ 'User-Agent' = 'KoFAuth-Windows-Deployer/1.0 (https://github.com/Renamekk/KoFNetwork-Auth)' }
    $response = Invoke-RestMethod -UseBasicParsing -Headers $headers -Uri 'https://fill.papermc.io/v3/projects/velocity/versions/3.4.0/builds'
    if ($response -is [Array]) { $rawBuilds = @($response) }
    elseif ($response.PSObject.Properties.Name -contains 'builds') { $rawBuilds = @($response.builds) }
    else { $rawBuilds = @($response) }
    $builds = @($rawBuilds | Where-Object { ([string]$_.channel).ToUpperInvariant() -eq 'STABLE' } | Sort-Object {[int]$_.id} -Descending)
    if ($builds.Count -eq 0) { Fail 'PaperMC returned no stable Velocity 3.4.0 build.' }
    $selected = $builds[0]
    $download = $null
    foreach ($property in $selected.downloads.PSObject.Properties) {
        if ([string]$property.Value.name -like '*.jar') { $download = $property.Value; break }
    }
    if (-not $download -or -not $download.url -or -not $download.checksums.sha256) { Fail 'PaperMC response did not include a Velocity JAR URL and SHA-256.' }
    $temporary = $destination + '.download-' + [Guid]::NewGuid().ToString('N')
    try {
        Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri ([string]$download.url) -OutFile $temporary
        $actual = (Get-FileHash256 $temporary).ToLowerInvariant()
        $expected = ([string]$download.checksums.sha256).ToLowerInvariant()
        if ($actual -ne $expected) { Fail 'Downloaded Velocity JAR failed the PaperMC SHA-256 check.' }
        if (-not (Validate-VelocityRuntime $temporary)) { Fail 'Downloaded file is not a valid Velocity runtime.' }
        [IO.File]::Move($temporary, $destination)
        Write-Ok ('Velocity build ' + $selected.id + ' installed; SHA-256 ' + $actual.Substring(0, 12) + '.')
    } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
}

function Ensure-CoreRuntimes([pscustomobject] $ServerMetadata) {
    $serverJar = Join-Path $script:Root 'server.jar'
    $limboJar = Join-Path $script:Limbo 'paper.jar'
    if (-not [IO.File]::Exists($limboJar)) {
        [IO.File]::Copy($serverJar, $limboJar, $false)
        Write-Ok ('Created Limbo with the same Paper/Purpur core (' + $ServerMetadata.Minecraft + ').')
    } else {
        $limboMeta = Get-ServerMetadata $limboJar
        if ($limboMeta.Minecraft -ne $ServerMetadata.Minecraft) {
            Fail ('Limbo core version ' + $limboMeta.Minecraft + ' differs from backend ' + $ServerMetadata.Minecraft + '. Replace _kofauth\limbo\paper.jar intentionally before deploying.')
        }
    }
    Download-VelocityRuntime
}

function Get-PropertyValue([string] $Path, [string] $Key, [string] $Default) {
    if (-not [IO.File]::Exists($Path)) { return $Default }
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match ('^\s*' + [regex]::Escape($Key) + '\s*=\s*(?<value>.*)$')) { return $Matches.value.Trim() }
    }
    return $Default
}

function Initialize-JavaConfiguration([string] $Kind, [string] $WorkingDirectory, [string] $Jar, [string] $ExpectedConfig) {
    if ([IO.File]::Exists($ExpectedConfig)) { return }
    Write-Info ('Running ' + $Kind + ' once to generate its native configuration...')
    $java = Get-JavaPathAndVersion 21
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $java.Path
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.CreateNoWindow = $false
    $psi.Arguments = '-Xms256M -Xmx1024M -Duser.timezone=UTC -jar "' + $Jar + '"'
    if ($Kind -ne 'velocity') { $psi.Arguments += ' --nogui' }
    if ($script:Env.Count -gt 0) { Set-ProcessEnvironment $psi $Kind }
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $psi
    $ready = $false
    $started = $false
    $forcedCleanup = $false
    $checkpoint = Get-LogCheckpoint $WorkingDirectory
    try {
        if (-not $process.Start()) { Fail ('Could not start ' + $Kind + ' for configuration generation.') }
        $started = $true
        $deadline = [DateTime]::UtcNow.AddSeconds(240)
        while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
            if ([IO.File]::Exists($ExpectedConfig)) {
                $freshLog = Read-LogSinceCheckpoint $WorkingDirectory $checkpoint
                if ($freshLog -match '(?i)Done \(' -or $freshLog -match '(?i)Listening on') { $ready = $true; break }
            }
            Start-Sleep -Seconds 1
        }
    } finally {
        if ($started -and -not $process.HasExited) {
            try {
                $process.StandardInput.WriteLine($(if ($Kind -eq 'velocity') { 'shutdown' } else { 'stop' }))
                $process.StandardInput.Flush()
            } catch {}
            if (-not $process.WaitForExit(120000)) {
                # This is the exact short-lived child created above, never a
                # process found by name/port.  Cleaning it prevents a failed
                # bootstrap from becoming an unmanaged permanent blocker.
                Write-WarningLine ($Kind + ' configuration generator ignored graceful shutdown; terminating that exact owned child process.')
                try { $process.Kill(); [void]$process.WaitForExit(30000) } catch {}
                $forcedCleanup = $true
            }
        }
    }
    if ($forcedCleanup) { Fail ($Kind + ' required forced cleanup after configuration generation. Inspect its fresh log before retrying.') }
    if (-not $ready -or -not [IO.File]::Exists($ExpectedConfig)) { Fail ($Kind + ' failed to generate a usable configuration. Check ' + (Join-Path $WorkingDirectory 'logs')) }
    Write-Ok ($Kind + ' native configuration generated and stopped cleanly.')
}

function Initialize-JavaConfigurationWithoutKoFAuth([string] $Kind, [string] $WorkingDirectory, [string] $Jar, [string] $ExpectedConfig, [string] $PluginPath) {
    if ([IO.File]::Exists($ExpectedConfig)) { return }
    Backup-File $ExpectedConfig
    $disabled = $null
    if ($PluginPath -and [IO.File]::Exists($PluginPath)) {
        $disabledDirectory = Join-Path $script:Runtime 'bootstrap-disabled'
        Ensure-Directory $disabledDirectory
        $disabled = Join-Path $disabledDirectory ($Kind + '-' + [Guid]::NewGuid().ToString('N') + '.jar.disabled')
        Backup-File $PluginPath
        Backup-File $disabled
        [IO.File]::Move($PluginPath, $disabled)
        Write-Info ('Temporarily isolated KoFAuth while generating the missing ' + $Kind + ' native config.')
    }
    try { Initialize-JavaConfiguration $Kind $WorkingDirectory $Jar $ExpectedConfig }
    finally {
        if ($disabled -and [IO.File]::Exists($disabled)) {
            if ([IO.File]::Exists($PluginPath)) { Fail ('Cannot restore isolated KoFAuth plugin because its original path was unexpectedly recreated: ' + $PluginPath) }
            [IO.File]::Move($disabled, $PluginPath)
        }
    }
}

function Set-PropertiesValues([string] $Path, [System.Collections.IDictionary] $Values) {
    $text = if ([IO.File]::Exists($Path)) { [IO.File]::ReadAllText($Path) } else { '' }
    $eol = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in [regex]::Split($text, '\r\n|\n|\r')) { $lines.Add($line) }
    $changed = $false
    foreach ($entry in $Values.GetEnumerator()) {
        $found = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match ('^\s*' + [regex]::Escape([string]$entry.Key) + '\s*=')) {
                $found = $true
                $desired = [string]$entry.Key + '=' + [string]$entry.Value
                if ($lines[$i] -ne $desired) { $lines[$i] = $desired; $changed = $true }
            }
        }
        if (-not $found) { $lines.Add([string]$entry.Key + '=' + [string]$entry.Value); $changed = $true }
    }
    if ($changed) {
        Backup-File $Path
        $output = [string]::Join($eol, $lines)
        if (-not $output.EndsWith($eol)) { $output += $eol }
        Write-AtomicText $Path $output $script:Utf8NoBom
    }
    return $changed
}

function Get-LineIndent([string] $Line) {
    if ($Line -match '^(?<space> *)') { return $Matches.space.Length }
    return 0
}

function Find-YamlChildIndex([System.Collections.Generic.List[string]] $Lines, [int] $ParentIndex, [string] $Key) {
    if ($ParentIndex -lt 0) { $start = 0; $end = $Lines.Count; $expected = 0 }
    else {
        $parentIndent = Get-LineIndent $Lines[$ParentIndex]
        $expected = $parentIndent + 2
        $start = $ParentIndex + 1
        $end = $Lines.Count
        for ($i = $start; $i -lt $Lines.Count; $i++) {
            if ($Lines[$i] -match '^\s*(?:#.*)?$') { continue }
            if ((Get-LineIndent $Lines[$i]) -le $parentIndent) { $end = $i; break }
        }
    }
    for ($i = $start; $i -lt $end; $i++) {
        if ((Get-LineIndent $Lines[$i]) -eq $expected -and $Lines[$i] -match ('^\s*' + [regex]::Escape($Key) + '\s*:(?<value>.*)$')) { return $i }
    }
    return -1
}

function Get-YamlScalarRawFromText([string] $Text, [string[]] $Keys) {
    $lines = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in [regex]::Split($Text, '\r\n|\n|\r')) { $lines.Add($line) }
    $parent = -1
    foreach ($key in $Keys) {
        $index = Find-YamlChildIndex $lines $parent $key
        if ($index -lt 0) { return $null }
        $parent = $index
    }
    if ($lines[$parent] -match '^\s*[^:]+:\s*(?<value>.*)$') { return $Matches.value.Trim() }
    return $null
}

function Get-StateManagedValue([string] $ManagedKey) {
    if (-not $script:State -or -not ($script:State.PSObject.Properties.Name -contains 'managedConfig') -or -not $script:State.managedConfig) { return $null }
    $property = $script:State.managedConfig.PSObject.Properties[$ManagedKey]
    if ($property) { return [string]$property.Value }
    return $null
}

function Set-YamlScalar([string] $Path, [string[]] $Keys, [string] $Desired, [string] $ManagedKey, [string[]] $LegacyValues, [switch] $Force) {
    if ($null -eq $Desired) { return 'same' }
    $text = if ([IO.File]::Exists($Path)) { [IO.File]::ReadAllText($Path) } else { '' }
    $eol = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in [regex]::Split($text, '\r\n|\n|\r')) { $lines.Add($line) }
    $parent = -1
    for ($depth = 0; $depth -lt $Keys.Count; $depth++) {
        $key = $Keys[$depth]
        $index = Find-YamlChildIndex $lines $parent $key
        if ($index -lt 0) {
            if ($parent -lt 0) { $insert = $lines.Count; $indent = 0 }
            else {
                $parentIndent = Get-LineIndent $lines[$parent]
                $insert = $parent + 1
                while ($insert -lt $lines.Count) {
                    if ($lines[$insert] -notmatch '^\s*(?:#.*)?$' -and (Get-LineIndent $lines[$insert]) -le $parentIndent) { break }
                    $insert++
                }
                $indent = $parentIndent + 2
            }
            for ($remaining = $depth; $remaining -lt $Keys.Count; $remaining++) {
                $isLeaf = $remaining -eq ($Keys.Count - 1)
                $newLine = (' ' * $indent) + $Keys[$remaining] + ':' + $(if ($isLeaf) { ' ' + $Desired } else { '' })
                $lines.Insert($insert, $newLine)
                $parent = $insert
                $insert++
                $indent += 2
            }
            Backup-File $Path
            $output = [string]::Join($eol, $lines)
            if (-not $output.EndsWith($eol)) { $output += $eol }
            Write-AtomicText $Path $output $script:Utf8NoBom
            if ($ManagedKey) { $script:ManagedDesired[$ManagedKey] = $Desired }
            return 'changed'
        }
        $parent = $index
    }

    $current = ''
    if ($lines[$parent] -match '^\s*[^:]+:\s*(?<value>.*)$') { $current = $Matches.value.Trim() }
    if ($current -eq $Desired -or (Normalize-YamlString $current) -ceq (Normalize-YamlString $Desired)) {
        if ($ManagedKey) { $script:ManagedDesired[$ManagedKey] = $Desired }
        return 'same'
    }
    $mayUpdate = $Force.IsPresent -or [string]::IsNullOrWhiteSpace($current)
    $previous = if ($ManagedKey) { Get-StateManagedValue $ManagedKey } else { $null }
    if ($previous -and $current -eq $previous) { $mayUpdate = $true }
    foreach ($legacy in @($LegacyValues)) { if ($null -ne $legacy -and $current -eq $legacy) { $mayUpdate = $true } }
    if (-not $mayUpdate) {
        $script:Conflicts.Add(($ManagedKey + ' in ' + (Get-RelativeSafePath $Path) + ' was customized; kept current value. Suggested: ' + $Desired))
        return 'conflict'
    }
    $indentText = ' ' * (Get-LineIndent $lines[$parent])
    $lines[$parent] = $indentText + $Keys[$Keys.Count - 1] + ': ' + $Desired
    Backup-File $Path
    $output = [string]::Join($eol, $lines)
    if (-not $output.EndsWith($eol)) { $output += $eol }
    Write-AtomicText $Path $output $script:Utf8NoBom
    if ($ManagedKey) { $script:ManagedDesired[$ManagedKey] = $Desired }
    return 'changed'
}

function Find-TomlSectionBounds([System.Collections.Generic.List[string]] $Lines, [string] $Section) {
    if ([string]::IsNullOrWhiteSpace($Section)) {
        $rootEnd = $Lines.Count
        for ($rootIndex = 0; $rootIndex -lt $Lines.Count; $rootIndex++) {
            if ($Lines[$rootIndex] -match '^\s*\[[^]]+\]\s*$') { $rootEnd = $rootIndex; break }
        }
        return [pscustomobject]@{ Start = 0; End = $rootEnd }
    }
    $start = -1
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match ('^\s*\[' + [regex]::Escape($Section) + '\]\s*$')) { $start = $i + 1; break }
    }
    if ($start -lt 0) { return $null }
    $end = $Lines.Count
    for ($i = $start; $i -lt $Lines.Count; $i++) { if ($Lines[$i] -match '^\s*\[[^]]+\]\s*$') { $end = $i; break } }
    return [pscustomobject]@{ Start = $start; End = $end }
}

function Get-TomlArrayBalance([string] $Line) {
    $balance = 0
    $quote = [char]0
    $escaped = $false
    for ($i = 0; $i -lt $Line.Length; $i++) {
        $ch = $Line[$i]
        if ($quote -ne [char]0) {
            if ($quote -eq '"' -and $ch -eq '\' -and -not $escaped) { $escaped = $true; continue }
            if ($ch -eq $quote -and -not $escaped) { $quote = [char]0 }
            $escaped = $false
            continue
        }
        if ($ch -eq '#') { break }
        if ($ch -eq '"' -or $ch -eq "'") { $quote = $ch; continue }
        if ($ch -eq '[') { $balance++ }
        elseif ($ch -eq ']') { $balance-- }
    }
    return $balance
}

function Set-TomlValue([string] $Path, [string] $Section, [string] $Key, [string] $Desired) {
    $text = if ([IO.File]::Exists($Path)) { [IO.File]::ReadAllText($Path) } else { '' }
    $eol = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in [regex]::Split($text, '\r\n|\n|\r')) { $lines.Add($line) }
    $bounds = Find-TomlSectionBounds $lines $Section
    if (-not $bounds) {
        if ($lines.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($lines[$lines.Count - 1])) { $lines.Add('') }
        $lines.Add('[' + $Section + ']')
        $lines.Add($Key + ' = ' + $Desired)
    } else {
        $found = -1
        for ($i = $bounds.Start; $i -lt $bounds.End; $i++) { if ($lines[$i] -match ('^\s*' + [regex]::Escape($Key) + '\s*=')) { $found = $i; break } }
        if ($found -ge 0) {
            $removedContinuation = $false
            $rightHand = $lines[$found].Substring($lines[$found].IndexOf('=') + 1)
            $arrayBalance = Get-TomlArrayBalance $rightHand
            while ($arrayBalance -gt 0 -and ($found + 1) -lt $lines.Count) {
                $continuation = $lines[$found + 1]
                if ($continuation -match '^\s*\[[^]]+\]\s*(?:#.*)?$') { Fail ('Malformed TOML array for ' + $Key + ' reaches a new section; ' + $Path + ' was not changed.') }
                $arrayBalance += Get-TomlArrayBalance $continuation
                $lines.RemoveAt($found + 1)
                $removedContinuation = $true
            }
            if ($arrayBalance -ne 0) { Fail ('Malformed TOML array for ' + $Key + ' in ' + $Path + '; file was not changed.') }
            $replacement = $Key + ' = ' + $Desired
            if ($lines[$found] -eq $replacement -and -not $removedContinuation) { return $false }
            $lines[$found] = $replacement
        } else { $lines.Insert($bounds.End, $Key + ' = ' + $Desired) }
    }
    Backup-File $Path
    $output = [string]::Join($eol, $lines)
    if (-not $output.EndsWith($eol)) { $output += $eol }
    Write-AtomicText $Path $output $script:Utf8NoBom
    return $true
}

function Get-TomlScalarRaw([string] $Path, [string] $Section, [string] $Key) {
    if (-not [IO.File]::Exists($Path)) { return $null }
    $lines = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in [regex]::Split([IO.File]::ReadAllText($Path), '\r\n|\n|\r')) { $lines.Add($line) }
    $bounds = Find-TomlSectionBounds $lines $Section
    if (-not $bounds) { return $null }
    $values = New-Object 'System.Collections.Generic.List[string]'
    for ($i = $bounds.Start; $i -lt $bounds.End; $i++) {
        if ($lines[$i] -match ('^\s*' + [regex]::Escape($Key) + '\s*=\s*(?<value>.*)$')) { $values.Add($Matches.value.Trim()) }
    }
    if ($values.Count -gt 1) { Fail ('Duplicate TOML key ' + $(if ($Section) { $Section + '.' } else { '' }) + $Key + ' in ' + $Path + '.') }
    if ($values.Count -eq 0) { return $null }
    return $values[0]
}

function Normalize-TomlScalar([string] $Raw) {
    if ($null -eq $Raw) { return $null }
    $value = $Raw.Trim()
    if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
        return $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Assert-PropertiesValues([string] $Path, [System.Collections.IDictionary] $Expected, [string] $Name) {
    if (-not [IO.File]::Exists($Path)) { Fail ($Name + ' is missing: ' + $Path + '. Run deploy.bat before START/RESTART.') }
    $found = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*(?<key>[^#!\s:=]+)\s*[:=]\s*(?<value>.*)$') {
            if (-not $found.ContainsKey($Matches.key)) { $found[$Matches.key] = New-Object 'System.Collections.Generic.List[string]' }
            $found[$Matches.key].Add($Matches.value.Trim())
        }
    }
    foreach ($entry in $Expected.GetEnumerator()) {
        if (-not $found.ContainsKey([string]$entry.Key) -or $found[[string]$entry.Key].Count -eq 0) { Fail ($Name + ' is missing required key ' + $entry.Key + '. Run deploy.bat; nothing was started.') }
        foreach ($actual in $found[[string]$entry.Key]) {
            if (-not [string]::Equals([string]$actual, [string]$entry.Value, [StringComparison]::OrdinalIgnoreCase)) {
                Fail ($Name + ' has unsafe/uncommitted ' + $entry.Key + '=' + $actual + '. Expected ' + $entry.Value + '; run deploy.bat before START/RESTART.')
            }
        }
    }
}

function Assert-ControlRuntimeConfiguration {
    $secret = Get-EnvValue 'FORWARDING_SECRET'
    Assert-PropertiesValues (Join-Path $script:Root 'server.properties') ([ordered]@{
        'server-ip'='127.0.0.1'; 'server-port'='25566'; 'online-mode'='false'; 'enforce-secure-profile'='false'
    }) 'Backend server.properties'
    Assert-PropertiesValues (Join-Path $script:Limbo 'server.properties') ([ordered]@{
        'server-ip'='127.0.0.1'; 'server-port'='25567'; 'online-mode'='false'; 'enforce-secure-profile'='false';
        'allow-nether'='false'; 'spawn-monsters'='false'; 'max-players'=(Get-EnvValue 'LIMBO_MAX_PLAYERS' '50')
    }) 'Limbo server.properties'

    foreach ($entry in @(
        [pscustomobject]@{ Name='Backend paper-global.yml'; Path=(Join-Path $script:Root 'config\paper-global.yml') },
        [pscustomobject]@{ Name='Limbo paper-global.yml'; Path=(Join-Path $script:Limbo 'config\paper-global.yml') }
    )) {
        if (-not [IO.File]::Exists($entry.Path)) { Fail ($entry.Name + ' is missing. Run deploy.bat before START/RESTART.') }
        $text = [IO.File]::ReadAllText($entry.Path)
        if ((Normalize-YamlString (Get-YamlScalarRawFromText $text @('proxies','velocity','enabled'))) -ne 'true' -or
            (Normalize-YamlString (Get-YamlScalarRawFromText $text @('proxies','velocity','online-mode'))) -ne 'false') {
            Fail ($entry.Name + ' does not enforce modern Velocity forwarding. Run deploy.bat; nothing was started.')
        }
        $actualSecret = Normalize-YamlString (Get-YamlScalarRawFromText $text @('proxies','velocity','secret'))
        if (-not [string]::Equals($actualSecret, $secret, [StringComparison]::Ordinal)) { Fail ($entry.Name + ' forwarding secret differs from persistent env. Values were not printed; nothing was started.') }
    }

    $velocityToml = Join-Path $script:Velocity 'velocity.toml'
    if (-not [IO.File]::Exists($velocityToml)) { Fail ('Velocity configuration is missing: ' + $velocityToml) }
    $expectedToml = @(
        [pscustomobject]@{ Section=''; Key='bind'; Value=((Get-EnvValue 'MINECRAFT_BIND' '0.0.0.0') + ':' + (Get-EnvValue 'MINECRAFT_PORT' '25565')) },
        [pscustomobject]@{ Section=''; Key='online-mode'; Value='false' },
        [pscustomobject]@{ Section=''; Key='force-key-authentication'; Value='false' },
        [pscustomobject]@{ Section=''; Key='player-info-forwarding-mode'; Value='modern' },
        [pscustomobject]@{ Section=''; Key='forwarding-secret-file'; Value='forwarding.secret' },
        [pscustomobject]@{ Section='servers'; Key='limbo-1'; Value='127.0.0.1:25567' },
        [pscustomobject]@{ Section='servers'; Key='hub-1'; Value='127.0.0.1:25566' }
    )
    foreach ($entry in $expectedToml) {
        $actual = Normalize-TomlScalar (Get-TomlScalarRaw $velocityToml $entry.Section $entry.Key)
        if (-not [string]::Equals($actual, $entry.Value, [StringComparison]::OrdinalIgnoreCase)) {
            Fail ('Velocity ' + $(if ($entry.Section) { $entry.Section + '.' } else { '' }) + $entry.Key + ' differs from the managed safe value. Run deploy.bat; nothing was started.')
        }
    }
    $tryRaw = Get-TomlScalarRaw $velocityToml 'servers' 'try'
    if (($tryRaw -replace '\s','') -ne '["limbo-1"]') { Fail 'Velocity servers.try must route first connections to limbo-1. Run deploy.bat; nothing was started.' }
    $secretPath = Join-Path $script:Velocity 'forwarding.secret'
    if (-not [IO.File]::Exists($secretPath) -or -not [string]::Equals([IO.File]::ReadAllText($secretPath).Trim(), $secret, [StringComparison]::Ordinal)) {
        Fail 'Velocity forwarding.secret differs from persistent env. Values were not printed; nothing was started.'
    }
}

function Copy-ConfigDefaults([string] $PaperJar, [string] $Destination) {
    Ensure-Directory $Destination
    $zip = [IO.Compression.ZipFile]::OpenRead($PaperJar)
    $copied = $false
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notmatch '^config/(?<name>[^/]+\.yml)$') { continue }
            $target = Join-Path $Destination $Matches.name
            if ([IO.File]::Exists($target)) { continue }
            Backup-File $target
            $reader = New-Object IO.StreamReader($entry.Open(), [Text.Encoding]::UTF8, $true)
            try { Write-AtomicText $target $reader.ReadToEnd() $script:Utf8NoBom } finally { $reader.Dispose() }
            $copied = $true
        }
    } finally { $zip.Dispose() }
    return $copied
}

function Copy-LegacyWebConfig {
    $source = Join-Path $script:Release 'data\config'
    if (-not [IO.Directory]::Exists($source)) { return $false }
    $copied = $false
    foreach ($file in Get-ChildItem -LiteralPath $source -File -Recurse) {
        $relative = $file.FullName.Substring($source.Length).TrimStart('\','/')
        $target = Join-Path $script:WebConfig $relative
        if (-not [IO.File]::Exists($target)) {
            Ensure-Directory ([IO.Path]::GetDirectoryName($target))
            Backup-File $target
            [IO.File]::Copy($file.FullName, $target, $false)
            $copied = $true
        }
    }
    return $copied
}

function Read-State {
    if (-not [IO.File]::Exists($script:StateFile)) { return $null }
    try { return ([IO.File]::ReadAllText($script:StateFile) | ConvertFrom-Json) }
    catch { Fail ('Invalid state file: ' + $script:StateFile + '. Restore it from _kofauth\backups.') }
}

function Normalize-YamlString([string] $Raw) {
    if ($null -eq $Raw) { return $null }
    $value = $Raw.Trim()
    if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
        return $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Assert-ExistingForwardingSecrets([bool] $HadInstalledPlugin) {
    # During a nonce/fingerprint-verified bootstrap resume, Paper/Velocity may
    # have regenerated placeholder forwarding secrets after rollback. They are
    # not operator state and must be replaced by the persistent marked secret.
    if (-not $HadInstalledPlugin -or (Test-BootstrapIdentityMarker)) { return }
    $expected = Get-EnvValue 'FORWARDING_SECRET'
    $checks = @(
        [pscustomobject]@{ Name = 'backend paper-global.yml'; Path = (Join-Path $script:Root 'config\paper-global.yml'); Type = 'yaml' },
        [pscustomobject]@{ Name = 'limbo paper-global.yml'; Path = (Join-Path $script:Limbo 'config\paper-global.yml'); Type = 'yaml' },
        [pscustomobject]@{ Name = 'Velocity forwarding.secret'; Path = (Join-Path $script:Velocity 'forwarding.secret'); Type = 'text' }
    )
    foreach ($check in $checks) {
        if (-not [IO.File]::Exists($check.Path)) { continue }
        if ($check.Type -eq 'yaml') { $raw = Get-YamlScalarRawFromText ([IO.File]::ReadAllText($check.Path)) @('proxies','velocity','secret'); $value = Normalize-YamlString $raw }
        else { $value = [IO.File]::ReadAllText($check.Path).Trim() }
        if ($value -and $value -ne $expected) {
            Fail ($check.Name + ' contains a forwarding secret different from persistent env. Values were not printed or changed. Restore the correct env/config copy and rerun.')
        }
    }
}

function Apply-Configuration([string] $PaperArtifact, [bool] $HadInstalledPlugin) {
    $backendConfig = Join-Path $script:Root 'plugins\KoFAuth'
    $limboConfig = Join-Path $script:Limbo 'plugins\KoFAuth'
    $velocityConfig = Join-Path $script:Velocity 'plugins\kofauth'
    $configDestinations = @(
        [pscustomobject]@{ Name='backend'; Path=$backendConfig },
        [pscustomobject]@{ Name='limbo'; Path=$limboConfig },
        [pscustomobject]@{ Name='velocity'; Path=$velocityConfig },
        [pscustomobject]@{ Name='webapi'; Path=$script:WebConfig }
    )
    foreach ($destination in $configDestinations) {
        if (Copy-ConfigDefaults $PaperArtifact $destination.Path) { [void]$script:ConfigChanged.Add($destination.Name) }
    }
    if (Copy-LegacyWebConfig) { [void]$script:ConfigChanged.Add('webapi') }

    Assert-ExistingForwardingSecrets $HadInstalledPlugin
    $secret = Get-EnvValue 'FORWARDING_SECRET'
    foreach ($entry in @(
        [pscustomobject]@{ Name='backend'; Path=(Join-Path $script:Root 'config\paper-global.yml') },
        [pscustomobject]@{ Name='limbo'; Path=(Join-Path $script:Limbo 'config\paper-global.yml') }
    )) {
        $changed = $false
        if ((Set-YamlScalar $entry.Path @('proxies','velocity','enabled') 'true' '' @() -Force) -eq 'changed') { $changed = $true }
        if ((Set-YamlScalar $entry.Path @('proxies','velocity','online-mode') 'false' '' @() -Force) -eq 'changed') { $changed = $true }
        if ((Set-YamlScalar $entry.Path @('proxies','velocity','secret') ("'" + $secret + "'") '' @() -Force) -eq 'changed') { $changed = $true }
        if ($changed) { [void]$script:ConfigChanged.Add($entry.Name) }
    }

    $backendProperties = [ordered]@{
        'server-ip'='127.0.0.1'; 'server-port'='25566'; 'online-mode'='false'; 'enforce-secure-profile'='false'
    }
    if (Set-PropertiesValues (Join-Path $script:Root 'server.properties') $backendProperties) { [void]$script:ConfigChanged.Add('backend') }
    $limboProperties = [ordered]@{
        'server-ip'='127.0.0.1'; 'server-port'='25567'; 'online-mode'='false'; 'enforce-secure-profile'='false';
        'gamemode'='adventure'; 'difficulty'='peaceful'; 'allow-nether'='false'; 'spawn-monsters'='false';
        'max-players'=(Get-EnvValue 'LIMBO_MAX_PLAYERS' '50')
    }
    if (Set-PropertiesValues (Join-Path $script:Limbo 'server.properties') $limboProperties) { [void]$script:ConfigChanged.Add('limbo') }

    $velocityToml = Join-Path $script:Velocity 'velocity.toml'
    $bind = (Get-EnvValue 'MINECRAFT_BIND' '0.0.0.0') + ':' + (Get-EnvValue 'MINECRAFT_PORT' '25565')
    $tomlChanged = $false
    if (Set-TomlValue $velocityToml '' 'bind' ('"' + $bind + '"')) { $tomlChanged = $true }
    if (Set-TomlValue $velocityToml '' 'online-mode' 'false') { $tomlChanged = $true }
    if (Set-TomlValue $velocityToml '' 'force-key-authentication' 'false') { $tomlChanged = $true }
    if (Set-TomlValue $velocityToml '' 'player-info-forwarding-mode' '"modern"') { $tomlChanged = $true }
    if (Set-TomlValue $velocityToml '' 'forwarding-secret-file' '"forwarding.secret"') { $tomlChanged = $true }
    if (Set-TomlValue $velocityToml 'servers' 'limbo-1' '"127.0.0.1:25567"') { $tomlChanged = $true }
    if (Set-TomlValue $velocityToml 'servers' 'hub-1' '"127.0.0.1:25566"') { $tomlChanged = $true }
    if (Set-TomlValue $velocityToml 'servers' 'try' '["limbo-1"]') { $tomlChanged = $true }
    $secretPath = Join-Path $script:Velocity 'forwarding.secret'
    if (-not [IO.File]::Exists($secretPath) -or [IO.File]::ReadAllText($secretPath) -ne $secret) {
        Backup-File $secretPath
        Write-AtomicText $secretPath $secret $script:Utf8NoBom
        $tomlChanged = $true
    }
    if ($tomlChanged) { [void]$script:ConfigChanged.Add('velocity') }

    $defaultMessages = Read-ZipEntry $PaperArtifact 'config/messages.yml'
    $defaultPaper = Read-ZipEntry $PaperArtifact 'config/paper.yml'
    $legacyPrefix = '"<gradient:#FF2D2D:#FFD700><bold>KoF</bold></gradient><white><bold>Network</bold></white> <dark_gray>' + [char]0x00BB + '</dark_gray> "'
    $managed = @(
        [pscustomobject]@{ File='messages.yml'; Keys=@('prefix'); Key='messages.prefix'; Desired=(Get-YamlScalarRawFromText $defaultMessages @('prefix')); Legacy=@($legacyPrefix) },
        [pscustomobject]@{ File='messages.yml'; Keys=@('limbo-title'); Key='messages.limbo-title'; Desired=(Get-YamlScalarRawFromText $defaultMessages @('limbo-title')); Legacy=@('"<gradient:#FF2D2D:#FFD700:#FFFFFF><bold>KoFNetwork</bold></gradient>"') },
        [pscustomobject]@{ File='messages.yml'; Keys=@('welcome-title'); Key='messages.welcome-title'; Desired=(Get-YamlScalarRawFromText $defaultMessages @('welcome-title')); Legacy=@('"<gradient:#FF2D2D:#FFD700:#FFFFFF><bold>KoFNetwork</bold></gradient>"') }
    )
    foreach ($destination in $configDestinations) {
        foreach ($item in $managed) {
            $result = Set-YamlScalar (Join-Path $destination.Path $item.File) $item.Keys $item.Desired $item.Key $item.Legacy
            if ($result -eq 'changed') { [void]$script:ConfigChanged.Add($destination.Name) }
            if ($result -ne 'conflict') { $script:ManagedDesired[$item.Key] = $item.Desired }
        }
    }
    foreach ($destination in @(
        [pscustomobject]@{ Name='backend'; Path=$backendConfig },
        [pscustomobject]@{ Name='limbo'; Path=$limboConfig }
    )) {
        foreach ($item in @(
            [pscustomobject]@{ Keys=@('limbo','title','animation','enabled'); Key='paper.limbo.title.animation.enabled'; Desired=(Get-YamlScalarRawFromText $defaultPaper @('limbo','title','animation','enabled')) },
            [pscustomobject]@{ Keys=@('limbo','title','animation','interval'); Key='paper.limbo.title.animation.interval'; Desired=(Get-YamlScalarRawFromText $defaultPaper @('limbo','title','animation','interval')) }
        )) {
            $result = Set-YamlScalar (Join-Path $destination.Path 'paper.yml') $item.Keys $item.Desired $item.Key @()
            if ($result -eq 'changed') { [void]$script:ConfigChanged.Add($destination.Name) }
            if ($result -ne 'conflict') { $script:ManagedDesired[$item.Key] = $item.Desired }
        }
    }
}

function Write-ConflictReport {
    $path = Join-Path $script:Runtime 'config-conflicts.txt'
    if ($script:Conflicts.Count -eq 0) {
        $clearContent = "No current managed configuration conflicts.`n"
        if ([IO.File]::Exists($path) -and [IO.File]::ReadAllText($path) -ne $clearContent) { Backup-File $path; Write-AtomicText $path $clearContent $script:Utf8NoBom }
        return
    }
    $content = "KoFAuth preserved these user-customized values:`n`n" + ([string]::Join("`n", $script:Conflicts)) + "`n"
    if (-not [IO.File]::Exists($path) -or [IO.File]::ReadAllText($path) -ne $content) { Backup-File $path; Write-AtomicText $path $content $script:Utf8NoBom }
    Write-WarningLine ($script:Conflicts.Count.ToString() + ' customized managed value(s) were preserved. See ' + $path)
}

function Move-RemapCacheToBackup([string] $PluginsDirectory, [string] $PluginFileName) {
    $cache = Join-Path (Join-Path $PluginsDirectory '.paper-remapped') $PluginFileName
    if (-not [IO.File]::Exists($cache)) { return }
    $destination = Join-Path $script:TransactionBackup ((Get-RelativeSafePath $cache) + '.cache')
    Ensure-Directory ([IO.Path]::GetDirectoryName($destination))
    Backup-File $cache
    if (-not [IO.File]::Exists($destination)) { [IO.File]::Copy($cache, $destination, $false) }
    [IO.File]::Delete($cache)
    Write-Info ('Moved stale remap cache to backup: ' + $destination)
}

function Test-ServiceRunning([string] $Service) {
    $id = Get-ServiceContainerId $Service
    if (-not $id) { return $false }
    $running = (& docker inspect --format '{{.State.Running}}' $id 2>$null | Select-Object -First 1)
    return ([string]$running).Trim() -eq 'true'
}

function Start-DataServices([bool] $ForceRecreate = $false) {
    if ($ForceRecreate) {
        Invoke-Compose @('up','-d','--force-recreate','--no-deps','mysql','redis')
        Wait-ServiceHealthy 'mysql' 180
        Wait-ServiceHealthy 'redis' 90
        return
    }
    $missing = New-Object 'System.Collections.Generic.List[string]'
    foreach ($service in @('mysql','redis')) { if (-not (Test-ServiceRunning $service)) { $missing.Add($service) } }
    if ($missing.Count -gt 0) { Invoke-Compose (@('up','-d','--no-deps') + @($missing)) }
    else { Write-Info 'MySQL and Redis are already running; Compose was not reapplied.' }
    Wait-ServiceHealthy 'mysql' 180
    Wait-ServiceHealthy 'redis' 90
}

function Start-Infra([bool] $ForceWebApi, [bool] $ForceData = $false) {
    Start-DataServices $ForceData
    if ($ForceWebApi) { Invoke-Compose @('up','-d','--force-recreate','--no-deps','webapi') }
    elseif (-not (Test-ServiceRunning 'webapi')) { Invoke-Compose @('up','-d','--no-deps','webapi') }
    else { Write-Info 'WebAPI is already running; Compose was not reapplied.' }
    Wait-ServiceHealthy 'webapi' 240
}

function Stop-WebApiOnly {
    Stop-DockerServiceExact 'webapi'
}

function Stop-DockerServiceExact([string] $Service) {
    $id = Get-LabeledContainerId $script:ProjectName $Service
    if (-not $id) { Write-Info ($Service + ' container does not exist.'); return }
    $running = ([string](& docker inspect --format '{{.State.Running}}' $id 2>$null | Select-Object -First 1)).Trim()
    if ($running -ne 'true') { Write-Info ($Service + ' is already stopped.'); return }
    & docker stop --time 120 $id *> $null
    if ($LASTEXITCODE -ne 0) { Fail ('Could not stop exact managed ' + $Service + ' container ' + $id + '.') }
    Write-Ok ($Service + ' stopped without removing its container or volume.')
}

function Stop-Infra {
    if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue) -and -not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'Docker is unavailable; Java stop actions remain usable, but Docker services could not be stopped.' }
    if (-not $script:ProjectName) { Fail 'compose-project.txt is missing; exact label-owned Docker services cannot be identified safely.' }
    & docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) { Fail 'Docker engine is unavailable; Java components were not restarted.' }
    $errors = New-Object 'System.Collections.Generic.List[string]'
    foreach ($service in @('webapi','redis','mysql')) {
        try { Stop-DockerServiceExact $service }
        catch { $errors.Add($service + ': ' + $_.Exception.Message) }
    }
    if ($errors.Count -gt 0) { Fail ('One or more exact Docker services could not be stopped: ' + [string]::Join(' | ', $errors)) }
    Write-Ok 'WebAPI, Redis and MySQL stopped without removing containers or volumes.'
}

function Stop-JavaComponents {
    $errors = New-Object 'System.Collections.Generic.List[string]'
    foreach ($component in @('velocity','backend','limbo')) {
        try { Stop-Component $component }
        catch { $errors.Add($component + ': ' + $_.Exception.Message) }
    }
    if ($errors.Count -gt 0) { Fail ('One or more Java components could not be stopped: ' + [string]::Join(' | ', $errors)) }
}

function Stop-AllComponents {
    $errors = New-Object 'System.Collections.Generic.List[string]'
    try { Stop-JavaComponents } catch { $errors.Add($_.Exception.Message) }
    try { Stop-Infra } catch { $errors.Add($_.Exception.Message) }
    if ($errors.Count -gt 0) { Fail ('STOP-ALL completed with errors after attempting every managed component: ' + [string]::Join(' | ', $errors)) }
}

function Restore-FileFromBackup($Entry) {
    $path = [string]$Entry.Path
    if ([bool]$Entry.Existed) {
        $backup = [string]$Entry.Backup
        if (-not [IO.File]::Exists($backup)) { Fail ('Rollback backup is missing for ' + $path) }
        Ensure-Directory ([IO.Path]::GetDirectoryName($path))
        $temporary = $path + '.rollback-' + [Guid]::NewGuid().ToString('N')
        [IO.File]::Copy($backup, $temporary, $false)
        if ([IO.File]::Exists($path)) {
            $discard = $path + '.discard-' + [Guid]::NewGuid().ToString('N')
            [IO.File]::Replace($temporary, $path, $discard, $true)
            if ([IO.File]::Exists($discard)) { [IO.File]::Delete($discard) }
        } else { [IO.File]::Move($temporary, $path) }
    } elseif ([IO.File]::Exists($path)) {
        [IO.File]::Delete($path)
    }
}

function Invoke-DeploymentRollback {
    if (-not $script:DeploymentStarted) { return [bool]$true }
    $script:RollbackInProgress = $true
    Write-WarningLine 'Deployment did not complete; closing the proxy gate and restoring transaction backups.'
    $stopSucceeded = $true
    foreach ($component in @('velocity','backend','limbo')) {
        try { if (Test-ExactOwnedProcess $component) { Stop-Component $component } }
        catch { $stopSucceeded = $false; Write-WarningLine ('Rollback could not stop ' + $component + ': ' + $_.Exception.Message) }
    }
    # A supervisor may have died after starting Java. Such a listener is no
    # longer provably ours and must never be killed, but rollback also must not
    # restore files underneath it or leave a public Velocity gate open.
    foreach ($component in @('velocity','backend','limbo')) {
        $definition = Get-ComponentDefinition $component
        if (Test-Port $definition.ProbeHost $definition.Port) {
            $stopSucceeded = $false
            Write-WarningLine ('Rollback found a remaining listener at ' + $definition.ProbeHost + ':' + $definition.Port + ' for ' + $component + '. It was not killed; manual shutdown is required.')
        }
    }
    try { [void](Stop-WebApiOnly) }
    catch { $stopSucceeded = $false; Write-WarningLine ('Rollback could not stop WebAPI: ' + $_.Exception.Message) }
    if (-not $stopSucceeded) {
        $script:TransactionPhase = 'recovery-stop-failed'
        Write-TransactionJournal $script:TransactionPhase
        Write-WarningLine 'Rollback did not modify files because one or more managed processes could not be stopped safely.'
        return [bool]$false
    }

    $restoreSucceeded = $true
    for ($i = $script:RollbackEntries.Count - 1; $i -ge 0; $i--) {
        try { Restore-FileFromBackup $script:RollbackEntries[$i] }
        catch { $restoreSucceeded = $false; Write-WarningLine ('Rollback could not restore ' + [string]$script:RollbackEntries[$i].Path + ': ' + $_.Exception.Message) }
    }
    try { [void](Restore-LastKnownGoodInputs) }
    catch { $restoreSucceeded = $false; Write-WarningLine ('Rollback could not restore last-known-good inputs: ' + $_.Exception.Message) }
    if (-not $restoreSucceeded) {
        $script:TransactionPhase = 'recovery-restore-failed'
        Write-TransactionJournal $script:TransactionPhase
        Write-WarningLine 'Rollback backups require manual review; the auth network remains closed and the transaction journal was preserved.'
        return [bool]$false
    }

    if ($script:DataInfrastructureMayHaveChanged) {
        try { [void](Start-DataServices $true) }
        catch {
            $script:TransactionPhase = 'data-recovery-failed'
            Write-TransactionJournal $script:TransactionPhase
            Write-WarningLine ('Could not restore the last-known-good MySQL/Redis definition: ' + $_.Exception.Message)
            return [bool]$false
        }
    }

    if ($script:DatabaseMayHaveMigrated) {
        $script:TransactionPhase = 'database-recovery-required'
        Write-TransactionJournal $script:TransactionPhase
        Write-WarningLine ('Runtime files were restored, but WebAPI may have applied Flyway migrations. For safety the auth network remains closed. Review the SQL dump in ' + $script:TransactionBackup + ' and resolve pending-transaction.json before restarting.')
        return [bool]$false
    }
    try {
        $needData = [bool](Get-ObjectValue $script:InitialServices 'mysql' $false) -or [bool](Get-ObjectValue $script:InitialServices 'redis' $false) -or
                    [bool](Get-ObjectValue $script:InitialServices 'webapi' $false) -or
                    [bool](Get-ObjectValue $script:InitialRunning 'backend' $false) -or [bool](Get-ObjectValue $script:InitialRunning 'limbo' $false) -or [bool](Get-ObjectValue $script:InitialRunning 'velocity' $false)
        if ($needData) { [void](Start-DataServices) }
        if ([bool](Get-ObjectValue $script:InitialServices 'webapi' $false)) { [void](Start-Infra $true) }
        if ([bool](Get-ObjectValue $script:InitialRunning 'limbo' $false)) { [void](Start-Component 'limbo') }
        if ([bool](Get-ObjectValue $script:InitialRunning 'backend' $false)) { [void](Start-Component 'backend') }
        if ([bool](Get-ObjectValue $script:InitialRunning 'velocity' $false)) { [void](Start-Component 'velocity') }
        if (-not [bool](Get-ObjectValue $script:InitialServices 'webapi' $false) -and (Test-ServiceRunning 'webapi')) { [void](Stop-WebApiOnly) }
        if (-not [bool](Get-ObjectValue $script:InitialServices 'redis' $false) -and (Test-ServiceRunning 'redis')) { [void](Invoke-Compose @('stop','redis')) }
        if (-not [bool](Get-ObjectValue $script:InitialServices 'mysql' $false) -and (Test-ServiceRunning 'mysql')) { [void](Invoke-Compose @('stop','mysql')) }
        Write-Ok 'Previous managed runtime state was restored as far as safely possible.'
        if ([IO.File]::Exists($script:PendingTransactionFile)) { [IO.File]::Delete($script:PendingTransactionFile) }
        $script:DeploymentStarted = $false
        return [bool]$true
    } catch {
        $script:TransactionPhase = 'recovery-restart-failed'
        Write-TransactionJournal $script:TransactionPhase
        Write-WarningLine ('Files were restored, but the previous runtime could not be fully restarted: ' + $_.Exception.Message)
        return [bool]$false
    }
}

function Recover-PendingTransaction {
    if (-not [IO.File]::Exists($script:PendingTransactionFile)) { return $false }
    try { $journal = [IO.File]::ReadAllText($script:PendingTransactionFile) | ConvertFrom-Json }
    catch { Fail ('Unreadable pending transaction journal: ' + $script:PendingTransactionFile + '. Do not deploy until it is reviewed.') }
    if ([string](Get-ObjectValue $journal 'phase' '') -eq 'database-recovery-required') {
        Fail ('A previous deploy was interrupted after WebAPI migration may have started. The proxy remains gated. Review ' + [string](Get-ObjectValue $journal 'backupDirectory' $script:Backups) + ' and ' + $script:PendingTransactionFile + ' before any new deploy.')
    }
    $script:RollbackEntries.Clear()
    foreach ($entry in @($journal.entries)) {
        $script:RollbackEntries.Add([pscustomobject]@{ Path=[string]$entry.path; Existed=[bool]$entry.existed; Backup=[string]$entry.backup })
    }
    $script:InitialRunning = @{}
    foreach ($property in $journal.initialRunning.PSObject.Properties) { $script:InitialRunning[$property.Name] = [bool]$property.Value }
    $script:InitialServices = @{}
    foreach ($property in $journal.initialServices.PSObject.Properties) { $script:InitialServices[$property.Name] = [bool]$property.Value }
    $script:ProjectName = [string](Get-ObjectValue $journal 'projectName' '')
    $script:TransactionBackup = [string](Get-ObjectValue $journal 'backupDirectory' $script:TransactionBackup)
    $script:TransactionPhase = [string](Get-ObjectValue $journal 'phase' 'interrupted')
    $script:DatabaseMayHaveMigrated = [bool](Get-ObjectValue $journal 'databaseMayHaveMigrated' $false)
    $script:DataInfrastructureMayHaveChanged = [bool](Get-ObjectValue $journal 'dataInfrastructureMayHaveChanged' $false)
    $script:DeploymentStarted = $true
    $script:RollbackTracking = $false
    if ([IO.File]::Exists($script:EnvFile)) { $script:Env = Read-EnvMap $script:EnvFile }
    Write-WarningLine ('Recovering interrupted deployment ' + [string](Get-ObjectValue $journal 'transactionStamp' 'unknown') + ' before starting a new one.')
    if (-not (Invoke-DeploymentRollback)) {
        # The recovery pass already restored/gated everything it safely could.
        # Keep the durable journal but prevent the outer deploy catch from
        # running the same recovery (and data recreate) a second time.
        $script:DeploymentStarted = $false
        Fail ('Interrupted transaction recovery needs manual attention: ' + $script:PendingTransactionFile)
    }
    Write-Ok 'Interrupted deployment was recovered. Run deploy.bat again to start a new deployment.'
    return $true
}

function Wait-KoFAuthLog([string] $Component, [string] $Mode) {
    $definition = Get-ComponentDefinition $Component
    $pidState = Get-PidState $Component
    if (-not $pidState) { Fail ($Component + ' has no managed PID state for readiness verification.') }
    $checkpoint = [pscustomobject]@{
        Path = [string](Get-ObjectValue $pidState 'logPath' '')
        Length = [Int64](Get-ObjectValue $pidState 'logLength' 0L)
        CreationTicks = [Int64](Get-ObjectValue $pidState 'logCreationTicks' 0L)
        PrefixHash = [string](Get-ObjectValue $pidState 'logPrefixHash' '')
        PrefixLength = [int](Get-ObjectValue $pidState 'logPrefixLength' 0)
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        $fresh = Read-LogSinceCheckpoint $definition.Work $checkpoint
        $started = $fresh -match 'KoFAuth.*\u0437\u0430\u043f\u0443\u0449\u0435\u043d'
        $modeOk = -not $Mode -or $fresh -match ([regex]::Escape($Mode))
        if ($started -and $modeOk) {
            if (-not (Test-ExactOwnedProcess $Component ([string](Get-ObjectValue $pidState 'nonce' ''))) -or -not (Test-ManagedProcess $Component)) {
                Fail ($Component + ' logged readiness, but its managed supervisor generation is no longer alive.')
            }
            $readyPath = Join-Path $script:PidDir ($Component + '.ready.json')
            $ready = [ordered]@{ component=$Component; nonce=[string](Get-ObjectValue $pidState 'nonce' 'legacy'); pid=[int]$pidState.pid; confirmedAt=[DateTime]::UtcNow.ToString('o') }
            Write-AtomicText $readyPath (($ready | ConvertTo-Json -Depth 3) + "`n") $script:Utf8NoBom
            Write-Ok ($Component + ' KoFAuth startup confirmed in this process generation.')
            return
        }
        $exitPath = Join-Path $script:PidDir ($Component + '.exit.json')
        if ([IO.File]::Exists($exitPath)) {
            try { $exitState = [IO.File]::ReadAllText($exitPath) | ConvertFrom-Json } catch { $exitState = $null }
            if ($exitState -and [string](Get-ObjectValue $exitState 'nonce' '') -eq [string](Get-ObjectValue $pidState 'nonce' '')) { Fail ($Component + ' stopped before KoFAuth startup was confirmed.') }
        }
        Start-Sleep -Seconds 2
    }
    Fail ($Component + ' is listening, but KoFAuth startup was not confirmed in logs within 120 seconds.')
}

function Write-StateFile([pscustomobject] $ServerMetadata, [string] $PaperHash, [string] $VelocityHash, [string] $WebHash) {
    $installedAt = [DateTime]::UtcNow.ToString('o')
    if ($script:State -and $script:State.PSObject.Properties.Name -contains 'installedAt') { $installedAt = [string]$script:State.installedAt }
    $mysqlVolume = Get-ProjectVolumeName 'mysql-data'
    if (-not $mysqlVolume) { Fail 'Refusing to commit deployment state without an exact labeled mysql-data volume.' }
    $state = [ordered]@{
        schemaVersion = 2
        installedAt = $installedAt
        updatedAt = [DateTime]::UtcNow.ToString('o')
        minecraftVersion = $ServerMetadata.Minecraft
        javaRequired = $ServerMetadata.Java
        composeProject = $script:ProjectName
        composeDefinitionHash = (Get-FileHash256 $script:ComposeFile)
        runtimeEnvironmentFingerprint = (Get-RuntimeEnvironmentFingerprint)
        identityEnvironmentFingerprint = (Get-IdentityEnvironmentFingerprint)
        dataInfrastructureFingerprint = (Get-DataInfrastructureFingerprint)
        mysqlVolume = $mysqlVolume
        velocityDirectory = (Get-RelativeSafePath $script:Velocity)
        artifacts = [ordered]@{ paper = $PaperHash; velocity = $VelocityHash; webapi = $WebHash }
        managedConfig = $script:ManagedDesired
    }
    Backup-File $script:StateFile
    Write-AtomicText $script:StateFile (($state | ConvertTo-Json -Depth 8) + "`n") $script:Utf8NoBom
}

function Write-Helper([string] $Path, [string] $Command) {
    if ([IO.Path]::GetDirectoryName($Path) -eq $script:Root) { $target = '%~dp0deploy.bat' }
    else { $target = '%~dp0..\..\deploy.bat' }
    $marker = 'rem Generated by KoFAuth deploy.bat - safe to regenerate'
    $content = "@echo off`r`n" + $marker + "`r`ncall `"" + $target + "`" " + $Command + "`r`nexit /b %ERRORLEVEL%`r`n"
    if ([IO.File]::Exists($Path)) {
        $current = [IO.File]::ReadAllText($Path)
        if ($current -eq $content) { return }
        if ($current -notmatch [regex]::Escape($marker)) {
            Write-WarningLine ('Preserved user-owned BAT instead of overwriting it: ' + $Path)
            return
        }
        Backup-File $Path
    }
    Write-AtomicText $Path $content $script:Ascii
}

function Ensure-Helpers {
    Ensure-Directory $script:Commands
    foreach ($entry in @(
        [pscustomobject]@{ Path=(Join-Path $script:Root 'KOFAUTH-START-ALL.bat'); Action='start-all' },
        [pscustomobject]@{ Path=(Join-Path $script:Root 'KOFAUTH-STOP-ALL.bat'); Action='stop-all' },
        [pscustomobject]@{ Path=(Join-Path $script:Root 'KOFAUTH-RESTART-ALL.bat'); Action='restart-all' },
        [pscustomobject]@{ Path=(Join-Path $script:Root 'KOFAUTH-STATUS.bat'); Action='status' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'START-INFRA.bat'); Action='start-infra' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'STOP-INFRA.bat'); Action='stop-infra' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'RESTART-INFRA.bat'); Action='restart-infra' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'START-BACKEND.bat'); Action='start-backend' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'STOP-BACKEND.bat'); Action='stop-backend' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'RESTART-BACKEND.bat'); Action='restart-backend' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'COMMAND-BACKEND.bat'); Action='command-backend' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'START-LIMBO.bat'); Action='start-limbo' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'STOP-LIMBO.bat'); Action='stop-limbo' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'RESTART-LIMBO.bat'); Action='restart-limbo' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'COMMAND-LIMBO.bat'); Action='command-limbo' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'START-VELOCITY.bat'); Action='start-velocity' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'STOP-VELOCITY.bat'); Action='stop-velocity' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'RESTART-VELOCITY.bat'); Action='restart-velocity' },
        [pscustomobject]@{ Path=(Join-Path $script:Commands 'COMMAND-VELOCITY.bat'); Action='command-velocity' }
    )) { Write-Helper $entry.Path $entry.Action }
}

function Resolve-KoFAuthPluginPath([string] $PluginsDirectory, [ValidateSet('paper','velocity')] [string] $Kind, [string] $PreferredName) {
    Ensure-Directory $PluginsDirectory
    $matches = New-Object 'System.Collections.Generic.List[string]'
    foreach ($jar in Get-ChildItem -LiteralPath $PluginsDirectory -Filter '*.jar' -File -ErrorAction SilentlyContinue) {
        try {
            Validate-ReleaseJar $jar.FullName $Kind
            $matches.Add($jar.FullName)
        } catch {}
    }
    if ($matches.Count -gt 1) {
        Fail ('Multiple KoFAuth ' + $Kind + ' plugin JARs exist in ' + $PluginsDirectory + ': ' + ([string]::Join(', ', $matches)) + '. Keep exactly one to avoid duplicate plugin loading.')
    }
    if ($matches.Count -eq 1) {
        $preferred = Join-Path $PluginsDirectory $PreferredName
        if (-not [string]::Equals($matches[0], $preferred, [StringComparison]::OrdinalIgnoreCase)) {
            Write-Info ('Adopted existing versioned KoFAuth plugin path: ' + $matches[0])
        }
        return $matches[0]
    }
    return (Join-Path $PluginsDirectory $PreferredName)
}

function Validate-ReleaseInputs {
    if (-not [IO.File]::Exists($script:ReleaseComposeFile)) { Fail ('Missing ' + $script:ReleaseComposeFile) }
    $paths = [ordered]@{
        paper = (Join-Path $script:Release 'artifacts\kofauth-paper.jar')
        velocity = (Join-Path $script:Release 'artifacts\kofauth-velocity.jar')
        webapi = (Join-Path $script:Release 'artifacts\kofauth-webapi.jar')
    }
    foreach ($entry in $paths.GetEnumerator()) {
        Validate-ReleaseJar $entry.Value $entry.Key
        $hash = Get-FileHash256 $entry.Value
        $item = Get-Item -LiteralPath $entry.Value
        $size = [Math]::Round($item.Length / 1MB, 1)
        Write-Info ('Incoming ' + $entry.Key + ': ' + $size + ' MiB, built ' + $item.LastWriteTimeUtc.ToString('yyyy-MM-dd HH:mm:ss') + 'Z, SHA-256 ' + $hash)
    }
    return $paths
}

function Find-BuiltModuleArtifact([string] $Module, [ValidateSet('paper','velocity','webapi')] [string] $Kind) {
    $target = Join-Path $script:Root ($Module + '\target')
    if (-not [IO.Directory]::Exists($target)) { Fail ('Module target directory is missing: ' + $target + '. Build the Maven project first.') }
    $candidates = @(Get-ChildItem -LiteralPath $target -Filter '*.jar' -File | Where-Object {
        $_.Name -notmatch '^original-' -and $_.Name -notmatch '-(?:sources|javadoc|tests)\.jar$'
    } | Sort-Object -Property @{Expression='LastWriteTimeUtc';Descending=$true}, @{Expression='Length';Descending=$true})
    foreach ($candidate in $candidates) {
        try { Validate-ReleaseJar $candidate.FullName $Kind; return $candidate.FullName } catch {}
    }
    Fail ('No deployable ' + $Kind + ' JAR was found in ' + $target + '. Run mvnw.cmd clean package first.')
}

function Publish-ReleaseArtifact([string] $Source, [string] $Destination, [string] $Kind) {
    Validate-ReleaseJar $Source $Kind
    Ensure-Directory ([IO.Path]::GetDirectoryName($Destination))
    $sourceHash = Get-FileHash256 $Source
    if ((Get-FileHash256 $Destination) -eq $sourceHash) {
        Write-Info ($Kind + ' release artifact already current: ' + $sourceHash)
        return
    }
    if ([IO.File]::Exists($Destination)) {
        $packageBackup = Join-Path $script:Release ('backups\package-' + $script:TransactionStamp)
        Ensure-Directory $packageBackup
        [IO.File]::Copy($Destination, (Join-Path $packageBackup ([IO.Path]::GetFileName($Destination))), $false)
    }
    $temporary = $Destination + '.new-' + [Guid]::NewGuid().ToString('N')
    try {
        [IO.File]::Copy($Source, $temporary, $false)
        if ((Get-FileHash256 $temporary) -ne $sourceHash) { Fail ($kind + ' release staging failed SHA-256 verification.') }
        if ([IO.File]::Exists($Destination)) {
            $replaceBackup = $Destination + '.replace-' + [Guid]::NewGuid().ToString('N')
            [IO.File]::Replace($temporary, $Destination, $replaceBackup, $true)
            if ([IO.File]::Exists($replaceBackup)) { [IO.File]::Delete($replaceBackup) }
        } else { [IO.File]::Move($temporary, $Destination) }
    } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
    Validate-ReleaseJar $Destination $Kind
    if ((Get-FileHash256 $Destination) -ne $sourceHash) { Fail ($Kind + ' release promotion failed SHA-256 verification.') }
    Write-Ok ($Kind + ' release artifact published: ' + $sourceHash)
}

function Invoke-PackageRelease {
    Write-Host 'KoFAuth release packaging' -ForegroundColor Magenta
    $sources = [ordered]@{
        paper = Find-BuiltModuleArtifact 'KoFAuth-Paper' 'paper'
        velocity = Find-BuiltModuleArtifact 'KoFAuth-Velocity' 'velocity'
        webapi = Find-BuiltModuleArtifact 'KoFAuth-WebAPI' 'webapi'
    }
    foreach ($entry in $sources.GetEnumerator()) { Write-Info ('Selected ' + $entry.Key + ': ' + $entry.Value) }
    Publish-ReleaseArtifact $sources.paper (Join-Path $script:Release 'artifacts\kofauth-paper.jar') 'paper'
    Publish-ReleaseArtifact $sources.velocity (Join-Path $script:Release 'artifacts\kofauth-velocity.jar') 'velocity'
    Publish-ReleaseArtifact $sources.webapi (Join-Path $script:Release 'artifacts\kofauth-webapi.jar') 'webapi'
    [void](Validate-ReleaseInputs)
    Write-Ok 'Release package is ready. Copy deploy.bat and the deploy folder together to the server folder.'
}

function Invoke-DryRun {
    Write-Host 'KoFAuth Windows deploy dry-run (zero mutations)' -ForegroundColor Magenta
    $server = Get-ServerMetadata (Join-Path $script:Root 'server.jar')
    $java = Get-JavaPathAndVersion $server.Java
    $paths = Validate-ReleaseInputs
    Test-DockerReady
    Write-Ok ('Paper/Purpur ' + $server.Minecraft + '; Java ' + $java.Major + '.')
    foreach ($entry in @(
        [pscustomobject]@{ Name='paper/backend'; Source=$paths.paper; Target=(Join-Path $script:Root 'plugins\KoFAuth-Paper.jar') },
        [pscustomobject]@{ Name='paper/limbo'; Source=$paths.paper; Target=(Join-Path $script:Limbo 'plugins\KoFAuth-Paper.jar') },
        [pscustomobject]@{ Name='velocity'; Source=$paths.velocity; Target=(Join-Path $script:Velocity 'plugins\KoFAuth-Velocity.jar') },
        [pscustomobject]@{ Name='webapi'; Source=$paths.webapi; Target=(Join-Path $script:Artifacts 'kofauth-webapi.jar') }
    )) {
        $change = (Get-FileHash256 $entry.Source) -ne (Get-FileHash256 $entry.Target)
        Write-Host ('  ' + $entry.Name.PadRight(18) + $(if ($change) { 'UPDATE required' } else { 'unchanged' }))
    }
    if (-not [IO.File]::Exists($script:EnvFile)) { Write-Host '  persistent env     CREATE (or import deploy\.env)' }
    if (-not [IO.File]::Exists((Join-Path $script:Velocity 'velocity.jar'))) { Write-Host '  Velocity runtime   DOWNLOAD pinned stable 3.4.0' }
    Write-Ok 'Dry-run complete; no files, processes, containers or volumes were changed.'
}

function Show-Help {
    Write-Host 'KoFAuth Windows deployer' -ForegroundColor Magenta
    Write-Host '  deploy.bat                  bootstrap or deploy only changed builds'
    Write-Host '  deploy.bat package          publish Maven target JARs into deploy\artifacts'
    Write-Host '  deploy.bat --dry-run        validate and show the plan without mutations'
    Write-Host '  deploy.bat status           show Java and Docker component state'
    Write-Host '  deploy.bat start-all        start infra, Limbo, backend, then Velocity'
    Write-Host '  deploy.bat stop-all         graceful reverse-order shutdown'
    Write-Host '  deploy.bat restart-all      graceful full restart'
    Write-Host '  deploy.bat self-test        test safe config editors/helper generation'
    Write-Host 'Generated component-specific BATs are stored in _kofauth\commands.'
}

function Invoke-Status {
    Assert-VelocityLayout
    if ([IO.File]::Exists($script:EnvFile)) { $script:Env = Read-EnvMap $script:EnvFile }
    Write-Host ('KoFAuth status for ' + $script:Root) -ForegroundColor Magenta
    foreach ($component in @('velocity','backend','limbo')) {
        $definition = Get-ComponentDefinition $component
        $managed = Test-ManagedProcess $component
        $port = Test-Port $definition.ProbeHost $definition.Port
        $status = if ($managed -and $port) { 'RUNNING (managed)' } elseif ($port) { 'PORT OCCUPIED (unmanaged)' } elseif ($managed) { 'STARTING/FAILED' } else { 'STOPPED' }
        Write-Host ('  ' + $component.PadRight(10) + $status + '  port ' + $definition.Port)
    }
    if ([IO.File]::Exists($script:ProjectFile) -and [IO.File]::Exists($script:EnvFile) -and [IO.File]::Exists($script:OverrideFile) -and [IO.File]::Exists($script:ComposeFile) -and (Get-Command docker -ErrorAction SilentlyContinue)) {
        $script:ProjectName = [IO.File]::ReadAllText($script:ProjectFile).Trim()
        foreach ($service in @('mysql','redis','webapi')) {
            $id = Get-ServiceContainerId $service
            $status = 'STOPPED'
            if ($id) { $status = ([string](& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $id 2>$null | Select-Object -First 1)).Trim() }
            Write-Host ('  ' + $service.PadRight(10) + $status)
        }
    } else { Write-Host '  Docker runtime has not been initialized.' }
}

function Ensure-ControlPrerequisites([switch] $Docker, [switch] $MayStart, [switch] $ValidateJava) {
    Assert-VelocityLayout
    if ([IO.File]::Exists($script:EnvFile)) { $script:Env = Read-EnvMap $script:EnvFile }
    elseif ($MayStart) { Fail 'Run deploy.bat once before using START/RESTART commands.' }
    else { $script:Env = @{} }
    if ([IO.File]::Exists($script:ProjectFile)) { $script:ProjectName = [IO.File]::ReadAllText($script:ProjectFile).Trim() }
    if ($Docker -and $MayStart) {
        Test-DockerReady
        if (-not $script:ProjectName) { Fail 'compose-project.txt is missing.' }
    }
    if (-not $MayStart) { return }

    if ([IO.File]::Exists($script:PendingTransactionFile)) {
        Fail 'An unfinished deployment transaction exists. START/RESTART is blocked; run deploy.bat to recover it. STOP and STATUS remain available.'
    }
    Validate-EnvironmentValues
    $script:State = Read-State
    if (-not $script:State) { Fail 'No committed deployment state exists. Run deploy.bat before START/RESTART.' }
    if ([string](Get-ObjectValue $script:State 'composeProject' '') -ne $script:ProjectName) { Fail 'Committed state and compose-project.txt disagree. Run deploy.bat; nothing was started.' }
    foreach ($fingerprint in @(
        [pscustomobject]@{ State='identityEnvironmentFingerprint'; Current=(Get-IdentityEnvironmentFingerprint); Name='identity secrets' },
        [pscustomobject]@{ State='dataInfrastructureFingerprint'; Current=(Get-DataInfrastructureFingerprint); Name='MySQL/Redis settings' },
        [pscustomobject]@{ State='runtimeEnvironmentFingerprint'; Current=(Get-RuntimeEnvironmentFingerprint); Name='runtime environment' }
    )) {
        $stored = [string](Get-ObjectValue $script:State $fingerprint.State '')
        if (-not $stored -or $stored -ne $fingerprint.Current) {
            Fail ('Committed ' + $fingerprint.Name + ' fingerprint is missing or changed. START/RESTART will not apply unreviewed values; run deploy.bat.')
        }
    }
    if (-not [IO.File]::Exists($script:ComposeFile)) { Fail 'Pinned compose.base.yml is missing; run deploy.bat.' }
    $storedCompose = [string](Get-ObjectValue $script:State 'composeDefinitionHash' '')
    if (-not $storedCompose -or $storedCompose -ne (Get-FileHash256 $script:ComposeFile)) { Fail 'Pinned Docker definition differs from committed state. Run deploy.bat before starting services.' }
    $expectedVolume = [string](Get-ObjectValue $script:State 'mysqlVolume' '')
    Assert-MySqlVolumeIdentity $expectedVolume
    $projectContainers = @(& docker ps -aq --filter ('label=com.docker.compose.project=' + $script:ProjectName) 2>$null)
    if ($projectContainers.Count -gt 0) { [void](Test-ProjectMatchesSecrets $script:ProjectName) }

    $stateArtifacts = Get-ObjectValue $script:State 'artifacts'
    foreach ($artifact in @(
        [pscustomobject]@{ State='paper'; Path=(Join-Path $script:Artifacts 'kofauth-paper.jar'); Name='Paper' },
        [pscustomobject]@{ State='velocity'; Path=(Join-Path $script:Artifacts 'kofauth-velocity.jar'); Name='Velocity' },
        [pscustomobject]@{ State='webapi'; Path=(Join-Path $script:Artifacts 'kofauth-webapi.jar'); Name='WebAPI' }
    )) {
        $storedHash = [string](Get-ObjectValue $stateArtifacts $artifact.State '')
        if (-not $storedHash -or $storedHash -ne (Get-FileHash256 $artifact.Path)) { Fail ($artifact.Name + ' managed artifact differs from committed state. Run deploy.bat before starting it.') }
    }
    $paperHash = [string](Get-ObjectValue $stateArtifacts 'paper' '')
    $velocityHash = [string](Get-ObjectValue $stateArtifacts 'velocity' '')
    foreach ($plugin in @(
        [pscustomobject]@{ Path=(Resolve-KoFAuthPluginPath (Join-Path $script:Root 'plugins') 'paper' 'KoFAuth-Paper.jar'); Hash=$paperHash; Name='backend Paper plugin' },
        [pscustomobject]@{ Path=(Resolve-KoFAuthPluginPath (Join-Path $script:Limbo 'plugins') 'paper' 'KoFAuth-Paper.jar'); Hash=$paperHash; Name='Limbo Paper plugin' },
        [pscustomobject]@{ Path=(Resolve-KoFAuthPluginPath (Join-Path $script:Velocity 'plugins') 'velocity' 'KoFAuth-Velocity.jar'); Hash=$velocityHash; Name='Velocity plugin' }
    )) {
        if ((Get-FileHash256 $plugin.Path) -ne $plugin.Hash) { Fail ($plugin.Name + ' differs from committed state. Run deploy.bat before starting it.') }
    }

    if ($ValidateJava) { Assert-ControlRuntimeConfiguration }

    if ($Docker) {
        Ensure-ComposeOverride
        [void](Invoke-Compose @('config','-q') -Quiet)
    }
}

function Invoke-ControlAction([string] $Normalized) {
    $needsDocker = $Normalized -in @(
        'start-all','stop-all','restart-all','start-infra','stop-infra','restart-infra',
        'start-backend','restart-backend','start-limbo','restart-limbo','start-velocity','restart-velocity'
    )
    Acquire-DeployLock
    $mayStart = $Normalized -match '^(?:start|restart)-'
    $startsJava = $Normalized -in @('start-all','restart-all','start-backend','restart-backend','start-limbo','restart-limbo','start-velocity','restart-velocity')
    Ensure-ControlPrerequisites -Docker:$needsDocker -MayStart:$mayStart -ValidateJava:$startsJava
    # Normal helper BATs keep separate consoles so an operator can use each
    # console. Automation/tests may opt into fully hidden windows explicitly.
    $script:InteractiveStart = $env:KOFAUTH_HIDDEN_START -ne '1'
    switch ($Normalized) {
        'start-all' { Start-Infra $false; [void](Start-Component 'limbo'); [void](Start-Component 'backend'); [void](Start-Component 'velocity') }
        'stop-all' { Stop-AllComponents }
        'restart-all' { Stop-AllComponents; Start-Infra $false; [void](Start-Component 'limbo'); [void](Start-Component 'backend'); [void](Start-Component 'velocity') }
        'start-infra' { Start-Infra $false }
        'stop-infra' { Stop-Infra }
        'restart-infra' { Stop-Infra; Start-Infra $false }
        'start-backend' { Start-Infra $false; [void](Start-Component 'backend') }
        'stop-backend' { Stop-Component 'backend' }
        'restart-backend' { Stop-Component 'backend'; Start-Infra $false; [void](Start-Component 'backend') }
        'start-limbo' { Start-Infra $false; [void](Start-Component 'limbo') }
        'stop-limbo' { Stop-Component 'limbo' }
        'restart-limbo' { Stop-Component 'limbo'; Start-Infra $false; [void](Start-Component 'limbo') }
        'start-velocity' { Start-Infra $false; [void](Start-Component 'velocity') }
        'stop-velocity' { Stop-Component 'velocity' }
        'restart-velocity' { Stop-Component 'velocity'; Start-Infra $false; [void](Start-Component 'velocity') }
        'command-backend' { if (-not (Test-ManagedProcess 'backend')) { Fail 'Backend is stopped; no command was queued.' }; $command = Read-Host 'Backend console command'; if ($command) { Write-ControlCommand 'backend' $command } }
        'command-limbo' { if (-not (Test-ManagedProcess 'limbo')) { Fail 'Limbo is stopped; no command was queued.' }; $command = Read-Host 'Limbo console command'; if ($command) { Write-ControlCommand 'limbo' $command } }
        'command-velocity' { if (-not (Test-ManagedProcess 'velocity')) { Fail 'Velocity is stopped; no command was queued.' }; $command = Read-Host 'Velocity console command'; if ($command) { Write-ControlCommand 'velocity' $command } }
        default { Fail ('Unsupported control action: ' + $Normalized) }
    }
}

function Invoke-SelfTest {
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('kofauth-selftest-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($temp) | Out-Null
    $oldRoot = $script:Root; $oldBackup = $script:TransactionBackup; $oldState = $script:State
    try {
        $script:Root = $temp
        $script:TransactionBackup = Join-Path $temp 'backup'
        $script:State = $null
        $properties = Join-Path $temp 'server.properties'
        [IO.File]::WriteAllText($properties, "motd=custom`nserver-port=25565`n", $script:Utf8NoBom)
        $changed = Set-PropertiesValues $properties ([ordered]@{'server-port'='25566';'online-mode'='false'})
        if (-not $changed -or [IO.File]::ReadAllText($properties) -notmatch 'motd=custom') { Fail 'Self-test: properties preservation failed.' }
        if (Set-PropertiesValues $properties ([ordered]@{'server-port'='25566';'online-mode'='false'})) { Fail 'Self-test: properties update is not idempotent.' }
        $yaml = Join-Path $temp 'messages.yml'
        [IO.File]::WriteAllText($yaml, "prefix: `"custom`"`nunrelated:`n  keep: true`n", $script:Utf8NoBom)
        $result = Set-YamlScalar $yaml @('prefix') '"desired"' 'messages.prefix' @('"legacy"')
        if ($result -ne 'conflict' -or [IO.File]::ReadAllText($yaml) -notmatch 'prefix: "custom"') { Fail 'Self-test: YAML custom-value protection failed.' }
        $result = Set-YamlScalar $yaml @('limbo','title','animation','enabled') 'true' 'paper.animation' @()
        if ($result -ne 'changed' -or [IO.File]::ReadAllText($yaml) -notmatch 'unrelated:') { Fail 'Self-test: YAML insertion/preservation failed.' }
        if ((Set-YamlScalar $yaml @('limbo','title','animation','enabled') 'true' 'paper.animation' @()) -ne 'same') { Fail 'Self-test: YAML update is not idempotent.' }
        $toml = Join-Path $temp 'velocity.toml'
        [IO.File]::WriteAllText($toml, "[servers]`ntry = [`n  `"lobby`", # ] in a comment`n  # [ in a comment`n]`n", $script:Utf8NoBom)
        if (-not (Set-TomlValue $toml '' 'online-mode' 'false')) { Fail 'Self-test: TOML root insertion failed.' }
        if (-not (Set-TomlValue $toml 'servers' 'try' '["limbo-1"]')) { Fail 'Self-test: TOML multiline array replacement failed.' }
        $tomlText = [IO.File]::ReadAllText($toml)
        if ($tomlText.IndexOf('online-mode') -gt $tomlText.IndexOf('[servers]')) { Fail 'Self-test: TOML root key was inserted inside a section.' }
        if ($tomlText -match '(?m)^\s*"lobby"\s*$' -or $tomlText -match '(?m)^\s*\]\s*$') { Fail 'Self-test: TOML multiline array tail was left behind.' }
        $helper = Join-Path $temp 'KOFAUTH-START-ALL.bat'
        Write-Helper $helper 'start-all'
        if ([IO.File]::ReadAllText($helper) -notmatch 'Generated by KoFAuth' -or [IO.File]::ReadAllText($helper) -notmatch 'call "%~dp0deploy\.bat" start-all') { Fail 'Self-test: generated helper BAT is invalid.' }
        $userHelper = Join-Path $temp 'START-ALL.bat'
        [IO.File]::WriteAllText($userHelper, "@echo off`r`necho user-owned`r`n", $script:Ascii)
        Write-Helper $userHelper 'start-all'
        if ([IO.File]::ReadAllText($userHelper) -notmatch 'user-owned') { Fail 'Self-test: user-owned BAT was overwritten.' }
        Write-Ok 'Self-test passed: properties/YAML/TOML edits are idempotent, comments are parsed safely, and user BATs are preserved.'
    } finally {
        $script:Root = $oldRoot; $script:TransactionBackup = $oldBackup; $script:State = $oldState
        if ([IO.Directory]::Exists($temp) -and [IO.Path]::GetDirectoryName($temp).TrimEnd('\') -eq [IO.Path]::GetTempPath().TrimEnd('\')) { [IO.Directory]::Delete($temp, $true) }
    }
}

function Invoke-Deploy {
    Assert-VelocityLayout
    $serverJar = Join-Path $script:Root 'server.jar'
    $server = Get-ServerMetadata $serverJar
    $java = Get-JavaPathAndVersion $server.Java
    $release = Validate-ReleaseInputs
    Test-DockerReady
    Write-Ok ('Preflight: Paper/Purpur ' + $server.Minecraft + ', Java ' + $java.Major + ', Docker ready.')

    Acquire-DeployLock
    Initialize-Layout
    if (Recover-PendingTransaction) { return }
    if ([IO.File]::Exists($script:StateFile) -and -not [IO.File]::Exists($script:ProjectFile)) {
        try {
            $projectFromState = [string](([IO.File]::ReadAllText($script:StateFile) | ConvertFrom-Json).composeProject)
            if ($projectFromState -match '^[a-z0-9][a-z0-9_-]{2,62}$') {
                Write-AtomicText $script:ProjectFile ($projectFromState + "`n") $script:Ascii
                Write-Ok 'Recovered compose-project.txt from committed state.json.'
            }
        } catch {}
    }
    $hadInstalledPlugin = Test-ExistingManagedInstallation
    Ensure-Environment $server.Minecraft
    Validate-EnvironmentValues
    Ensure-ProjectName
    Ensure-ComposeBase
    Ensure-ComposeOverride
    Ensure-Helpers
    $script:State = Read-State
    if ($script:State -and [string]$script:State.composeProject -ne $script:ProjectName) { Fail 'state.json and compose-project.txt disagree; restore the matching files from backup.' }
    $stateVelocityDirectory = [string](Get-ObjectValue $script:State 'velocityDirectory' '')
    if ($stateVelocityDirectory -and $stateVelocityDirectory -notin @('_kofauth\proxy','_kofauth\velocity')) { Fail ('Invalid velocityDirectory in state.json: ' + $stateVelocityDirectory) }
    $storedIdentityFingerprint = [string](Get-ObjectValue $script:State 'identityEnvironmentFingerprint' '')
    if ($storedIdentityFingerprint -and $storedIdentityFingerprint -ne (Get-IdentityEnvironmentFingerprint)) {
        Fail 'A database/encryption/forwarding identity secret changed since the last successful deploy. Automatic secret rotation is unsafe; restore _kofauth\config\kofauth.env from backup.'
    }
    if ($script:State -and -not $storedIdentityFingerprint) {
        $projectContainers = @(& docker ps -aq --filter ('label=com.docker.compose.project=' + $script:ProjectName) 2>$null)
        if ($projectContainers.Count -eq 0) { Fail 'This older state has no stored identity fingerprint and no complete container set to verify encryption/database secrets. Restore the prior containers once; the database was not touched.' }
        [void](Test-ProjectMatchesSecrets $script:ProjectName -RequireComplete)
    }
    Ensure-LastKnownGoodBaseline
    Invoke-Compose @('config','-q') -Quiet
    foreach ($component in @('velocity','backend','limbo')) { Assert-PortIsManagedOrFree $component }

    foreach ($component in @('velocity','backend','limbo')) {
        $definition = Get-ComponentDefinition $component
        $script:InitialRunning[$component] = Test-ExactOwnedProcess $component
    }
    foreach ($service in @('mysql','redis','webapi')) { $script:InitialServices[$service] = Test-ServiceRunning $service }
    $existingProjectContainers = @(& docker ps -aq --filter ('label=com.docker.compose.project=' + $script:ProjectName) 2>$null)
    $existingProjectVolumes = @(& docker volume ls -q --filter ('label=com.docker.compose.project=' + $script:ProjectName) 2>$null)
    $bootstrapResume = $null -eq $script:State -and (Test-BootstrapIdentityMarker)
    $bootstrap = ($null -eq $script:State -and -not $hadInstalledPlugin -and $existingProjectContainers.Count -eq 0 -and $existingProjectVolumes.Count -eq 0) -or $bootstrapResume
    if (-not $bootstrap -and -not $script:State) {
        Assert-LegacyDataInfrastructureMatchesDesired
    }
    if (-not $bootstrap -and -not $script:State -and (-not [IO.File]::Exists((Join-Path $script:LastGoodDirectory 'kofauth.env')) -or -not [IO.File]::Exists((Join-Path $script:LastGoodDirectory 'compose.base.yml')))) {
        Save-LastKnownGoodInputs
        Write-Ok 'Captured the adopted installation inputs as its rollback baseline before any recreate.'
    }
    $expectedMysqlVolume = ''
    if ($script:State) {
        $expectedMysqlVolume = [string](Get-ObjectValue $script:State 'mysqlVolume' '')
        Assert-MySqlVolumeIdentity $expectedMysqlVolume
    }

    Ensure-Eula
    Ensure-CoreRuntimes $server

    $backendPlugin = Resolve-KoFAuthPluginPath (Join-Path $script:Root 'plugins') 'paper' 'KoFAuth-Paper.jar'
    $limboPlugin = Resolve-KoFAuthPluginPath (Join-Path $script:Limbo 'plugins') 'paper' 'KoFAuth-Paper.jar'
    $velocityPlugin = Resolve-KoFAuthPluginPath (Join-Path $script:Velocity 'plugins') 'velocity' 'KoFAuth-Velocity.jar'
    Begin-DeploymentTransaction

    $backendNativeMissing = -not [IO.File]::Exists((Join-Path $script:Root 'config\paper-global.yml'))
    $limboNativeMissing = -not [IO.File]::Exists((Join-Path $script:Limbo 'config\paper-global.yml'))
    $velocityNativeMissing = -not [IO.File]::Exists((Join-Path $script:Velocity 'velocity.toml'))
    if ($backendNativeMissing -or $limboNativeMissing) {
        Stop-Component 'velocity'
        if ($backendNativeMissing) { Stop-Component 'backend' }
        if ($limboNativeMissing) { Stop-Component 'limbo' }
    } elseif ($velocityNativeMissing) { Stop-Component 'velocity' }

    if ($backendNativeMissing) {
        [void](Set-PropertiesValues (Join-Path $script:Root 'server.properties') ([ordered]@{'server-ip'='127.0.0.1';'server-port'='25566';'online-mode'='false';'enforce-secure-profile'='false'}))
    }
    if ($limboNativeMissing) {
        [void](Set-PropertiesValues (Join-Path $script:Limbo 'server.properties') ([ordered]@{'server-ip'='127.0.0.1';'server-port'='25567';'online-mode'='false';'enforce-secure-profile'='false';'allow-nether'='false';'spawn-monsters'='false'}))
    }
    Initialize-JavaConfigurationWithoutKoFAuth 'backend' $script:Root $serverJar (Join-Path $script:Root 'config\paper-global.yml') $backendPlugin
    Initialize-JavaConfigurationWithoutKoFAuth 'limbo' $script:Limbo (Join-Path $script:Limbo 'paper.jar') (Join-Path $script:Limbo 'config\paper-global.yml') $limboPlugin
    Initialize-JavaConfigurationWithoutKoFAuth 'velocity' $script:Velocity (Join-Path $script:Velocity 'velocity.jar') (Join-Path $script:Velocity 'velocity.toml') $velocityPlugin

    Apply-Configuration $release.paper $hadInstalledPlugin
    Write-ConflictReport

    $paperHash = Get-FileHash256 $release.paper
    $velocityHash = Get-FileHash256 $release.velocity
    $webHash = Get-FileHash256 $release.webapi
    $managedPaper = Join-Path $script:Artifacts 'kofauth-paper.jar'
    $managedVelocity = Join-Path $script:Artifacts 'kofauth-velocity.jar'
    $managedWeb = Join-Path $script:Artifacts 'kofauth-webapi.jar'
    $stateArtifacts = Get-ObjectValue $script:State 'artifacts'
    $confirmedPaper = [string](Get-ObjectValue $stateArtifacts 'paper' '')
    $confirmedVelocity = [string](Get-ObjectValue $stateArtifacts 'velocity' '')
    $confirmedWeb = [string](Get-ObjectValue $stateArtifacts 'webapi' '')
    $paperChanged = (Get-FileHash256 $backendPlugin) -ne $paperHash -or (Get-FileHash256 $limboPlugin) -ne $paperHash -or $confirmedPaper -ne $paperHash
    $velocityChanged = (Get-FileHash256 $velocityPlugin) -ne $velocityHash -or $confirmedVelocity -ne $velocityHash
    $webChanged = (Get-FileHash256 $managedWeb) -ne $webHash -or $confirmedWeb -ne $webHash
    $environmentFingerprint = Get-RuntimeEnvironmentFingerprint
    $envChanged = [string](Get-ObjectValue $script:State 'runtimeEnvironmentFingerprint' '') -ne $environmentFingerprint
    $storedDataFingerprint = [string](Get-ObjectValue $script:State 'dataInfrastructureFingerprint' '')
    # Legacy/no-state adoption was compared against the live image, command,
    # ports and volume above. It is committed without recreating data services;
    # only later, stateful changes can enter the reviewed recreate path.
    $dataInfrastructureChanged = [bool]$script:State -and (-not $storedDataFingerprint -or $storedDataFingerprint -ne (Get-DataInfrastructureFingerprint))
    $storedComposeHash = [string](Get-ObjectValue $script:State 'composeDefinitionHash' '')
    $composeChanged = $storedComposeHash -ne (Get-FileHash256 $script:ComposeFile)
    if ($storedComposeHash -and $composeChanged -and $env:KOFAUTH_APPROVE_INFRA_UPDATE -ne 'YES') {
        Fail 'The pinned Docker infrastructure definition changed since the last successful deploy. Restore it from backup, or set KOFAUTH_APPROVE_INFRA_UPDATE=YES for this reviewed deployment (a DB dump will be made first).'
    }
    $forceDataInfrastructure = $dataInfrastructureChanged -or ($storedComposeHash -and $composeChanged)

    $backendAffected = $paperChanged -or $envChanged -or $script:ConfigChanged.Contains('backend')
    $limboAffected = $paperChanged -or $envChanged -or $script:ConfigChanged.Contains('limbo')
    $velocityAffected = $velocityChanged -or $envChanged -or $script:ConfigChanged.Contains('velocity')
    $webAffected = $webChanged -or $envChanged -or $composeChanged -or $forceDataInfrastructure -or $script:ConfigChanged.Contains('webapi')

    if ($webAffected) {
        foreach ($component in @('velocity','backend','limbo')) { if (Test-ExactOwnedProcess $component) { Stop-Component $component } }
    } else {
        if (($backendAffected -or $limboAffected -or $velocityAffected) -and (Test-ExactOwnedProcess 'velocity')) { Stop-Component 'velocity' }
        if ($backendAffected -and (Test-ExactOwnedProcess 'backend')) { Stop-Component 'backend' }
        if ($limboAffected -and (Test-ExactOwnedProcess 'limbo')) { Stop-Component 'limbo' }
    }

    $mysqlExists = $null -ne (Get-ServiceContainerId 'mysql')
    if ($webAffected -and ($mysqlExists -or -not $bootstrap)) {
        Ensure-MySqlRunningForBackup $forceDataInfrastructure
        Backup-DatabaseIfPresent
    }
    if ($webAffected -and (Get-ServiceContainerId 'webapi')) { Stop-WebApiOnly }
    if ($forceDataInfrastructure) {
        # This durable marker must precede the first Compose command that can
        # apply the new MySQL/Redis definition. Recovery will then restore the
        # last-known-good pair after a crash at any following instruction.
        $script:DataInfrastructureMayHaveChanged = $true
        Write-TransactionJournal 'data-infrastructure'
        Start-DataServices $true
    }

    [void](Install-FileAtomically $release.paper $managedPaper 'managed Paper artifact')
    [void](Install-FileAtomically $release.velocity $managedVelocity 'managed Velocity artifact')
    [void](Install-FileAtomically $release.webapi $managedWeb 'managed WebAPI artifact')
    if ($paperChanged) {
        [void](Install-FileAtomically $release.paper $backendPlugin 'backend KoFAuth plugin')
        [void](Install-FileAtomically $release.paper $limboPlugin 'Limbo KoFAuth plugin')
        Move-RemapCacheToBackup (Join-Path $script:Root 'plugins') ([IO.Path]::GetFileName($backendPlugin))
        Move-RemapCacheToBackup (Join-Path $script:Limbo 'plugins') ([IO.Path]::GetFileName($limboPlugin))
    }
    if ($velocityChanged) { [void](Install-FileAtomically $release.velocity $velocityPlugin 'Velocity KoFAuth plugin') }

    $shouldRunWeb = $bootstrap -or [bool]$script:InitialServices['webapi']
    if ($shouldRunWeb) {
        if ($webAffected -and -not $bootstrap) {
            $script:DatabaseMayHaveMigrated = $true
            Write-TransactionJournal 'database-migration'
        }
        Start-Infra $webAffected $false
    }
    elseif ($webAffected) {
        Start-DataServices
        Invoke-Compose @('create','--force-recreate','--no-deps','webapi')
        Write-Info 'WebAPI was updated but left stopped, matching its pre-deploy state.'
    }
    Assert-ResolvedMySqlVolumeDefinition
    $activeMysqlVolume = Get-ProjectVolumeName 'mysql-data'
    if ($expectedMysqlVolume) { Assert-MySqlVolumeIdentity $expectedMysqlVolume }
    else { Assert-MySqlVolumeIdentity $activeMysqlVolume }
    if ($bootstrap -or [bool]$script:InitialRunning['limbo']) { [void](Start-Component 'limbo') }
    if ($bootstrap -or [bool]$script:InitialRunning['backend']) { [void](Start-Component 'backend') }
    if ($bootstrap -or [bool]$script:InitialRunning['velocity']) { [void](Start-Component 'velocity') }

    if (-not $bootstrap) {
        # Preserve the operator's exact stopped/running intent independently
        # for every data service, even if it was an unusual partial state.
        if (-not [bool]$script:InitialServices['webapi'] -and (Test-ServiceRunning 'webapi')) { Stop-WebApiOnly }
        if (-not [bool]$script:InitialServices['redis'] -and (Test-ServiceRunning 'redis')) { Invoke-Compose @('stop','redis') }
        if (-not [bool]$script:InitialServices['mysql'] -and (Test-ServiceRunning 'mysql')) { Invoke-Compose @('stop','mysql') }
    }

    $noContentChanges = -not $paperChanged -and -not $velocityChanged -and -not $webChanged -and -not $envChanged -and -not $composeChanged -and -not $forceDataInfrastructure -and $script:ConfigChanged.Count -eq 0
    $stateCommitRequired = -not $noContentChanges -or -not [IO.File]::Exists($script:StateFile)
    if ($stateCommitRequired) {
        Write-StateFile $server $paperHash $velocityHash $webHash
        Save-LastKnownGoodInputs
    }

    $script:RollbackTracking = $false
    if ([IO.File]::Exists($script:PendingTransactionFile)) { [IO.File]::Delete($script:PendingTransactionFile) }
    if ([IO.File]::Exists($script:StateFile) -and [IO.File]::Exists($script:BootstrapIdentityFile)) { [IO.File]::Delete($script:BootstrapIdentityFile) }
    $script:DeploymentStarted = $false

    if ($noContentChanges) {
        Write-Ok 'Idempotence check: artifacts and managed configs were already current; no component was restarted.'
    }
    Write-Host ''
    Write-Ok 'KoFAuth deployment completed successfully.'
    Write-Host ('  Server root:  ' + $script:Root)
    Write-Host ('  Persistent:   ' + $script:Managed)
    Write-Host ('  Control BATs: ' + $script:Commands)
    Write-Host ('  Backup:       ' + $(if ([IO.Directory]::Exists($script:TransactionBackup)) { $script:TransactionBackup } else { 'not needed' }))
}

$normalized = $Action.Trim().ToLowerInvariant()
try {
    if ($normalized -like 'runner-*') {
        Run-ComponentSupervisor $normalized.Substring(7)
    } elseif ($normalized -eq 'self-test') {
        Invoke-SelfTest
    } elseif ($normalized -eq '--dry-run' -or $normalized -eq 'dry-run') {
        Invoke-DryRun
    } elseif ($normalized -eq 'package' -or $normalized -eq 'package-release') {
        Invoke-PackageRelease
    } elseif ($normalized -eq 'help' -or $normalized -eq '--help' -or $normalized -eq '-h') {
        Show-Help
    } elseif ($normalized -eq 'status') {
        Invoke-Status
    } elseif ($normalized -eq 'deploy') {
        Invoke-Deploy
    } elseif ($normalized -in @(
        'start-all','stop-all','restart-all','start-infra','stop-infra','restart-infra',
        'start-backend','stop-backend','restart-backend','command-backend',
        'start-limbo','stop-limbo','restart-limbo','command-limbo',
        'start-velocity','stop-velocity','restart-velocity','command-velocity'
    )) {
        Invoke-ControlAction $normalized
    } else {
        Fail ('Unknown action "' + $Action + '". Run deploy.bat help for the command list.')
    }
} catch {
    $failure = $_
    Write-Host ''
    Write-Host ('[ERROR] ' + $failure.Exception.Message) -ForegroundColor Red
    if ($script:LogFile) {
        [IO.File]::AppendAllText($script:LogFile, ('[ERROR] ' + $failure.Exception.ToString() + [Environment]::NewLine), $script:Utf8NoBom)
        Write-Host ('Transaction log: ' + $script:LogFile) -ForegroundColor DarkGray
    }
    if ($normalized -eq 'deploy' -and $script:DeploymentStarted) {
        try { [void](Invoke-DeploymentRollback) } catch { Write-WarningLine ('Automatic rollback itself failed: ' + $_.Exception.Message) }
    }
    exit 1
} finally {
    if ($script:LockHandle) { $script:LockHandle.Dispose() }
}
