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

## Konfigurasi Sync Terenkripsi (Ably, opsional)

PangDash dan PangLink memakai **PDSync v1** untuk isi pesan (topik turunan SHA-256, AES-GCM, nonce anti-replay). Transport default adalah **ntfy.sh**. Agar kuota lebih besar dan tidak ada jeda karena inactivity, gunakan **Ably**:

1. Buat aplikasi di [Ably](https://ably.com/), lalu buat **API key** dengan capability `publish`, `subscribe`, dan `history` hanya untuk resource `pangdash:*`. Jangan memakai kunci full-access.
2. Buat **rule** dengan match expression `pangdash:*`, aktifkan **Persist last message** agar `rewind=1` masih mendapat snapshot terakhir, dan **jangan centang Identified**. Aplikasi memakai basic auth API key (bukan token auth). Sebagai kompatibilitas, transport mengklaim `clientId` acak per sesi lewat header `X-Ably-ClientId` (REST) dan parameter `clientId` (SSE), sehingga rule lama yang masih mewajibkan Identified tidak langsung memblokir. Tetap disarankan mematikan Identified pada `pangdash:*`; jika ada rule legacy bernama `pangdash`, hapus atau matikan Identified-nya karena rule legacy menang atas match expression `pangdash:*`. Tanpa rule ini, history default hanya 2 menit.
3. Simpan kunci sebagai secret GitHub `ABLY_API_KEY` di **kedua repositori** (headunit dan PangLink). Workflow akan meneruskannya ke Gradle. Kunci yang sama boleh dipakai kedua aplikasi karena channel dipisahkan per mobil.
4. Untuk build lokal:
   ```bat
   gradlew.bat assembleRelease -PablyKey="ISI_KUNCI_ABLY"
   ```
   atau set environment `ABLY_API_KEY` sebelum build.
5. Jika `ABLY_API_KEY` kosong, build otomatis memakai **ntfy** sebagai cadangan. Aplikasi tidak gagal total tanpa kunci; status di UI akan menunjukkan backend sync yang dipakai. Untuk memaksa ntfy walaupun kunci tersedia, tambahkan `-PsyncProvider=ntfy`.

Catatan keamanan: kunci Ably yang dibundel ke APK bisa diekstrak dari aplikasi. Karena itu kunci **wajib dibatasi** ke capability dan resource di atas, dan harus dirotasi jika bocor. Isi pesan tetap terenkripsi AES-GCM; kunci Ably hanya memberi akses transport ke channel `pangdash:*`.

Status uji live 13 Nov 2026: **11/11 PASS** untuk app `tNDrjg` — publish state/servis, history REST, `fetchRecent`, SSE `rewind=1`, live publish→subscribe, dan **Persist last message** setelah >2 menit.

---

## Fitur Native Android yang Aktif Saat Diinstal sebagai APK
* **Home Launcher Resmi**: Saat menekan tombol *Home* di headunit, Android akan menanyakan apakah Anda ingin menjadikan **PangDash Launcher** sebagai layar utama (*Default Home App*).
* **Kontrol Brightness Headunit Asli**: Menggeser bar kecerahan di pojok kanan atas langsung mengubah kecerahan layar sistem Android headunit (`WindowManager` & `Settings.System.SCREEN_BRIGHTNESS`).
* **Kontrol Volume Audio Asli**: Menggeser bar volume di atas Pintasan Cepat langsung mengatur `AudioManager.STREAM_MUSIC` pada headunit.
* **Peluncur Aplikasi Native (App Drawer)**: Menekan ikon aplikasi pada kategori **Semua, Navigasi, Media, Sistem, Mobil, Lainnya** langsung membuka aplikasi Android yang terinstal di headunit (Google Maps, Waze, Spotify, YouTube, Netflix, DSP EQ, dll.).
* **GPS Speedometer Real-Time**: Membaca kecepatan langsung dari chip satelit GPS headunit (`LocationManager.GPS_PROVIDER`).

---

## Jika PangDash Selalu Kembali ke Launcher Bawaan

Di sebagian headunit China (FYT, Topway, Allwinner T3/T5, MTCD), firmware mengunci launcher stock dan mengembalikannya saat boot. Urutan pengecekan:

1. **Dalam aplikasi**: buka tab **Setelan → Diagnostik**. Panel ini menampilkan launcher yang sedang aktif, daftar aplikasi Home yang terdeteksi, status Role Home, versi Android, dan perintah ADB.
2. **Setelan Android**: `Setelan → Aplikasi → Aplikasi Default → Layar Utama (Home) → PangDash`. Di beberapa unit namanya "Home app" atau "Launcher".
3. **Factory menu**: buka menu tersembunyi headunit dan cari **Default Launcher / Home App / Boot Launcher**, lalu pilih PangDash. Kode umum: FYT `3368`/`8888`, Allwinner T3/T5 `1617`/`16176699`, MTCD `126`, Teyes `123456`/`168`. **Jangan ubah setelan lain** di factory menu.
4. **Lewat ADB** (jika USB debugging aktif):
   ```bat
   adb shell cmd package set-home-activity com.pangdash.launcher/.MainActivity
   ```
   lalu reboot headunit.
5. Jika setelah reboot tetap kembali, firmware memang memaksa launcher stock. Menonaktifkan launcher stock lewat ADB/root bisa membuat PangDash terbuka, tetapi berisiko mematikan kontrol radio/AC yang dimiliki launcher stock. Lakukan hanya jika Anda punya cara memulihkan firmware.

Catatan: PangDash tidak menurunkan keamanan WebView/OTA dan tidak memakai akses root untuk memaksa default launcher.
