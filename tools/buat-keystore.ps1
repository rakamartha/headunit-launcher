# =============================================================================
#  buat-keystore.ps1
#  Membuat keystore rilis PangDash / PangLink sekaligus menyiapkan
#  nilai base64 untuk GitHub Secrets.
#
#  Script ini HANYA bekerja di komputer Anda. Tidak ada data yang dikirim
#  ke internet.
#
#  Cara pakai (PowerShell, dari folder ini):
#      powershell -ExecutionPolicy Bypass -File .\buat-keystore.ps1
#
#  Hasil:
#      <OutDir>\release-keystore.p12        <- file identitas aplikasi (RAHASIA)
#      <OutDir>\release-keystore.base64.txt <- isi secret KEYSTORE_BASE64
# =============================================================================

[CmdletBinding()]
param(
    [string]$OutDir = (Join-Path $env:USERPROFILE 'PangDash-Keys'),
    [string]$Alias  = 'pangdash',
    [string]$Dname  = 'CN=PangDash, OU=PangDash, O=PangDash, L=Jakarta, ST=DKI Jakarta, C=ID',
    # Sengaja tidak default: clipboard bisa berisi data lama dari aplikasi lain.
    [switch]$CopyToClipboard
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

    foreach ($path in $candidates) {
        if ($path -and (Test-Path $path)) { return $path }
    }
    return $null
}

function Read-PlainPassword {
    param([string]$Prompt)

    $secure = Read-Host -AsSecureString -Prompt $Prompt
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

Write-Host ''
Write-Host '=== PangDash :: Pembuat Keystore Rilis ===' -ForegroundColor Cyan
Write-Host ''

$keytool = Find-Keytool
if (-not $keytool) {
    Write-Host 'ERROR: keytool tidak ditemukan.' -ForegroundColor Red
    Write-Host 'Pasang JDK 17 (Temurin/Adoptium) atau Android Studio, lalu jalankan ulang script ini.'
    exit 1
}
Write-Host "keytool : $keytool"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$keystorePath = Join-Path $OutDir 'release-keystore.p12'
$base64Path   = Join-Path $OutDir 'release-keystore.base64.txt'

if (Test-Path $keystorePath) {
    Write-Host ''
    Write-Host "File keystore sudah ada: $keystorePath" -ForegroundColor Yellow
    $answer = Read-Host 'Timpa file lama? (y/N)'
    if ($answer -notmatch '^[yY]') {
        Write-Host 'Dibatalkan. File lama tidak diubah.'
        exit 0
    }
    Remove-Item $keystorePath -Force
}

# Password bisa diberikan lewat environment variable (untuk otomasi);
# normalnya script akan menanyakannya secara tersembunyi.
$password = $env:PANGDASH_KEYSTORE_PASSWORD
if ([string]::IsNullOrWhiteSpace($password)) {
    $password = Read-PlainPassword 'Password keystore (min. 6 karakter, ingat baik-baik)'
    $confirm  = Read-PlainPassword 'Ulangi password'
    if ($password -ne $confirm) {
        Write-Host 'ERROR: password tidak sama. Dibatalkan.' -ForegroundColor Red
        exit 1
    }
}

if ([string]::IsNullOrWhiteSpace($password) -or $password.Length -lt 6) {
    Write-Host 'ERROR: password minimal 6 karakter. Dibatalkan.' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'Membuat keystore (RSA 4096-bit, berlaku ~27 tahun)...'
# keytool menulis progres ke stderr; jangan dianggap error.
& $keytool -genkeypair -v `
    -keystore $keystorePath `
    -storetype PKCS12 `
    -alias $Alias `
    -keyalg RSA -keysize 4096 -validity 10000 `
    -storepass $password `
    -keypass $password `
    -dname $Dname

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystorePath)) {
    Write-Host ''
    Write-Host 'ERROR: keytool gagal membuat keystore.' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'Membuat nilai base64 untuk GitHub Secret...'
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
[IO.File]::WriteAllText($base64Path, $base64, [Text.Encoding]::ASCII)
$fileSha256 = (Get-FileHash $keystorePath -Algorithm SHA256).Hash

$copied = $false
if ($CopyToClipboard) {
    try { Set-Clipboard -Value $base64; $copied = $true } catch { }
}

# Sidik jari sertifikat (BUKAN rahasia) berguna untuk memastikan APK rilis
# ditandatangani keystore yang benar.
$fingerprint = ''
$fingerprintPath = Join-Path $OutDir 'release-keystore.fingerprint.txt'
try {
    $listOutput = & $keytool -list -v -keystore $keystorePath -storetype PKCS12 -storepass $password 2>&1 | Out-String
    $match = [regex]::Match($listOutput, 'SHA256:\s*([0-9A-Fa-f:]{95})')
    if ($match.Success) {
        $fingerprint = $match.Groups[1].Value.ToUpperInvariant()
        $lines = @(
            "Fingerprint sertifikat SHA-256 : $fingerprint",
            "SHA-256 file keystore          : $fileSha256",
            "Alias                          : $Alias",
            "Dibuat                         : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
            '',
            'Fingerprint sertifikat dibandingkan dengan apksigner verify --print-certs pada APK rilis.',
            'SHA-256 file keystore dibandingkan dengan baris "SHA-256 file keystore" di log GitHub Actions.'
        )
        [IO.File]::WriteAllText($fingerprintPath, ($lines -join [Environment]::NewLine), [Text.Encoding]::ASCII)
    }
}
catch { }

Write-Host ''
Write-Host '=== SELESAI ===' -ForegroundColor Green
Write-Host ''
Write-Host 'File yang dibuat:'
Write-Host "  1) $keystorePath"
Write-Host "  2) $base64Path"
if ($fingerprint) {
    Write-Host "  3) $fingerprintPath"
    Write-Host ''
    Write-Host "Fingerprint sertifikat SHA-256: $fingerprint" -ForegroundColor Cyan
    Write-Host '(bukan rahasia, boleh disimpan/dicatat untuk verifikasi APK rilis)'
}
Write-Host ''
Write-Host "SHA-256 file keystore: $fileSha256" -ForegroundColor Cyan
Write-Host 'Nilai ini akan muncul di log GitHub Actions; kalau berbeda, berarti secret KEYSTORE_BASE64 bukan file ini.'
if ($copied) { Write-Host 'Nilai base64 sudah disalin ke clipboard.' -ForegroundColor Green }
else { Write-Host 'Base64 TIDAK disalin otomatis. Buka file .base64.txt, Ctrl+A lalu Ctrl+C saat siap paste.' }
Write-Host ''
Write-Host 'Langkah selanjutnya:' -ForegroundColor Cyan
Write-Host "  1. BACKUP file release-keystore.p12 + password ke 2 tempat (drive eksternal / password manager)."
Write-Host '  2. Buka repo GitHub -> Settings -> Secrets and variables -> Actions -> New repository secret.'
Write-Host '     Tambahkan 4 secret berikut:'
Write-Host '       KEYSTORE_BASE64    = isi file release-keystore.base64.txt (satu baris panjang)'
Write-Host "       KEYSTORE_PASSWORD  = password yang baru Anda ketik"
Write-Host "       KEY_ALIAS          = $Alias"
Write-Host '       KEY_PASSWORD       = password yang sama'
Write-Host '  3. Push ke branch main (atau jalankan workflow manual) untuk membuat rilis bertanda tangan.'
Write-Host ''
Write-Host 'PENTING: jangan pernah commit file .p12 ke Git dan jangan kirim ke siapa pun.' -ForegroundColor Yellow
Write-Host 'Jika keystore ini hilang, update OTA tidak bisa dipasang lagi tanpa uninstall manual.' -ForegroundColor Yellow
Write-Host 'Clipboard hanya alat bantu sementara: file .p12 + password adalah sumber utama.' -ForegroundColor Yellow
Write-Host ''
