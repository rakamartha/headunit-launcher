# =============================================================================
#  cek-keystore.ps1
#  Menampilkan isi & sidik jari (fingerprint) keystore rilis, untuk memastikan
#  file backup yang Anda simpan benar-benar keystore yang dipakai menandatangani APK.
#
#  Tidak mengubah apa pun pada keystore.
#
#  Cara pakai:
#      powershell -ExecutionPolicy Bypass -File .\tools\cek-keystore.ps1
#      powershell -ExecutionPolicy Bypass -File .\tools\cek-keystore.ps1 -Path "D:\Backup\release-keystore.p12"
# =============================================================================

[CmdletBinding()]
param(
    [string]$Path = (Join-Path (Join-Path $env:USERPROFILE 'PangDash-Keys') 'release-keystore.p12')
)

$ErrorActionPreference = 'Continue'

function Find-Keytool {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
    $candidates += @(
        (Join-Path $env:USERPROFILE 'AppData\Local\Temp\opencode\jdk17\bin\keytool.exe'),
        'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe',
        'C:\Program Files\Java\jdk-17\bin\keytool.exe',
        'C:\Program Files\Eclipse Adoptium\jdk-17\bin\keytool.exe'
    )
    $onPath = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

Write-Host ''
Write-Host '=== PangDash :: Cek Keystore ===' -ForegroundColor Cyan
Write-Host ''

if (-not (Test-Path $Path)) {
    Write-Host "ERROR: file tidak ditemukan: $Path" -ForegroundColor Red
    exit 1
}

$keytool = Find-Keytool
if (-not $keytool) {
    Write-Host 'ERROR: keytool tidak ditemukan. Pasang JDK 17 atau Android Studio.' -ForegroundColor Red
    exit 1
}

$fileInfo = Get-Item $Path
$fileHash = (Get-FileHash $Path -Algorithm SHA256).Hash
Write-Host "File   : $($fileInfo.FullName)"
Write-Host "Ukuran : $($fileInfo.Length) byte"
Write-Host "SHA256 file : $fileHash"
Write-Host '(hash file berbeda dengan fingerprint sertifikat di bawah)'
Write-Host ''

$password = $env:PANGDASH_KEYSTORE_PASSWORD
if ([string]::IsNullOrWhiteSpace($password)) {
    $secure = Read-Host -AsSecureString -Prompt 'Password keystore'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$output = & $keytool -list -v -keystore $Path -storetype PKCS12 -storepass $password 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $output -match 'password was incorrect|Keystore was tampered') {
    Write-Host ''
    Write-Host 'ERROR: password salah atau file keystore rusak.' -ForegroundColor Red
    exit 1
}

$fingerprintMatch = [regex]::Match($output, 'SHA256:\s*([0-9A-Fa-f:]{95})')
$aliasMatches = [regex]::Matches($output, 'Alias name:\s*(.+)')
$validityMatch = [regex]::Match($output, 'Valid from:.*until:\s*(.+)')

Write-Host 'Isi keystore:' -ForegroundColor Cyan
foreach ($alias in $aliasMatches) { Write-Host "  Alias : $($alias.Groups[1].Value.Trim())" }
if ($validityMatch.Success) { Write-Host "  Berlaku sampai: $($validityMatch.Groups[1].Value.Trim())" }

if ($fingerprintMatch.Success) {
    Write-Host ''
    Write-Host "Fingerprint sertifikat (SHA-256):" -ForegroundColor Green
    Write-Host "  $($fingerprintMatch.Groups[1].Value.ToUpperInvariant())"
    Write-Host ''
    Write-Host 'Bandingkan dengan log GitHub Actions:'
    Write-Host '  "Signer #1 certificate SHA-256 digest"'

    $fingerprintPath = Join-Path $fileInfo.DirectoryName 'release-keystore.fingerprint.txt'
    $lines = @(
        "SHA-256 : $($fingerprintMatch.Groups[1].Value.ToUpperInvariant())",
        "File    : $($fileInfo.FullName)",
        "Dibuat  : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    )
    try { [IO.File]::WriteAllText($fingerprintPath, ($lines -join [Environment]::NewLine), [Text.Encoding]::ASCII) } catch { }
}
else {
    Write-Host 'Tidak dapat membaca fingerprint sertifikat.' -ForegroundColor Yellow
}

Write-Host ''
