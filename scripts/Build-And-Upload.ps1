#Requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$BuildOnly,
    [string]$SigningConfigPath = (Join-Path $env:LOCALAPPDATA 'BluetoothAssistant\signing.clixml'),
    [string]$ServiceAccountPath,
    [ValidateRange(1, 2100000000)][int]$VersionCode,
    [string]$VersionName,
    [string]$ChineseNotesPath,
    [string]$EnglishNotesPath
)

$ErrorActionPreference = 'Stop'
if (-not $ServiceAccountPath) { $ServiceAccountPath = Join-Path $PSScriptRoot '..\..\Dandroid-keys\bluetto\play-service-account.json' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactRoot = Join-Path $repoRoot 'release-artifacts'
$pipelineBuildDirectory = Join-Path $repoRoot '.release-tools\android-build'
$runDirectory = Join-Path $artifactRoot ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$savedEnvironment = @{}
$secretValues = @()
$pipelineLock = $null
$signing = $null

function Assert-ExternalPath {
    param([string]$Path, [string]$Description)
    if ($Path -notmatch '^(?:[A-Za-z]:[\\/]|\\\\(?![?.]\\)[^\\/]+\\[^\\/]+\\)') { throw "$Description requires a fully qualified absolute path." }
    $absolute = [IO.Path]::GetFullPath($Path)
    if ($absolute.Equals($repoRoot.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase) -or
            $absolute.StartsWith($repoRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description must be stored outside the repository."
    }
}

function Get-FileSha256 {
    param([string]$Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    $stream = $null
    try {
        $stream = [IO.File]::OpenRead($Path)
        return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '')
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        $algorithm.Dispose()
    }
}

function Invoke-LoggedCommand {
    param([string]$Executable, [string[]]$Arguments, [string]$LogPath)
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = $null
    $commandExitCode = $null
    try {
        & $Executable @Arguments 2>&1 | ForEach-Object {
            $line = $_.ToString()
            foreach ($secret in $script:secretValues) {
                if (-not [string]::IsNullOrEmpty($secret)) { $line = $line.Replace($secret, '[REDACTED]') }
            }
            Write-Host $line
            Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
        }
        $commandExitCode = $global:LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    if ($null -eq $commandExitCode) { throw "Command did not start or return an exit code. See $LogPath" }
    if ($commandExitCode -ne 0) { throw "Command failed (exit $commandExitCode). See $LogPath" }
}

Push-Location -LiteralPath $repoRoot
try {
    New-Item -ItemType Directory -Path (Join-Path $repoRoot '.scratch') -Force | Out-Null
    $pipelineLock = [IO.File]::Open((Join-Path $repoRoot '.scratch\release-pipeline.lock'),
        [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    if (-not (Test-Path -LiteralPath $SigningConfigPath)) {
        throw 'Signing configuration missing. Run scripts\Set-ReleaseSigning.ps1 once on this Windows account.'
    }
    Assert-ExternalPath $SigningConfigPath 'Signing configuration'
    Assert-ExternalPath $ServiceAccountPath 'Google Play credentials'
    $signing = Import-Clixml -LiteralPath $SigningConfigPath
    if ($signing.SchemaVersion -ne 1 -or $signing.StorePassword -isnot [Security.SecureString] `
            -or $signing.KeyPassword -isnot [Security.SecureString]) {
        throw 'Invalid signing configuration; recreate it with Set-ReleaseSigning.ps1.'
    }
    if (-not (Test-Path -LiteralPath $signing.KeyStorePath -PathType Leaf)) { throw 'Configured keystore not found.' }
    Assert-ExternalPath $signing.KeyStorePath 'Keystore'
    $java = (Get-Command java -ErrorAction Stop).Source
    Get-Command keytool, jarsigner -ErrorAction Stop | Out-Null
    $python = (Get-Command python -ErrorAction Stop).Source
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    # Offline regressions use disposable test keys, never the upload signing key.
    Invoke-LoggedCommand $python @('-B', '-m', 'unittest', 'discover', '-s',
        (Join-Path $PSScriptRoot 'tests'), '-p', 'test_*.py') (Join-Path $runDirectory 'script-tests.log')
    $sourceSnapshot = Join-Path $runDirectory 'source-before.json'
    Invoke-LoggedCommand $python @('-B', (Join-Path $PSScriptRoot 'build_info.py'), 'capture',
        '--repository', $repoRoot, '--output', $sourceSnapshot) (Join-Path $runDirectory 'evidence.log')

    $secretValues = @(
        [Net.NetworkCredential]::new('', $signing.StorePassword).Password,
        [Net.NetworkCredential]::new('', $signing.KeyPassword).Password
    )
    $buildEnvironment = @{
        BLUETOOTH_UPLOAD_KEYSTORE = $signing.KeyStorePath
        BLUETOOTH_UPLOAD_KEY_ALIAS = $signing.KeyAlias
        BLUETOOTH_UPLOAD_STORE_PASSWORD = $secretValues[0]
        BLUETOOTH_UPLOAD_KEY_PASSWORD = $secretValues[1]
    }
    foreach ($name in $buildEnvironment.Keys) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $buildEnvironment[$name], 'Process')
    }
    $gradleArguments = @('--no-daemon', '--no-configuration-cache', '--console=plain',
        "-PreleasePipelineBuildDir=$pipelineBuildDirectory",
        'testDebugUnitTest', 'lintRelease', 'writeReleaseUploadMetadata')
    # Building a reviewable artifact must not upload a production Crashlytics mapping.
    # Keep normal release collection/runtime behavior; this is separate from offlineAudit.
    if ($BuildOnly) { $gradleArguments += '-PskipMappingUpload' }
    if ($PSBoundParameters.ContainsKey('VersionCode')) { $gradleArguments += "-PreleaseVersionCode=$VersionCode" }
    if ($PSBoundParameters.ContainsKey('VersionName')) {
        if ($VersionName -notmatch '^[0-9A-Za-z][0-9A-Za-z.+_-]{0,49}$') { throw 'Invalid version name.' }
        $gradleArguments += "-PreleaseVersionName=$VersionName"
    }
    Invoke-LoggedCommand (Join-Path $repoRoot 'gradlew.bat') $gradleArguments (Join-Path $runDirectory 'build.log')

    # Restore password-bearing environment variables before running upload tools.
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $metadataPath = Join-Path $pipelineBuildDirectory 'outputs\release-pipeline\metadata.json'
    $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($metadata.packageName -ne 'com.zzf.bluetoothsmp') { throw 'Unexpected application package; refusing to upload.' }
    $bundlePath = Join-Path $runDirectory ("bluetoothsmp-{0}.aab" -f $metadata.versionCode)
    Copy-Item -LiteralPath (Join-Path $pipelineBuildDirectory 'outputs\bundle\release\app-release.aab') -Destination $bundlePath
    Invoke-LoggedCommand $java @((Join-Path $PSScriptRoot 'VerifyBundle.java'), $bundlePath, $signing.CertificateSha256) (Join-Path $runDirectory 'verify.log')
    $bundleHash = Get-FileSha256 $bundlePath
    Invoke-LoggedCommand $python @('-B', (Join-Path $PSScriptRoot 'build_info.py'), 'write',
        '--repository', $repoRoot, '--before', $sourceSnapshot, '--build-directory', $pipelineBuildDirectory,
        '--artifact', $bundlePath, '--signature-status', 'verified', '--signature-evidence', (Join-Path $runDirectory 'verify.log'),
        '--offline-scripts-status', 'passed', '--offline-scripts-evidence', (Join-Path $runDirectory 'script-tests.log'),
        '--output', (Join-Path $runDirectory 'build-info.json')) (Join-Path $runDirectory 'evidence.log')
    if (-not $ChineseNotesPath) { $ChineseNotesPath = Join-Path $repoRoot ("docs\RELEASE_NOTES_{0}_zh-CN.txt" -f $metadata.versionName) }
    if (-not $EnglishNotesPath) { $EnglishNotesPath = Join-Path $repoRoot ("docs\RELEASE_NOTES_{0}_en-US.txt" -f $metadata.versionName) }
    $plan = [ordered]@{
        packageName = $metadata.packageName
        versionCode = [int]$metadata.versionCode
        bundlePath = $bundlePath
        sha256 = $bundleHash
        releaseName = "{0} ({1})" -f $metadata.versionName, $metadata.versionCode
        releaseNotes = @(
            @{ language = 'zh-CN'; text = (Get-Content -LiteralPath $ChineseNotesPath -Raw -Encoding UTF8).Trim() },
            @{ language = 'en-US'; text = (Get-Content -LiteralPath $EnglishNotesPath -Raw -Encoding UTF8).Trim() }
        )
        track = 'internal'
    }
    $planPath = Join-Path $runDirectory 'upload-plan.json'
    [IO.File]::WriteAllText($planPath, ($plan | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Invoke-LoggedCommand $python @((Join-Path $PSScriptRoot 'play_upload.py'), 'plan', '--config', $planPath) (Join-Path $runDirectory 'plan.log')
    Write-Host "Signed AAB: $bundlePath"
    Write-Host "SHA-256: $bundleHash"
    if ($BuildOnly) { Write-Host 'BuildOnly complete. No Google Play upload was attempted.'; return }

    if (-not (Test-Path -LiteralPath $ServiceAccountPath -PathType Leaf)) {
        throw "Signed build is ready. Google Play service-account JSON is missing: $ServiceAccountPath . This is separate from upload.jks and google-services.json."
    }
    $venvRoot = Join-Path $repoRoot '.release-tools\venv'
    $uploadPython = Join-Path $venvRoot 'Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $uploadPython)) {
        Invoke-LoggedCommand $python @('-m', 'venv', $venvRoot) (Join-Path $runDirectory 'setup.log')
    }
    Invoke-LoggedCommand $uploadPython @('-m', 'pip', 'install', '--disable-pip-version-check',
        '-r', (Join-Path $PSScriptRoot 'requirements-play-upload.txt')) (Join-Path $runDirectory 'setup.log')
    # A stable parent is required for the uploader's cross-run unresolved-edit guard.
    $receiptDirectory = Join-Path $artifactRoot 'receipts'
    New-Item -ItemType Directory -Path $receiptDirectory -Force | Out-Null
    $receipt = Join-Path $receiptDirectory ((Split-Path -Leaf $runDirectory) + '.json')
    Invoke-LoggedCommand $uploadPython @((Join-Path $PSScriptRoot 'play_upload.py'), 'upload',
        '--config', $planPath, '--receipt', $receipt, '--service-account-json',
        (Resolve-Path -LiteralPath $ServiceAccountPath).Path) (Join-Path $runDirectory 'upload.log')
    Write-Host "Google Play internal draft uploaded. Receipt: $receipt"
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $secretValues = @()
    if ($null -ne $signing) {
        if ($signing.StorePassword -is [Security.SecureString]) { $signing.StorePassword.Dispose() }
        if ($signing.KeyPassword -is [Security.SecureString]) { $signing.KeyPassword.Dispose() }
    }
    if ($null -ne $pipelineLock) { $pipelineLock.Dispose() }
    Pop-Location
}
