#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$KeyStorePath,
    [string]$KeyAlias = 'upload',
    [string]$ConfigPath = (Join-Path $env:LOCALAPPDATA 'BluetoothAssistant\signing.clixml'),
    [string]$ExpectedCertificateSha256 = '6F87F2BA3D114F9E32B6F2587207FC7F2A3EFD78F1EB2F6B2062363B5B628823',
    [switch]$SeparateKeyPassword
)

$ErrorActionPreference = 'Stop'

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

if (-not $KeyStorePath) { $KeyStorePath = Join-Path $PSScriptRoot '..\..\Dandroid-keys\bluetto\upload.jks' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$configFile = [IO.Path]::GetFullPath($ConfigPath)
if ($configFile.StartsWith($repoRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The signing configuration must be stored outside the repository.'
}
$keyStoreFile = (Resolve-Path -LiteralPath $KeyStorePath).Path
if ($keyStoreFile.StartsWith($repoRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The keystore must be stored outside the repository.'
}
$keytool = (Get-Command keytool -ErrorAction Stop).Source
$expected = $ExpectedCertificateSha256.Replace(':', '').ToUpperInvariant()
if ($expected -notmatch '^[0-9A-F]{64}$') { throw 'Invalid expected upload certificate SHA-256.' }

$storeSecret = Read-Host 'Keystore password (hidden; saved with Windows user encryption)' -AsSecureString
$keySecret = $null
$temporaryCertificate = Join-Path ([IO.Path]::GetTempPath()) ('bluetooth-upload-' + [Guid]::NewGuid().ToString('N') + '.der')
$previousPassword = [Environment]::GetEnvironmentVariable('BLUETOOTH_CONFIGURE_STORE_PASSWORD', 'Process')
try {
    if ($SeparateKeyPassword) {
        $keySecret = Read-Host 'Private-key password (hidden)' -AsSecureString
    } else {
        $keySecret = $storeSecret.Copy()
    }
    [Environment]::SetEnvironmentVariable('BLUETOOTH_CONFIGURE_STORE_PASSWORD',
        [Net.NetworkCredential]::new('', $storeSecret).Password, 'Process')
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = $null
    try {
        & $keytool '-J-Duser.language=en' -exportcert -keystore $keyStoreFile -alias $KeyAlias `
            -storepass:env BLUETOOTH_CONFIGURE_STORE_PASSWORD -file $temporaryCertificate 2>&1 | Out-Null
        $keytoolExitCode = $global:LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorPreference }
    if ($null -eq $keytoolExitCode -or $keytoolExitCode -ne 0) { throw 'Cannot open this keystore/alias with the supplied password. No configuration was saved.' }
    $actual = Get-FileSha256 $temporaryCertificate
    if ($actual -ne $expected) { throw 'The keystore certificate does not match the configured Google Play upload certificate.' }

    New-Item -ItemType Directory -Path (Split-Path -Parent $configFile) -Force | Out-Null
    [pscustomobject]@{
        SchemaVersion = 1
        KeyStorePath = $keyStoreFile
        KeyAlias = $KeyAlias
        CertificateSha256 = $actual
        StorePassword = $storeSecret
        KeyPassword = $keySecret
    } | Export-Clixml -LiteralPath $configFile
    Write-Host "Signing configuration saved: $configFile"
    Write-Host "Upload certificate SHA-256: $actual"
    Write-Host 'Passwords are encrypted for this Windows user on this computer.'
} finally {
    [Environment]::SetEnvironmentVariable('BLUETOOTH_CONFIGURE_STORE_PASSWORD', $previousPassword, 'Process')
    if (Test-Path -LiteralPath $temporaryCertificate) { Remove-Item -LiteralPath $temporaryCertificate -Force }
    $storeSecret.Dispose()
    if ($null -ne $keySecret) { $keySecret.Dispose() }
}
