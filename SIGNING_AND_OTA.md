# Signing & OTA — Panduan Operasional

Dokumen ini menjelaskan cara membuat keystore rilis, memasang secret di GitHub,
dan memastikan update OTA bisa dipasang di atas versi lama.

> **Penting:** APK rilis **wajib** ditandatangani dengan keystore yang sama setiap
> kali build. APK debug dari runner CI selalu punya signature berbeda, sehingga
> Android menolak update in-place dengan `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

---

## 1. Buat keystore rilis (sekali saja)

### Cara cepat (Windows, disarankan)

Script pembantu sudah disertakan di `tools/buat-keystore.ps1`. Jalankan dari
root proyek:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\buat-keystore.ps1
```

Script akan:
1. Mencari `keytool` (JAVA_HOME / Android Studio JBR / PATH).
2. Meminta password dua kali (input tersembunyi).
3. Membuat `%USERPROFILE%\PangDash-Keys\release-keystore.p12` (PKCS12, RSA 4096).
4. Menulis nilai base64 ke `release-keystore.base64.txt`, plus file sidik jari
   (`release-keystore.fingerprint.txt`).

> Base64 **tidak** disalin ke clipboard secara default, karena clipboard bisa
> berisi data lama dari aplikasi lain. Tambahkan `-CopyToClipboard` bila ingin
> disalin otomatis, lalu segera paste ke GitHub tanpa menyalin hal lain.

Sebelum menekan **Add secret**, pastikan isi clipboard memang file Anda:

```powershell
(Get-Clipboard -Raw).Trim().Length   # harus 5884 untuk keystore 4412 byte
```

### Cara manual

```powershell
$pwd = Read-Host -AsSecureString 'Password keystore'
$plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($pwd))
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore release-keystore.p12 -storetype PKCS12 `
  -alias pangdash -keyalg RSA -keysize 4096 -validity 10000 `
  -storepass $plain -keypass $plain `
  -dname "CN=PangDash, OU=PangDash, O=PangDash, L=Jakarta, ST=DKI Jakarta, C=ID"
```

Simpan file `release-keystore.p12` + password di tempat aman (password manager /
drive terenkripsi), minimal dua salinan. **Jangan commit ke Git** (`.gitignore`
sudah memblokir `*.p12`, `*.jks`, `*.keystore`). Jika file ini hilang, pengguna
tidak bisa menerima update OTA lagi dan harus instal ulang manual.

### Encode ke base64 untuk GitHub Secrets

PowerShell (hasil langsung ke clipboard, tanpa newline):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-keystore.p12")) | Set-Clipboard
```

Bash/Linux:

```bash
base64 -w0 release-keystore.p12
```

---

## 2. Pasang GitHub Secrets

Buka **Settings → Secrets and variables → Actions → New repository secret**,
lalu tambahkan:

| Secret | Isi |
|---|---|
| `KEYSTORE_BASE64` | hasil base64 dari `release-keystore.jks` |
| `KEYSTORE_PASSWORD` | password keystore |
| `KEY_ALIAS` | alias, contoh `pangdash` |
| `KEY_PASSWORD` | password key (biasanya sama) |

Workflow akan:
1. Men-decode keystore ke `$RUNNER_TEMP/release.jks`.
2. Build `assembleRelease` dengan `versionCodeOverride`.
3. Memverifikasi sertifikat APK memakai `apksigner`.
4. Membuat/memperbarui GitHub Release dan mengirim payload OTA berisi `sha256`.

Jika `KEYSTORE_BASE64` belum diisi, workflow **hanya** membuat APK debug dan
**tidak** mempublikasikan rilis OTA (mencegah update yang pasti gagal).

---

## 3. Build lokal

Wrapper Gradle sudah disertakan. Jalankan dari root proyek:

```bat
gradlew.bat assembleDebug
```

Untuk build release bertanda tangan:

```bat
gradlew.bat assembleRelease -PversionCodeOverride=200
```

dengan `keystore.properties` di root proyek (lihat `keystore.properties.example`):

```properties
KEYSTORE_PATH=release-keystore.p12
KEYSTORE_PASSWORD=PASSWORD_ANDA
KEY_ALIAS=pangdash
KEY_PASSWORD=PASSWORD_ANDA
```

**JDK:** proyek memakai Java 17. Di Android Studio set
*Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK*
ke **JDK 17**. Gradle 8.5 belum mendukung Java 25.

Verifikasi sertifikat APK:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

---

## 4. Versi & tag rilis

- `versionName` masih manual di `app/build.gradle.kts` (contoh `1.1`).
  Naikkan saat ada rilis fitur.
- `versionCode` di CI dihitung otomatis: `101 + GITHUB_RUN_NUMBER`, sehingga
  selalu naik dan OTA terdeteksi.
- Tag rilis: `v<versionName>-b<versionCode>`, contoh `v1.1-b142`.
- Workflow aman dijalankan berulang: jika tag sudah ada, aset di-upload ulang
  dengan `--clobber`.

---

## 5. Topik relay OTA

Topik ntfy diturunkan dari nama repo, bukan hardcode:

```
pangdash_ota_<owner>_<repo>   (huruf kecil, non-alfanumerik → "_")
```

Contoh repo `rakamartha/PangDash-Headunit` → `pangdash_ota_rakamartha_pangdash_headunit`.

Ini mencegah fork lain saling menimpa payload update.

---

## 6. Migrasi dari APK debug lama

APK yang beredar sebelumnya ditandatangani debug key yang berbeda-beda. Karena
itu update OTA pertama ke APK rilis akan ditolak. Lakukan **satu kali** instal
manual:

1. Backup data penting (jika perlu).
2. Uninstall PangDash lama.
3. Instal APK rilis bertanda tangan dari GitHub Release.
4. Update berikutnya bisa lewat OTA.

Aplikasi juga memverifikasi bahwa sertifikat APK unduhan sama dengan sertifikat
aplikasi terpasang sebelum membuka installer, sehingga kegagalan terdeteksi
lebih awal dengan pesan yang jelas.
