# PangDash Android Car Headunit Launcher (1280x720 HD)

Paket proyek ini siap dikompilasi menjadi file **`.apk`** untuk diinstal pada **Android Car Headunit (1280x720)** dan sudah dilengkapi jembatan perangkat keras Android (`AndroidBridge`) serta modul sinkronisasi Cloud ke aplikasi HP.

---

## Cara Mengubah Proyek Ini Menjadi File `.APK`

### Cara 1: Otomatis Tanpa Instal Aplikasi (Lewat GitHub Actions - 2 Menit)
1. Buat repositori baru di akun GitHub Anda, lalu unggah isi folder proyek ini.
2. Buka tab **Actions** di repositori GitHub Anda.
3. Workflow **Build PangDash Headunit APK** akan berjalan otomatis dan mengompilasi file **`app-debug.apk`** yang siap Anda unduh dan instal di Headunit Android.

### Cara 2: Menggunakan Android Studio di Laptop/PC
1. Buka **Android Studio** -> pilih **Open** -> arahkan ke folder proyek ini.
2. Tunggu proses *Gradle Sync* selesai.
3. Klik menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. File `.apk` akan tersedia di `app/build/outputs/apk/debug/app-debug.apk`.

---

## Fitur Native Android yang Aktif Saat Diinstal sebagai APK
* **Home Launcher Resmi**: Saat menekan tombol *Home* di headunit, Android akan menanyakan apakah Anda ingin menjadikan **PangDash Launcher** sebagai layar utama (*Default Home App*).
* **Kontrol Brightness Headunit Asli**: Menggeser bar kecerahan di pojok kanan atas langsung mengubah kecerahan layar sistem Android headunit (`WindowManager` & `Settings.System.SCREEN_BRIGHTNESS`).
* **Kontrol Volume Audio Asli**: Menggeser bar volume di atas Pintasan Cepat langsung mengatur `AudioManager.STREAM_MUSIC` pada headunit.
* **Peluncur Aplikasi Native (App Drawer)**: Menekan ikon aplikasi pada kategori **Semua, Navigasi, Media, Sistem, Mobil, Lainnya** langsung membuka aplikasi Android yang terinstal di headunit (Google Maps, Waze, Spotify, YouTube, Netflix, DSP EQ, dll.).
* **GPS Speedometer Real-Time**: Membaca kecepatan langsung dari chip satelit GPS headunit (`LocationManager.GPS_PROVIDER`).
