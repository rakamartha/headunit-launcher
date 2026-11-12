# PangDash Android Car Headunit Launcher (1280x720 HD)

Paket proyek ini siap dikompilasi menjadi file **`.apk`** untuk diinstal pada **Android Car Headunit (1280x720)** dan sudah dilengkapi jembatan perangkat keras Android (`AndroidBridge`) serta modul sinkronisasi Cloud ke aplikasi HP.

---

## Cara Mengubah Proyek Ini Menjadi File `.APK`

### Cara 1: Otomatis Lewat GitHub Actions (± 2 Menit)
1. Buat repositori baru di akun GitHub Anda, lalu unggah isi folder proyek ini.
2. Tambahkan secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, dan `KEY_PASSWORD` (lihat **`SIGNING_AND_OTA.md`**). Tanpa keystore, workflow hanya membuat APK debug dan tidak mempublikasikan OTA.
3. Buka tab **Actions** di repositori GitHub Anda.
4. Workflow **Build, Sign & Publish PangDash OTA APK** akan mengompilasi APK rilis, memverifikasi sertifikat, menerbitkan GitHub Release, dan mengirim payload OTA.
5. Unduh APK dari artifact/release, lalu instal di Headunit Android.

### Cara 2: Menggunakan Android Studio atau Gradle Wrapper di Laptop/PC
1. Pastikan **JDK 17** aktif (Android Studio: *Settings → Build Tools → Gradle → Gradle JDK = 17*). Gradle 8.5 belum mendukung Java 25.
2. Buka **Android Studio** → **Open** → arahkan ke folder proyek ini, lalu tunggu *Gradle Sync*.
3. Build debug: `gradlew.bat assembleDebug`, atau lewat menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. File `.apk` tersedia di `app/build/outputs/apk/debug/app-debug.apk`.
5. Untuk APK rilis bertanda tangan, siapkan `keystore.properties` (contoh di `keystore.properties.example`) lalu jalankan `gradlew.bat assembleRelease`.

---

## Fitur Native Android yang Aktif Saat Diinstal sebagai APK
* **Home Launcher Resmi**: Saat menekan tombol *Home* di headunit, Android akan menanyakan apakah Anda ingin menjadikan **PangDash Launcher** sebagai layar utama (*Default Home App*).
* **Kontrol Brightness Headunit Asli**: Menggeser bar kecerahan di pojok kanan atas langsung mengubah kecerahan layar sistem Android headunit (`WindowManager` & `Settings.System.SCREEN_BRIGHTNESS`).
* **Kontrol Volume Audio Asli**: Menggeser bar volume di atas Pintasan Cepat langsung mengatur `AudioManager.STREAM_MUSIC` pada headunit.
* **Peluncur Aplikasi Native (App Drawer)**: Menekan ikon aplikasi pada kategori **Semua, Navigasi, Media, Sistem, Mobil, Lainnya** langsung membuka aplikasi Android yang terinstal di headunit (Google Maps, Waze, Spotify, YouTube, Netflix, DSP EQ, dll.).
* **GPS Speedometer Real-Time**: Membaca kecepatan langsung dari chip satelit GPS headunit (`LocationManager.GPS_PROVIDER`).
