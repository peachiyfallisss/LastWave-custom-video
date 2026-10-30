param([string]$Gh = 'gh')
$ErrorActionPreference = 'Stop'
$repo = 'peachiyfallisss/LastWave-custom-video'
$root = Split-Path $PSScriptRoot -Parent
$signing = Join-Path $env:USERPROFILE '.lastwave\signing'
$key = Join-Path $signing 'updates.p12'
$passwordFile = Join-Path $signing 'updates-password.dpapi'
$pin = Join-Path $root 'config\managed-updates-signer.sha256'
if ((Test-Path -LiteralPath $pin) -and -not (Test-Path -LiteralPath $key)) {
    throw 'Permanent signing identity already exists. Restore its private key; do not generate a replacement.'
}
New-Item -ItemType Directory -Path $signing -Force | Out-Null
# Keep local signing material outside the repository, readable only by this Windows account.
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $signing /inheritance:r /grant:r "${user}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the local signing folder' }
if (-not (Test-Path -LiteralPath $key)) {
    $existing = & $Gh api "repos/$repo/actions/secrets" --jq '.secrets[].name'
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect signing-secret names' }
    if ($existing -contains 'MANAGED_UPDATES_KEYSTORE') { throw 'A signing key is already configured remotely. Restore it instead of replacing it.' }
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $secure = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    $secure | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordFile
    $env:LASTWAVE_KEY_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
    try {
        & keytool -genkeypair -keystore $key -storetype PKCS12 -alias lastwave_updates -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=LastWave Updates' -storepass:env LASTWAVE_KEY_PASSWORD -keypass:env LASTWAVE_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Permanent signing key generation failed' }
    } finally { Remove-Item Env:LASTWAVE_KEY_PASSWORD -ErrorAction SilentlyContinue }
}
if (-not (Test-Path -LiteralPath $passwordFile)) { throw 'Restore the matching signing password before continuing' }
$secure = ConvertTo-SecureString (Get-Content -LiteralPath $passwordFile -Raw).Trim()
$env:LASTWAVE_KEY_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
try {
    $cert = Join-Path $signing 'updates-public-cert.der'
    & keytool -exportcert -keystore $key -alias lastwave_updates -storepass:env LASTWAVE_KEY_PASSWORD -file $cert
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the permanent signing certificate' }
    $fingerprint = (Get-FileHash -LiteralPath $cert -Algorithm SHA256).Hash.ToLower()
    if ((Test-Path -LiteralPath $pin) -and ((Get-Content -LiteralPath $pin -Raw).Trim() -ne $fingerprint)) {
        throw 'Signing certificate differs from the pinned identity. Nothing was uploaded.'
    }
    New-Item -ItemType Directory -Path (Split-Path $pin -Parent) -Force | Out-Null
    $fingerprint | Set-Content -LiteralPath $pin -Encoding ascii
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($key)) | & $Gh secret set MANAGED_UPDATES_KEYSTORE --repo $repo
    if ($LASTEXITCODE -ne 0) { throw 'Keystore secret upload failed' }
    $env:LASTWAVE_KEY_PASSWORD | & $Gh secret set MANAGED_UPDATES_PASSWORD --repo $repo
    if ($LASTEXITCODE -ne 0) { throw 'Signing password secret upload failed' }
    Write-Output "Permanent update signer: $fingerprint"
    Write-Output 'Signing secrets configured. Private values were not committed or printed.'
    Write-Output "Local encrypted backup: $signing (password is Windows-account protected)."
} finally { Remove-Item Env:LASTWAVE_KEY_PASSWORD -ErrorAction SilentlyContinue; $secure = $null }
