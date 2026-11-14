package com.pangdash.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.app.DownloadManager
import android.content.SharedPreferences
import android.database.Cursor
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

class MediaListenerService : NotificationListenerService() {
    companion object {
        var instance: MediaListenerService? = null
        var onMediaChangedCallback: ((title: String, artist: String, pkg: String, playing: Boolean) -> Unit)? = null
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        pollActiveMediaSessions()
    }
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { pollActiveMediaSessions() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { pollActiveMediaSessions() }

    fun getActiveController(): MediaController? {
        return try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
            val comp = ComponentName(this, MediaListenerService::class.java)
            val controllers = msm.getActiveSessions(comp)
            controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    fun pollActiveMediaSessions() {
        try {
            val active = getActiveController() ?: return
            val meta = active.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: "Musik Headunit Aktif"
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: active.packageName
            val isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING
            onMediaChangedCallback?.invoke(title, artist, active.packageName, isPlaying)
        } catch (_: Throwable) {}
    }
}

class MainActivity : Activity(), LocationListener {

    private var webView: WebView? = null
    private var audioManager: AudioManager? = null
    private var locationManager: LocationManager? = null
    private var connectivityManager: ConnectivityManager? = null
    @Volatile private var cachedAppsJson: String? = null
    @Volatile private var cachedAppsMetaJson: String? = null
    private val appIconCache = ConcurrentHashMap<String, String>()
    private var activityCreateElapsed: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val REQ_HOME_ROLE = 2002
    private var lastGpsFixElapsed: Long = 0L
    private var gpsStaleNotified: Boolean = false
    private val gpsWatchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (lastGpsFixElapsed > 0L &&
                    SystemClock.elapsedRealtime() - lastGpsFixElapsed >= 5000L &&
                    !gpsStaleNotified
                ) {
                    gpsStaleNotified = true
                    webView?.evaluateJavascript(
                        "if(window.onNativeGpsStale) window.onNativeGpsStale();",
                        null
                    )
                }
            } catch (_: Throwable) {}
            mainHandler.postDelayed(this, 2000L)
        }
    }
    private var prefs: SharedPreferences? = null
    private var trackedVolumePercent: Int = 50
    private var isFixedStreamHeadunit: Boolean = false
    private var mcuMaxVolume: Int = 30
    private var discoveredMcuVolumeKey: String? = null
    @Volatile private var isInternalVolumeStepping: Boolean = false
    @Volatile private var lastUserSliderSetTimestamp: Long = 0L
    private val volumeStepExecutor = Executors.newSingleThreadExecutor()

    private var lastPushedVolumePercent: Int = -1
    private var lastPushedBrightnessPercent: Int = -1
    private var hasPromptedWriteSettings: Boolean = false
    private var otaDownloadId: Long = -1L

    
    @Volatile private var pendingOtaApkFile: File? = null
    @Volatile private var isOtaDownloadingNow: Boolean = false
    @Volatile private var otaSessionToken: String? = null

    private val otaDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.pangdash.launcher.OTA_INSTALL_COMMIT") {
                // Token acak per sesi: membuktikan broadcast berasal dari PendingIntent
                // PackageInstaller milik aplikasi ini, bukan dari aplikasi lain.
                val token = intent.getStringExtra("pangdash_ota_token")
                if (token == null || token != otaSessionToken) return

                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmIntent == null) {
                        pushOtaStatusToWeb(0, "ERROR", "Installer tidak mengirim intent konfirmasi.")
                        return
                    }
                    // Intent berasal dari sesi PackageInstaller yang kita buat sendiri.
                    // Beberapa headunit memakai action/komponen non-standar, jadi jangan
                    // memfilter based on action; cukup jalankan dan tangani kegagalannya.
                    try {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(confirmIntent)
                    } catch (e: Throwable) {
                        pushOtaStatusToWeb(0, "ERROR", "Gagal membuka installer: ${e.message}")
                    }
                } else if (status != PackageInstaller.STATUS_SUCCESS) {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Gagal memasang paket"
                    pushOtaStatusToWeb(0, "ERROR", "Instalasi dibatalkan / gagal: $msg")
                }
            }
        }
    }

    private fun pushOtaStatusToWeb(pct: Int, state: String, message: String) {
        val safeMsg = JSONObject.quote(message)
        val safeState = JSONObject.quote(state)
        runOnUiThread {
            webView?.evaluateJavascript(
                "if(window.onNativeOtaProgress) window.onNativeOtaProgress($pct, $safeState, $safeMsg);",
                null
            )
        }
    }

    // Instal File APK langsung menggunakan Android PackageInstaller Session (Bebas Error FileUriExposed & DownloadManager)
    private fun installApkFromFile(apkFile: File) {
        runOnUiThread {
            try {
                if (!apkFile.exists() || apkFile.length() < 50_000L) {
                    pushOtaStatusToWeb(0, "ERROR", "File APK tidak ditemukan atau belum utuh.")
                    return@runOnUiThread
                }

                // 1. Pada Android 8.0+ (API 26+), pastikan izin 'Instal aplikasi tidak dikenal' aktif
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                    pendingOtaApkFile = apkFile
                    pushOtaStatusToWeb(100, "COMPLETE", "Izinkan 'Instal aplikasi tidak dikenal' di layar berikutnya, lalu tekan Kembali.")
                    Toast.makeText(
                        this@MainActivity,
                        "Aktifkan izin 'Instal aplikasi tidak dikenal' untuk PangDash, lalu kembali",
                        Toast.LENGTH_LONG
                    ).show()
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(permIntent)
                    return@runOnUiThread
                }

                pendingOtaApkFile = null

                // 2. Gunakan PackageInstaller Session API standar Android (bekerja di Android 5.0 s.d. Android 14 tanpa perlu FileProvider XML)
                val packageInstaller = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                FileInputStream(apkFile).use { input ->
                    session.openWrite("PangDash_Update.apk", 0, apkFile.length()).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        session.fsync(output)
                    }
                }

                val sessionToken = UUID.randomUUID().toString()
                otaSessionToken = sessionToken
                val callbackIntent = Intent("com.pangdash.launcher.OTA_INSTALL_COMMIT").apply {
                    setPackage(packageName)
                    putExtra("pangdash_ota_token", sessionToken)
                }
                val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(this@MainActivity, sessionId, callbackIntent, pendingFlags)
                session.commit(pendingIntent.intentSender)
                session.close()
            } catch (e: Throwable) {
                pushOtaStatusToWeb(0, "ERROR", "Gagal membuka installer: ${e.message}")
                Toast.makeText(this@MainActivity, "Gagal membuka installer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Hanya izinkan unduhan APK dari GitHub (later redirect ke githubusercontent ditangani native). */
    private fun isAllowedUpdateUrl(rawUrl: String): Boolean {
        return try {
            val url = URL(rawUrl)
            if (url.protocol != "https") return false
            val host = url.host?.lowercase() ?: return false
            if (host != "github.com" && !host.endsWith(".githubusercontent.com")) return false
            (url.path ?: "").lowercase().endsWith(".apk")
        } catch (_: Throwable) {
            false
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun signatureSha256Set(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptySet()
        }
        return signatures.filterNotNull().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }.toSet()
    }

    /** Pastikan APK unduhan ditandatangani dengan sertifikat yang sama seperti aplikasi terpasang. */
    private fun apkSignerMatchesInstalled(apkFile: File): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false
            val installedInfo = packageManager.getPackageInfo(packageName, flags)
            val archiveSigners = signatureSha256Set(archiveInfo)
            val installedSigners = signatureSha256Set(installedInfo)
            archiveSigners.isNotEmpty() && installedSigners.isNotEmpty() && archiveSigners.any { it in installedSigners }
        } catch (_: Throwable) {
            false
        }
    }

    // 1. BroadcastReceiver untuk Metadata Musik
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            try {
                val track = intent.getStringExtra("track")
                    ?: intent.getStringExtra("title")
                    ?: intent.getStringExtra("EXTRA_TRACK")
                    ?: intent.getStringExtra("music_title")
                    ?: intent.getStringExtra("song_name")
                val artist = intent.getStringExtra("artist")
                    ?: intent.getStringExtra("album")
                    ?: intent.getStringExtra("music_artist")
                    ?: intent.getStringExtra("singer_name")
                    ?: ""
                val playing = if (intent.hasExtra("playing")) {
                    intent.getBooleanExtra("playing", true)
                } else if (intent.hasExtra("playstate")) {
                    intent.getBooleanExtra("playstate", true)
                } else {
                    audioManager?.isMusicActive ?: true
                }
                val pkg = intent.action ?: ""
                if (!track.isNullOrBlank()) {
                    pushMediaToWeb(track, artist, pkg, playing)
                } else {
                    pushAudioPlayingState(playing)
                }
            } catch (_: Throwable) {}
        }
    }

    // 2. BroadcastReceiver Khusus Volume MCU, Steer Control (SWC) & Brightness Headunit
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val act = intent.action ?: ""
            if (act.contains("BRIGHTNESS", ignoreCase = true) || act.contains("backlight", ignoreCase = true)) {
                pushCurrentSystemBrightnessToWeb(force = false)
                return
            }
            // Jangan timpa posisi slider jika pengguna baru saja menggeser slider (< 900ms) atau sedang stepping internal
            if (isInternalVolumeStepping || (SystemClock.uptimeMillis() - lastUserSliderSetTimestamp < 900L)) {
                return
            }
            handleIncomingVolumeBroadcast(intent)
        }
    }

    // 3. ContentObserver untuk mendeteksi otomatis perubahan Volume MCU (SWC) & Brightness di Settings.System / Global
    private val settingsVolumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            val key = uri?.lastPathSegment ?: ""
            if (key.contains("brightness", ignoreCase = true) || key.contains("backlight", ignoreCase = true)) {
                pushCurrentSystemBrightnessToWeb(force = false)
                return
            }
            if (isInternalVolumeStepping || (SystemClock.uptimeMillis() - lastUserSliderSetTimestamp < 900L)) {
                return
            }
            if (key.contains("vol", ignoreCase = true) &&
                !key.contains("ring", ignoreCase = true) &&
                !key.contains("alarm", ignoreCase = true) &&
                !key.contains("notif", ignoreCase = true) &&
                !key.contains("voice", ignoreCase = true) &&
                !key.contains("tts", ignoreCase = true)
            ) {
                val rawVal = readSettingIntEverywhere(key, -1)
                if (rawVal >= 0) {
                    val am = audioManager
                    val maxStd = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                    // Abaikan jika ini hanya nilai STREAM_MUSIC standar yang terkunci di 100% (maxStd) pada Headunit
                    if (isFixedStreamHeadunit && rawVal == maxStd && key.contains("volume_music", ignoreCase = true)) {
                        return
                    }
                    discoveredMcuVolumeKey = key
                    prefs?.edit()?.putString("mcu_vol_key", key)?.apply()
                    updateTrackedVolumeFromRawMcu(rawVal, isFromMcuKey = !key.contains("volume_music", ignoreCase = true))
                    pushCurrentSystemVolumeToWeb(force = true)
                    return
                }
            }
            // Fallback jika uri null
            val mcuPct = readMcuHardwareVolumePercent()
            if (mcuPct != null) {
                saveTrackedVolumePercent(mcuPct)
                pushCurrentSystemVolumeToWeb(force = true)
            } else if (!isFixedStreamHeadunit) {
                pushCurrentSystemVolumeToWeb(force = false)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        activityCreateElapsed = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)

        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Keyboard tidak boleh me-resize window. Pada WebView, resize
            // viewport memicu autoScaleHeadunit() mengecilkan seluruh UI.
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
        } catch (_: Throwable) {}

        try {
            prefs = getSharedPreferences("pangdash_hu_prefs", Context.MODE_PRIVATE)
            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            volumeControlStream = AudioManager.STREAM_MUSIC
            initHeadunitVolumeState()
        } catch (_: Throwable) {}

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#070312"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        try {
            val wv = WebView(this)
            wv.setBackgroundColor(Color.parseColor("#070312"))
            wv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            val settings: WebSettings = wv.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // Halaman dimuat dari file:///android_asset. Aset & resource tetap terbaca
            // meski akses filesystem umum dimatikan (lihat dokumentasi WebSettings).
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Headunit) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Jangan pernah mengizinkan konten HTTP di halaman lokal.
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // Renderer WebView bisa dibunuh sistem saat memori rendah.
                    // Jangan biarkan launcher menampilkan layar hitam: restart activity.
                    try { view?.destroy() } catch (_: Throwable) {}
                    if (webView === view) webView = null
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@MainActivity, "Memuat ulang tampilan...", Toast.LENGTH_SHORT).show()
                        mainHandler.postDelayed({
                            if (isFinishing || isDestroyed) return@postDelayed
                            try {
                                val restart = Intent(this@MainActivity, MainActivity::class.java)
                                restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(restart)
                                finish()
                            } catch (_: Throwable) {}
                        }, 700L)
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    try {
                        val elapsed = SystemClock.elapsedRealtime() - activityCreateElapsed
                        Log.d("PangDashPerf", "WebView selesai dimuat dalam ${elapsed} ms")
                    } catch (_: Throwable) {}
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    val scheme = target.scheme?.lowercase() ?: return false
                    // Navigasi internal aset lokal tetap diizinkan.
                    if (scheme == "file" || scheme == "about" || scheme == "data") return false
                    // Tautan eksternal dibuka di browser, bukan di WebView ber-bridge.
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (_: Throwable) {
                        true
                    }
                }
            }
            wv.webChromeClient = WebChromeClient()
            wv.addJavascriptInterface(HeadunitBridge(this), "AndroidBridge")

            container.addView(wv)
            webView = wv
            setContentView(container)

            wv.loadUrl("file:///android_asset/index.html")
        } catch (t: Throwable) {
            setContentView(container)
            Toast.makeText(this, "WebView error: ${t.message}", Toast.LENGTH_LONG).show()
        }

        MediaListenerService.onMediaChangedCallback = { title, artist, pkg, playing ->
            pushMediaToWeb(title, artist, pkg, playing)
        }

        registerMediaBroadcasts()
        registerVolumeSyncWatchers()
        registerAudioPlaybackWatcher()
        registerAutoNetworkSync()
        registerOtaDownloadReceiver()

        window?.decorView?.post {
            hideSystemUI()
            requestGpsPermissionsSafely()
            pushCurrentSystemVolumeToWeb(force = true)
            pushCurrentSystemBrightnessToWeb(force = true)
            MediaListenerService.instance?.pollActiveMediaSessions()
        }
    }

    // Hasil audit receiver (b152, P2-3): NOT_EXPORTED sudah dipakai di Android 13+.
    // Cabang else (Android 12 ke bawah) memakai overload 2-argumen karena konstanta
    // RECEIVER_* baru ada di API 33; lint menandainya false positive, jadi
    // disembunyikan terarah di sini. Broadcast-nya private (setPackage) dan token
    // sesinya acak, sehingga tidak bisa dipalsukan aplikasi lain.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerOtaDownloadReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(otaDownloadReceiver, IntentFilter("com.pangdash.launcher.OTA_INSTALL_COMMIT"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(otaDownloadReceiver, IntentFilter("com.pangdash.launcher.OTA_INSTALL_COMMIT"))
        }
    }

    private fun initHeadunitVolumeState() {
        val p = prefs ?: return
        trackedVolumePercent = p.getInt("tracked_vol_pct", 50).coerceIn(0, 100)
        isFixedStreamHeadunit = p.getBoolean("is_fixed_stream_hu", false)
        mcuMaxVolume = p.getInt("mcu_max_vol", 30).coerceIn(15, 100)
        discoveredMcuVolumeKey = p.getString("mcu_vol_key", null)

        val sysMax = readSettingIntEverywhere("av_maxvolume", -1)
        if (sysMax in 15..100) {
            mcuMaxVolume = sysMax
            p.edit().putInt("mcu_max_vol", sysMax).apply()
        }

        // Cek apakah ada nilai volume MCU langsung saat startup
        val mcuPct = readMcuHardwareVolumePercent()
        if (mcuPct != null) {
            isFixedStreamHeadunit = true
            saveTrackedVolumePercent(mcuPct)
        } else {
            val am = audioManager
            if (am != null) {
                val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val curV = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                // Jika STREAM_MUSIC tidak mentok di 100%, baca nilai aslinya (misal di HP atau Headunit non-fixed)
                if (curV < maxV) {
                    val stdPct = ((curV * 100.0) / maxV).roundToInt().coerceIn(0, 100)
                    saveTrackedVolumePercent(stdPct)
                } else {
                    // Jika STREAM_MUSIC mentok di maxV (100%), tes apakah dikunci oleh ROM Headunit
                    isFixedStreamHeadunit = true
                    p.edit().putBoolean("is_fixed_stream_hu", true).apply()
                }
            }
        }
    }

    private fun saveTrackedVolumePercent(pct: Int) {
        val clamped = pct.coerceIn(0, 100)
        trackedVolumePercent = clamped
        prefs?.edit()?.putInt("tracked_vol_pct", clamped)?.apply()
    }

    private fun readSettingIntEverywhere(key: String, defaultVal: Int): Int {
        if (key.isBlank()) return defaultVal
        try {
            val vSys = Settings.System.getInt(contentResolver, key, -9999)
            if (vSys != -9999) return vSys
        } catch (_: Throwable) {}
        try {
            val vGlob = Settings.Global.getInt(contentResolver, key, -9999)
            if (vGlob != -9999) return vGlob
        } catch (_: Throwable) {}
        return defaultVal
    }

    private fun updateTrackedVolumeFromRawMcu(rawVal: Int, isFromMcuKey: Boolean) {
        if (rawVal < 0) return
        val am = audioManager
        val stdMax = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        if (isFromMcuKey || isFixedStreamHeadunit || rawVal > stdMax) {
            isFixedStreamHeadunit = true
            prefs?.edit()?.putBoolean("is_fixed_stream_hu", true)?.apply()
            if (rawVal > mcuMaxVolume && rawVal <= 100) {
                mcuMaxVolume = if (rawVal <= 36) 36 else if (rawVal <= 40) 40 else 100
                prefs?.edit()?.putInt("mcu_max_vol", mcuMaxVolume)?.apply()
            }
            val pct = ((rawVal * 100.0) / mcuMaxVolume.coerceAtLeast(1)).roundToInt().coerceIn(0, 100)
            saveTrackedVolumePercent(pct)
        } else {
            val pct = ((rawVal * 100.0) / stdMax.coerceAtLeast(1)).roundToInt().coerceIn(0, 100)
            saveTrackedVolumePercent(pct)
        }
    }

    private fun readMcuHardwareVolumePercent(): Int? {
        try {
            // 1. Cek parameter AudioManager av_volume (Microntek / HCT / Rockchip Headunit)
            val am = audioManager
            if (am != null) {
                val paramStr = am.getParameters("av_volume=") ?: ""
                val cleanNum = paramStr.replace("av_volume=", "").trim().toIntOrNull()
                if (cleanNum != null && cleanNum in 0..100) {
                    if (cleanNum > mcuMaxVolume) mcuMaxVolume = if (cleanNum <= 40) 40 else 100
                    return ((cleanNum * 100.0) / mcuMaxVolume).roundToInt().coerceIn(0, 100)
                }
            }
            // 2. Cek key Settings.System / Global yang ditemukan otomatis saat Steer Control ditekan
            val candidateKeys = mutableListOf<String>()
            discoveredMcuVolumeKey?.let { candidateKeys.add(it) }
            candidateKeys.addAll(
                listOf("av_volume", "mcu_volume", "car_volume", "sys_volume", "tw_volume", "fyt_volume", "xy_volume")
            )
            for (k in candidateKeys) {
                val v = readSettingIntEverywhere(k, -1)
                if (v in 0..100) {
                    if (v > mcuMaxVolume) mcuMaxVolume = if (v <= 40) 40 else 100
                    return ((v * 100.0) / mcuMaxVolume).roundToInt().coerceIn(0, 100)
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun handleIncomingVolumeBroadcast(intent: Intent) {
        try {
            val act = intent.action ?: ""
            val extras = intent.extras
            // 1. Cek extra eksplisit dari broadcast MCU Headunit (Microntek, SYU/FYT, Topway, TS10, XYAuto)
            if (extras != null) {
                val mcuKeys = listOf("volume", "vol", "cur_volume", "mcu_vol", "car_vol")
                for (k in mcuKeys) {
                    if (extras.containsKey(k)) {
                        val v = intent.getIntExtra(k, -1)
                        if (v in 0..100) {
                            updateTrackedVolumeFromRawMcu(v, isFromMcuKey = true)
                            pushCurrentSystemVolumeToWeb(force = true)
                            return
                        }
                    }
                }
            }

            // 2. Cek VOLUME_CHANGED_ACTION / MASTER_VOLUME_CHANGED_ACTION
            if (act == "android.media.VOLUME_CHANGED_ACTION" || act == "android.media.MASTER_VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                val am = audioManager
                val maxStd = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15

                if (newVol >= 0) {
                    // Jika Headunit mengirim nilai > maxStd (misal 0..30) di dalam EXTRA_VOLUME_STREAM_VALUE
                    if (newVol > maxStd) {
                        updateTrackedVolumeFromRawMcu(newVol, isFromMcuKey = true)
                        pushCurrentSystemVolumeToWeb(force = true)
                        return
                    }
                    // Jika STREAM_MUSIC tidak terkunci di maxStd (berubah nyata), perbarui
                    if (streamType == AudioManager.STREAM_MUSIC && (newVol < maxStd || (prevVol in 0 until maxStd))) {
                        val pct = ((newVol * 100.0) / maxStd.coerceAtLeast(1)).roundToInt().coerceIn(0, 100)
                        saveTrackedVolumePercent(pct)
                        pushCurrentSystemVolumeToWeb(force = true)
                        return
                    }
                }
            }

            // 3. Cek apakah nilai MCU di Settings.System berubah
            val mcuPct = readMcuHardwareVolumePercent()
            if (mcuPct != null) {
                saveTrackedVolumePercent(mcuPct)
                pushCurrentSystemVolumeToWeb(force = true)
            }
        } catch (_: Throwable) {}
    }

    // Tangkap langsung tombol Steer Control (SWC) Volume & Media ketika Launcher sedang tampil di layar
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (!isInternalVolumeStepping && event.action == KeyEvent.ACTION_DOWN) {
                    val mcuPct = readMcuHardwareVolumePercent()
                    if (mcuPct != null) {
                        saveTrackedVolumePercent(mcuPct)
                    } else if (isFixedStreamHeadunit) {
                        val stepPct = (100.0 / mcuMaxVolume.coerceAtLeast(1)).roundToInt().coerceAtLeast(3)
                        val updated = when (keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> (trackedVolumePercent + stepPct).coerceIn(0, 100)
                            KeyEvent.KEYCODE_VOLUME_DOWN -> (trackedVolumePercent - stepPct).coerceIn(0, 100)
                            KeyEvent.KEYCODE_VOLUME_MUTE -> if (trackedVolumePercent > 0) 0 else 50
                            else -> trackedVolumePercent
                        }
                        saveTrackedVolumePercent(updated)
                    }
                    mainHandler.postDelayed({ pushCurrentSystemVolumeToWeb(force = true) }, 15)
                    mainHandler.postDelayed({ pushCurrentSystemVolumeToWeb(force = true) }, 140)
                }
                return super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Hasil audit receiver (b152, P2-3): receiver ini WAJIB RECEIVER_EXPORTED.
    // Filter-nya mengandung broadcast firmware headunit dari aplikasi lain
    // (com.microntek.*, com.syu.*, com.tw.volume, com.ts.main.volume,
    // com.xyauto.volume, VOLUME_CHANGED_ACTION sistem) yang dikirim lintas UID;
    // RECEIVER_NOT_EXPORTED akan memutus sinkronisasi slider volume/brightness
    // di headunit sungguhan. Receiver ini hanya membaca extra untuk update UI
    // (try/catch, abaikan action tak dikenal), tidak memicu OTA/instalasi,
    // sehingga risikonya rendah dan flag EXPORTED sengaja dipertahankan.
    // Suppress lint: flag sudah eksplisit di cabang Android 13+; konstanta
    // RECEIVER_* tidak tersedia untuk cabang Android 12 ke bawah.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerVolumeSyncWatchers() {
        try {
            val volFilter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
                addAction("android.media.MASTER_MUTE_CHANGED_ACTION")
                addAction("android.media.RINGER_MODE_CHANGED")
                // Broadcast khusus berbagai merek Headunit Android (SYU / FYT / Microntek / Topway / TS10 / XYAuto)
                addAction("com.microntek.volume")
                addAction("com.microntek.backlight")
                addAction("com.syu.ms.vol")
                addAction("com.syu.ms.brightness")
                addAction("com.syu.ms")
                addAction("com.tw.volume")
                addAction("com.ts.main.volume")
                addAction("com.xyauto.volume")
                addAction("android.intent.action.VOLUME_CHANGED")
                addAction("android.intent.action.SCREEN_BRIGHTNESS_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(volumeReceiver, volFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(volumeReceiver, volFilter)
            }
        } catch (_: Throwable) {}

        try {
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                settingsVolumeObserver
            )
            contentResolver.registerContentObserver(
                Settings.Global.CONTENT_URI,
                true,
                settingsVolumeObserver
            )
        } catch (_: Throwable) {}
    }

    private fun readCurrentSystemVolumePercent(): Int {
        return try {
            val am = audioManager ?: return trackedVolumePercent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                return 0
            }
            // 1. Prioritaskan nilai volume MCU Headunit jika tersedia
            val mcuPct = readMcuHardwareVolumePercent()
            if (mcuPct != null) {
                saveTrackedVolumePercent(mcuPct)
                return mcuPct
            }
            // 2. Jika Headunit mengunci STREAM_MUSIC di 100% (Fixed Stream ROM), JANGAN kembalikan 100% palsu!
            // Kembalikan trackedVolumePercent agar slider tidak pernah mental balik ke 100%!
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (isFixedStreamHeadunit || curVol >= maxVol) {
                return trackedVolumePercent
            }
            val stdPct = ((curVol * 100.0) / maxVol).roundToInt().coerceIn(0, 100)
            saveTrackedVolumePercent(stdPct)
            stdPct
        } catch (_: Throwable) {
            trackedVolumePercent
        }
    }

    private fun pushCurrentSystemVolumeToWeb(force: Boolean) {
        try {
            val pct = readCurrentSystemVolumePercent()
            if (!force && pct == lastPushedVolumePercent) return
            lastPushedVolumePercent = pct
            runOnUiThread {
                webView?.evaluateJavascript(
                    "if(window.onNativeVolumeChanged) window.onNativeVolumeChanged($pct);",
                    null
                )
            }
        } catch (_: Throwable) {}
    }

    private fun writeMcuHardwareVolume(targetPercent: Int) {
        val mcuVol = ((targetPercent.coerceIn(0, 100) / 100.0) * mcuMaxVolume).roundToInt().coerceIn(0, mcuMaxVolume)
        try {
            audioManager?.setParameters("av_volume=$mcuVol")
        } catch (_: Throwable) {}

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)) {
                discoveredMcuVolumeKey?.let { key ->
                    try { Settings.System.putInt(contentResolver, key, mcuVol) } catch (_: Throwable) {}
                }
                val existingAv = readSettingIntEverywhere("av_volume", -1)
                if (existingAv >= 0) {
                    try { Settings.System.putInt(contentResolver, "av_volume", mcuVol) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        try {
            sendBroadcast(Intent("com.microntek.setvolume").putExtra("volume", mcuVol))
            sendBroadcast(Intent("com.syu.ms.vol").putExtra("vol", mcuVol))
            sendBroadcast(Intent("com.tw.volume").putExtra("volume", mcuVol))
        } catch (_: Throwable) {}
    }

    private fun readCurrentSystemBrightnessPercent(): Int {
        return try {
            val sysVal = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 230)
            ((sysVal.coerceIn(0, 255) / 255.0) * 100.0).roundToInt().coerceIn(5, 100)
        } catch (_: Throwable) {
            90
        }
    }

    private fun pushCurrentSystemBrightnessToWeb(force: Boolean) {
        try {
            val pct = readCurrentSystemBrightnessPercent()
            if (!force && pct == lastPushedBrightnessPercent) return
            lastPushedBrightnessPercent = pct
            runOnUiThread {
                webView?.evaluateJavascript(
                    "if(window.onNativeBrightnessChanged) window.onNativeBrightnessChanged($pct);",
                    null
                )
            }
        } catch (_: Throwable) {}
    }

    private fun registerAudioPlaybackWatcher() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager?.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        super.onPlaybackConfigChanged(configs)
                        val active = audioManager?.isMusicActive ?: false
                        pushAudioPlayingState(active)
                        mainHandler.postDelayed({
                            pushAudioPlayingState(audioManager?.isMusicActive ?: false)
                        }, 350)
                    }
                }, mainHandler)
            }
        } catch (_: Throwable) {}
    }

    private fun pushAudioPlayingState(playing: Boolean) {
        runOnUiThread {
            webView?.evaluateJavascript(
                "if(window.onNativeAudioStateChanged) window.onNativeAudioStateChanged($playing);",
                null
            )
        }
    }

    private fun pushMediaToWeb(title: String, artist: String, pkg: String, playing: Boolean) {
        val safeTitle = JSONObject.quote(title)
        val safeArtist = JSONObject.quote(artist)
        val safePkg = JSONObject.quote(pkg)
        runOnUiThread {
            webView?.evaluateJavascript(
                "if(window.onNativeMediaUpdate) window.onNativeMediaUpdate($safeTitle, $safeArtist, $safePkg, $playing);",
                null
            )
        }
    }

    // Hasil audit receiver (b152, P2-3): receiver ini WAJIB RECEIVER_EXPORTED.
    // Broadcast metadata musik (com.android.music.*, Spotify, player vendor
    // headunit) dikirim oleh aplikasi musik pihak ketiga lintas UID;
    // RECEIVER_NOT_EXPORTED akan memutus judul lagu di dock. Receiver ini hanya
    // meneruskan teks judul/artis ke WebView (di-escape) tanpa memproses Intent
    // arbitrer, sehingga risikonya rendah dan flag EXPORTED sengaja dipertahankan.
    // Suppress lint: flag sudah eksplisit di cabang Android 13+; konstanta
    // RECEIVER_* tidak tersedia untuk cabang Android 12 ke bawah.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerMediaBroadcasts() {
        try {
            val filter = IntentFilter().apply {
                addAction("com.android.music.metachanged")
                addAction("com.android.music.playstatechanged")
                addAction("com.android.music.playbackcomplete")
                addAction("com.spotify.music.metadatachanged")
                addAction("com.spotify.music.playbackstatechanged")
                addAction("com.sec.android.app.music.metachanged")
                addAction("com.miui.player.metachanged")
                addAction("com.htc.music.metachanged")
                addAction("fm.last.android.metachanged")
                addAction("com.nullsoft.winamp.metachanged")
                addAction("com.amazon.mp3.metachanged")
                addAction("com.real.IMP.metachanged")
                addAction("com.sonyericsson.music.metachanged")
                addAction("com.rdio.android.metachanged")
                addAction("com.samsung.sec.android.MusicPlayer.metachanged")
                addAction("com.andrew.apollo.metachanged")
                addAction("com.maxmpz.audioplayer.TRACK_CHANGED")
                addAction("com.syu.music.metachanged")
                addAction("com.syu.bt.metachanged")
                addAction("com.microntek.music.metachanged")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mediaReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(mediaReceiver, filter)
            }
        } catch (_: Throwable) {}
    }

    private fun registerAutoNetworkSync() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runOnUiThread {
                            webView?.evaluateJavascript("if(window.onNativeNetworkChange) window.onNativeNetworkChange(true);", null)
                        }
                    }
                    override fun onLost(network: Network) {
                        runOnUiThread {
                            webView?.evaluateJavascript("if(window.onNativeNetworkChange) window.onNativeNetworkChange(false);", null)
                        }
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    override fun onPause() {
        super.onPause()
        runOnUiThread {
            webView?.evaluateJavascript("if(window.onNativeHeadunitSleep) window.onNativeHeadunitSleep();", null)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        hideSystemUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME_ROLE) {
            mainHandler.postDelayed({
                webView?.evaluateJavascript("if(window.onNativeResumeCheck) window.onNativeResumeCheck();", null)
                val msg = if (resultCode == Activity.RESULT_OK) {
                    "PangDash diizinkan menjadi Layar Utama"
                } else {
                    "Izin Layar Utama belum diberikan"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        try {
            val canUseLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (canUseLocation) startGpsTrackingSafely()
        } catch (_: Throwable) {}
        val fileToInstall = pendingOtaApkFile
        if (fileToInstall != null && fileToInstall.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            pendingOtaApkFile = null
            mainHandler.postDelayed({ installApkFromFile(fileToInstall) }, 350)
        }
        val playing = audioManager?.isMusicActive ?: false
        pushAudioPlayingState(playing)
        pushCurrentSystemVolumeToWeb(force = true)
        pushCurrentSystemBrightnessToWeb(force = true)
        runOnUiThread {
            webView?.evaluateJavascript("if(window.onNativeResumeCheck) window.onNativeResumeCheck();", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mainHandler.removeCallbacks(gpsWatchdogRunnable) } catch (_: Throwable) {}
        try { locationManager?.removeUpdates(this) } catch (_: Throwable) {}
        try { unregisterReceiver(otaDownloadReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(mediaReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(volumeReceiver) } catch (_: Throwable) {}
        try { contentResolver.unregisterContentObserver(settingsVolumeObserver) } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
            pushAudioPlayingState(audioManager?.isMusicActive ?: false)
            pushCurrentSystemVolumeToWeb(force = false)
            pushCurrentSystemBrightnessToWeb(force = false)
            MediaListenerService.instance?.pollActiveMediaSessions()
        }
    }

    private fun requestGpsPermissionsSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!fine && !coarse) {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        1001
                    )
                } else {
                    startGpsTrackingSafely()
                }
            } else {
                startGpsTrackingSafely()
            }
        } catch (_: Throwable) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (requestCode == 1001 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startGpsTrackingSafely()
            }
        } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun startGpsTrackingSafely() {
        try {
            val lm = locationManager ?: return
            try { lm.removeUpdates(this) } catch (_: Throwable) {}
            lastGpsFixElapsed = SystemClock.elapsedRealtime()
            gpsStaleNotified = false
            mainHandler.removeCallbacks(gpsWatchdogRunnable)
            mainHandler.postDelayed(gpsWatchdogRunnable, 2000L)
            // Jangan gabungkan GPS dan NETWORK pada listener yang sama. Fix
            // network yang kasar/tertinggal dapat bergantian dengan GPS dan
            // membuat jarak melonjak serta rute bolak-balik.
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                lm.requestLocationUpdates(provider, if (provider == LocationManager.GPS_PROVIDER) 1000L else 3000L, 0f, this)
            }
            // Tidak memasukkan last-known location: titik tersebut bisa berasal
            // dari perjalanan lama dan menggambar garis balik ke posisi awal.
        } catch (_: Throwable) {}
    }

    override fun onLocationChanged(location: Location) {
        try {
            val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
            val fixElapsedNanos = location.elapsedRealtimeNanos
            if (fixElapsedNanos <= 0L || fixElapsedNanos > nowElapsedNanos) return
            val fixAgeMs = (nowElapsedNanos - fixElapsedNanos) / 1_000_000L
            // Jangan teruskan cache/fix yang sudah tertinggal. Watchdog juga
            // hanya diperbarui oleh fix baru, bukan callback lama dari provider.
            if (fixAgeMs > 15_000L) return
            lastGpsFixElapsed = SystemClock.elapsedRealtime()
            gpsStaleNotified = false
            val hasSpeed = location.hasSpeed()
            val speedKmh = if (hasSpeed) location.speed * 3.6f else 0f
            val lat = location.latitude
            val lng = location.longitude
            val bearing = if (location.hasBearing()) location.bearing else 0f
            val accuracy = if (location.hasAccuracy()) location.accuracy else 9999f
            val isGpsProvider = location.provider == LocationManager.GPS_PROVIDER
            val fixElapsedMs = fixElapsedNanos / 1_000_000.0
            runOnUiThread {
                webView?.evaluateJavascript(
                    "if(window.onNativeGpsUpdate) window.onNativeGpsUpdate($speedKmh, $lat, $lng, $bearing, $accuracy, $isGpsProvider, $fixElapsedMs, $fixAgeMs, $hasSpeed);",
                    null
                )
            }
        } catch (_: Throwable) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER || provider == LocationManager.NETWORK_PROVIDER) {
            mainHandler.post { startGpsTrackingSafely() }
        }
    }
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER || provider == LocationManager.NETWORK_PROVIDER) {
            mainHandler.post { startGpsTrackingSafely() }
        }
    }

    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window?.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } catch (_: Throwable) {}
    }

    private fun drawableToBase64(drawable: Drawable): String {
        return try {
            val size = 48
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
            "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun categorizeApp(pkg: String, label: String, appInfo: ApplicationInfo?): String {
        val p = pkg.lowercase()
        val l = label.lowercase()

        if (p.contains("maps") || p.contains("navi") || p.contains("waze") || p.contains("sygic") ||
            p.contains("here") || p.contains("gps") || l.contains("maps") || l.contains("navigasi") || l.contains("navi")) {
            return "Navigasi"
        }
        if (p.contains("spotify") || p.contains("music") || p.contains("bitchord") || p.contains("youtube") || p.contains("netflix") ||
            p.contains("disney") || p.contains("radio") || p.contains("video") || p.contains("gallery") ||
            p.contains("galeri") || p.contains("vlc") || p.contains("player") || p.contains("audio") ||
            l.contains("musik") || l.contains("music") || l.contains("radio") || l.contains("video") || l.contains("galeri")) {
            return "Media"
        }
        if (p.contains("car") || p.contains("dvr") || p.contains("camera") || p.contains("kamera") ||
            p.contains("aux") || p.contains("obd") || p.contains("tpms") || p.contains("zlink") ||
            p.contains("tlink") || p.contains("autokit") || p.contains("headunit") ||
            l.contains("mobil") || l.contains("car") || l.contains("kamera") || l.contains("dvr") || l.contains("aux")) {
            return "Mobil"
        }
        if (p.contains("settings") || p.contains("setelan") || p.contains("chrome") || p.contains("browser") ||
            p.contains("file") || p.contains("documentsui") || p.contains("bluetooth") || p.contains("eq") ||
            p.contains("musicfx") || p.contains("dsp") || p.contains("vending") || p.contains("calculator") ||
            l.contains("setelan") || l.contains("settings") || l.contains("file") || l.contains("chrome") || l.contains("eq")) {
            return "Sistem"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo != null) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_MAPS -> return "Navigasi"
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return "Media"
            }
        }
        return "Lainnya"
    }

    // Helper: Buat pasangan KeyEvent DOWN & UP standar sistem
    private fun buildSystemMediaKeyEvents(keyCode: Int): Pair<KeyEvent, KeyEvent> {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now,
            KeyEvent.ACTION_DOWN,
            keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            InputDevice.SOURCE_KEYBOARD
        )
        val up = KeyEvent(
            now, now + 10,
            KeyEvent.ACTION_UP,
            keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            InputDevice.SOURCE_KEYBOARD
        )
        return Pair(down, up)
    }

    // Helper: Kirim Intent ACTION_MEDIA_BUTTON langsung ke MediaButtonReceiver milik aplikasi musik target
    private fun dispatchTargetedMediaButtonBroadcast(keyCode: Int, targetPkg: String?) {
        try {
            val pm = packageManager
            val queryIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            if (!targetPkg.isNullOrBlank()) {
                queryIntent.setPackage(targetPkg)
            }
            val receivers = pm.queryBroadcastReceivers(queryIntent, 0)
            val (downEvent, upEvent) = buildSystemMediaKeyEvents(keyCode)

            if (receivers.isNotEmpty()) {
                for (ri in receivers) {
                    val ai = ri.activityInfo ?: continue
                    val comp = ComponentName(ai.packageName, ai.name)
                    val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                        component = comp
                        putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                        component = comp
                        putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    sendBroadcast(downIntent)
                    sendBroadcast(upIntent)
                }
            } else if (!targetPkg.isNullOrBlank()) {
                val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setPackage(targetPkg)
                    putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                }
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setPackage(targetPkg)
                    putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                }
                sendBroadcast(downIntent)
                sendBroadcast(upIntent)
            }
        } catch (_: Throwable) {}
    }

    // Helper: Kirim command broadcast ke aplikasi musik bawaan Android & Headunit (SYU/FYT/Topway/AOSP)
    private fun dispatchLegacyMusicServiceCommand(action: String, wasPlaying: Boolean, targetPkg: String?) {
        try {
            val cmd = when (action) {
                "next" -> "next"
                "prev" -> "previous"
                "play_pause" -> if (wasPlaying) "pause" else "play"
                else -> "togglepause"
            }
            val serviceIntent = Intent("com.android.music.musicservicecommand").apply {
                putExtra("command", cmd)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            sendBroadcast(serviceIntent)

            if (!targetPkg.isNullOrBlank()) {
                val targetedServiceIntent = Intent("com.android.music.musicservicecommand").apply {
                    setPackage(targetPkg)
                    putExtra("command", cmd)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                sendBroadcast(targetedServiceIntent)
            }
        } catch (_: Throwable) {}
    }

    inner class HeadunitBridge(private val context: Context) {

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                pInfo.versionName ?: "1.0"
            } catch (e: Exception) {
                "1.0"
            }
        }

        @JavascriptInterface
        fun getAppVersionCode(): Int {
            return try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (e: Exception) {
                100
            }
        }

        @JavascriptInterface
        fun getSyncConfigJson(): String {
            return try {
                val key = BuildConfig.ABLY_API_KEY
                val configured = BuildConfig.SYNC_PROVIDER.ifBlank { "ntfy" }.lowercase()
                val provider = if (configured == "ably" && key.isBlank()) "ntfy" else configured
                val ably = org.json.JSONObject()
                ably.put("key", key)
                ably.put("namespace", "pangdash")
                val root = org.json.JSONObject()
                root.put("provider", provider)
                root.put("ably", ably)
                root.toString()
            } catch (_: Throwable) {
                "{\"provider\":\"ntfy\"}"
            }
        }

        @JavascriptInterface
        fun startUpdateDownload(apkUrl: String) {
            startUpdateDownloadWithHash(apkUrl, "")
        }

        @JavascriptInterface
        fun startUpdateDownloadWithHash(apkUrl: String, expectedSha256: String) {
            if (isOtaDownloadingNow) return
            if (!isAllowedUpdateUrl(apkUrl)) {
                pushOtaStatusToWeb(0, "ERROR", "Alamat unduhan APK tidak diizinkan.")
                return
            }

            isOtaDownloadingNow = true
            pushOtaStatusToWeb(4, "DOWNLOADING", "Menghubungi server GitHub Release...")

            Thread {
                try {
                    val outDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                    if (!outDir.exists()) outDir.mkdirs()
                    val targetFile = File(outDir, "PangDash_Update.apk")
                    if (targetFile.exists()) targetFile.delete()

                    // Ikuti hingga 6 kali HTTP 301/302/307/308 Redirect (GitHub Releases -> objects.githubusercontent.com)
                    var currentUrl = apkUrl
                    var connection: HttpURLConnection? = null
                    var redirectCount = 0

                    while (redirectCount < 6) {
                        val urlObj = URL(currentUrl)
                        val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20000
                            readTimeout = 30000
                            instanceFollowRedirects = true
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; PangDash-OTA) AppleWebKit/537.36")
                            setRequestProperty("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")
                        }
                        conn.connect()
                        val code = conn.responseCode
                        if (code in listOf(
                                HttpURLConnection.HTTP_MOVED_PERM,
                                HttpURLConnection.HTTP_MOVED_TEMP,
                                HttpURLConnection.HTTP_SEE_OTHER,
                                307,
                                308
                            )
                        ) {
                            val nextLoc = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (nextLoc.isNullOrBlank()) break
                            currentUrl = nextLoc
                            redirectCount++
                        } else {
                            connection = conn
                            break
                        }
                    }

                    val finalConn = connection ?: throw Exception("Tidak dapat terhubung ke server unduhan")
                    if (finalConn.responseCode != HttpURLConnection.HTTP_OK) {
                        val errCode = finalConn.responseCode
                        finalConn.disconnect()
                        throw Exception("Server mengembalikan status HTTP $errCode (Pastikan repositori publik & rilis APK tersedia)")
                    }

                    val totalBytes = finalConn.contentLengthLong
                    var downloadedBytes = 0L
                    var lastReportTime = 0L

                    finalConn.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(32768)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val now = SystemClock.uptimeMillis()
                                if (now - lastReportTime > 180L) {
                                    lastReportTime = now
                                    val pct = if (totalBytes > 0) {
                                        ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(5, 99)
                                    } else {
                                        50
                                    }
                                    val mbDown = String.format("%.2f", downloadedBytes / (1024.0 * 1024.0))
                                    val mbTot = if (totalBytes > 0) String.format(" / %.2f MB", totalBytes / (1024.0 * 1024.0)) else " MB"
                                    pushOtaStatusToWeb(pct, "DOWNLOADING", "Mengunduh PangDash APK... ($pct% • $mbDown$mbTot)")
                                }
                            }
                            output.flush()
                        }
                    }
                    finalConn.disconnect()

                    if (!targetFile.exists() || targetFile.length() < 50_000L) {
                        throw Exception("File APK hasil unduhan tidak lengkap (${targetFile.length()} bytes)")
                    }

                    val expectedHash = expectedSha256.trim().lowercase()
                    if (expectedHash.matches(Regex("^[a-f0-9]{64}$"))) {
                        val actualHash = sha256Of(targetFile)
                        if (actualHash != expectedHash) {
                            targetFile.delete()
                            throw Exception("Checksum APK tidak cocok. Unduhan dibatalkan demi keamanan.")
                        }
                    }

                    if (!apkSignerMatchesInstalled(targetFile)) {
                        targetFile.delete()
                        throw Exception("Sertifikat APK berbeda dengan aplikasi terpasang. Lakukan instal ulang manual satu kali.")
                    }

                    isOtaDownloadingNow = false
                    pushOtaStatusToWeb(100, "COMPLETE", "Unduhan selesai 100%! Membuka penginstal Android...")
                    installApkFromFile(targetFile)
                } catch (e: Throwable) {
                    isOtaDownloadingNow = false
                    pushOtaStatusToWeb(0, "ERROR", "Unduhan gagal: ${e.message}")
                }
            }.start()
        }

        private fun currentHomePackageName(): String? {
            return try {
                val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (_: Throwable) {
                null
            }
        }

        private fun homeRoleManager(): RoleManager? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return try {
                getSystemService(RoleManager::class.java)
            } catch (_: Throwable) {
                null
            }
        }

        private fun openHomeSettingsScreen(): Boolean {
            val candidates = listOf(
                Intent(Settings.ACTION_HOME_SETTINGS),
                Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            for (intent in candidates) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return true
                } catch (_: Throwable) {}
            }
            return false
        }

        @JavascriptInterface
        fun isDefaultLauncher(): Boolean {
            return try {
                // RoleManager adalah sumber kebenaran di Android 10+. Saat boot,
                // resolveActivity(CATEGORY_HOME) bisa sementara mengembalikan
                // launcher lain/null meski role HOME sudah dipegang PangDash.
                val roleHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rm = homeRoleManager()
                    rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && rm.isRoleHeld(RoleManager.ROLE_HOME)
                } else {
                    false
                }
                val resolved = currentHomePackageName()
                roleHeld || (!resolved.isNullOrEmpty() && resolved == packageName)
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun promptSetDefaultLauncher() {
            runOnUiThread {
                // Android 10+: minta role HOME resmi. Ini jalur yang benar tanpa akses sistem.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rm = homeRoleManager()
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                        if (isDefaultLauncher()) {
                            Toast.makeText(context, "PangDash sudah menjadi Layar Utama", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        try {
                            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE)
                            return@runOnUiThread
                        } catch (_: Throwable) {}
                    }
                }

                // Android 9 ke bawah / ROM tanpa RoleManager: buka halaman Setelan Home.
                // Chooser ACTION_HOME sengaja tidak dipakai karena bisa langsung melempar
                // kembali ke launcher bawaan dan membuat pengguna bingung.
                if (openHomeSettingsScreen()) return@runOnUiThread

                Toast.makeText(
                    context,
                    "Setelan Layar Utama tidak tersedia. Buka Diagnostik Layar Utama untuk panduan ADB/factory menu.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        @JavascriptInterface
        fun getHomeDiagnosticsJson(): String {
            return try {
                val pm = context.packageManager
                val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
                val resolved = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                val resolvedPkg = resolved?.activityInfo?.packageName ?: ""
                val resolvedComponent = resolved?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: ""
                // Guard eksplisit Android 10+: RoleManager hanya ada sejak API 29 (lint NewApi).
                var roleAvailable = false
                var roleHeld = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rm = homeRoleManager()
                    if (rm != null) {
                        roleAvailable = rm.isRoleAvailable(RoleManager.ROLE_HOME)
                        roleHeld = rm.isRoleHeld(RoleManager.ROLE_HOME)
                    }
                }

                val candidates = JSONArray()
                val seen = HashSet<String>()
                for (ri in pm.queryIntentActivities(homeIntent, 0)) {
                    val ai = ri.activityInfo ?: continue
                    val key = "${ai.packageName}/${ai.name}"
                    if (!seen.add(key)) continue
                    val item = JSONObject()
                    item.put("pkg", ai.packageName)
                    item.put("activity", ai.name)
                    item.put("label", try { ri.loadLabel(pm).toString() } catch (_: Throwable) { ai.packageName })
                    item.put("isSystem", ((ai.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0)
                    item.put("isSelf", ai.packageName == context.packageName)
                    candidates.put(item)
                }

                val result = JSONObject()
                // Sama dengan isDefaultLauncher(): role HOME yang dipegang tetap
                // dianggap default walau resolusi Home transien saat boot.
                result.put("isDefault", roleHeld || resolvedPkg == context.packageName)
                result.put("resolvedPackage", resolvedPkg)
                result.put("resolvedComponent", resolvedComponent)
                result.put("roleAvailable", roleAvailable)
                result.put("roleHeld", roleHeld)
                result.put("sdkInt", Build.VERSION.SDK_INT)
                result.put("androidRelease", Build.VERSION.RELEASE ?: "")
                result.put("manufacturer", Build.MANUFACTURER ?: "")
                result.put("model", Build.MODEL ?: "")
                result.put("packageName", context.packageName)
                result.put("adbCommand", "adb shell cmd package set-home-activity ${context.packageName}/.MainActivity")
                result.put("candidates", candidates)
                result.toString()
            } catch (e: Throwable) {
                JSONObject().put("error", e.message ?: "unknown").toString()
            }
        }

        @JavascriptInterface
        fun openHomeSettings() {
            runOnUiThread {
                if (!openHomeSettingsScreen()) {
                    Toast.makeText(context, "Setelan Layar Utama tidak tersedia di ROM ini", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun openAppDetailsSettings() {
            runOnUiThread {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Throwable) {
                    Toast.makeText(context, "Tidak bisa membuka Info Aplikasi", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PangDash", text))
                runOnUiThread { Toast.makeText(context, "Teks disalin", Toast.LENGTH_SHORT).show() }
            } catch (_: Throwable) {
                runOnUiThread { Toast.makeText(context, "Gagal menyalin teks", Toast.LENGTH_SHORT).show() }
            }
        }

        @JavascriptInterface
        fun getInstalledAppsJson(forceRefresh: Boolean): String {
            if (!forceRefresh && cachedAppsJson != null) {
                return cachedAppsJson!!
            }
            if (forceRefresh) {
                appIconCache.clear()
                cachedAppsMetaJson = null
            }
            return try {
                buildInstalledAppsJson(includeIcons = true).also { cachedAppsJson = it }
            } catch (e: Throwable) {
                "[]"
            }
        }

        // Metadata ringan untuk startup: nama, paket, dan kategori tanpa ikon base64.
        // Ikon dimuat terpisah lewat getAppIconBase64() saat item benar-benar terlihat.
        @JavascriptInterface
        fun getInstalledAppsMetaJson(forceRefresh: Boolean): String {
            if (!forceRefresh && cachedAppsMetaJson != null) {
                return cachedAppsMetaJson!!
            }
            if (forceRefresh) {
                appIconCache.clear()
                cachedAppsJson = null
            }
            return try {
                buildInstalledAppsJson(includeIcons = false).also { cachedAppsMetaJson = it }
            } catch (e: Throwable) {
                "[]"
            }
        }

        @JavascriptInterface
        fun getAppIconBase64(pkg: String): String {
            if (pkg.isBlank()) return ""
            appIconCache[pkg]?.let { return it }
            val data = try {
                drawableToBase64(context.packageManager.getApplicationIcon(pkg))
            } catch (_: Throwable) {
                ""
            }
            appIconCache[pkg] = data
            return data
        }

        private fun buildInstalledAppsJson(includeIcons: Boolean): String {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val arr = JSONArray()
            val seenPackages = HashSet<String>()

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || seenPackages.contains(pkg)) continue
                seenPackages.add(pkg)

                val label = ri.loadLabel(pm)?.toString() ?: pkg
                val obj = JSONObject()
                obj.put("name", label)
                obj.put("pkg", pkg)
                obj.put("cat", categorizeApp(pkg, label, ri.activityInfo?.applicationInfo))
                if (includeIcons) {
                    obj.put("icon", iconForResolveInfo(pm, pkg, ri))
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        private fun iconForResolveInfo(pm: PackageManager, pkg: String, ri: ResolveInfo): String {
            appIconCache[pkg]?.let { return it }
            val data = try {
                val iconDrawable = ri.loadIcon(pm)
                if (iconDrawable != null) drawableToBase64(iconDrawable) else ""
            } catch (_: Throwable) {
                ""
            }
            appIconCache[pkg] = data
            return data
        }

        // Kontrol Media Multi-Jalur (AudioManager + MediaController + Targeted Broadcast ke Aplikasi Musik)
        @JavascriptInterface
        fun sendMediaControl(action: String, targetPkg: String?) {
            runOnUiThread {
                try {
                    val am = audioManager ?: return@runOnUiThread
                    val wasPlaying = am.isMusicActive

                    // 1. Jika MediaListenerService aktif, gunakan TransportControls langsung
                    val activeController = MediaListenerService.instance?.getActiveController()
                    if (activeController != null) {
                        try {
                            when (action) {
                                "next" -> activeController.transportControls.skipToNext()
                                "prev" -> activeController.transportControls.skipToPrevious()
                                "play_pause" -> {
                                    if (wasPlaying) activeController.transportControls.pause()
                                    else activeController.transportControls.play()
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    // 2. Kirim KeyEvent via AudioManager.dispatchMediaKeyEvent pada Main Thread
                    // Gunakan kode spesifik PLAY atau PAUSE (idempotent) selain PLAY_PAUSE agar pasti direspons oleh headunit
                    val primaryKeyCode = when (action) {
                        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "play_pause" -> if (wasPlaying) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
                        else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    }
                    val (down1, up1) = buildSystemMediaKeyEvents(primaryKeyCode)
                    am.dispatchMediaKeyEvent(down1)
                    am.dispatchMediaKeyEvent(up1)

                    // Untuk Next & Prev, langsung kirim juga broadcast ke receiver aplikasi musik target & MusicService
                    if (action == "next" || action == "prev") {
                        dispatchTargetedMediaButtonBroadcast(primaryKeyCode, targetPkg)
                        dispatchLegacyMusicServiceCommand(action, wasPlaying, targetPkg)
                    } else if (action == "play_pause") {
                        // Kirim command eksplisit "play" atau "pause" (idempotent, tidak akan double-toggle)
                        dispatchTargetedMediaButtonBroadcast(primaryKeyCode, targetPkg)
                        dispatchLegacyMusicServiceCommand(action, wasPlaying, targetPkg)

                        // Fallback 160ms: jika status audio belum berubah dengan KEYCODE_MEDIA_PLAY/PAUSE spesifik,
                        // kirim KEYCODE_MEDIA_PLAY_PAUSE toggle
                        mainHandler.postDelayed({
                            val nowPlaying = am.isMusicActive
                            if (nowPlaying == wasPlaying) {
                                val (downToggle, upToggle) = buildSystemMediaKeyEvents(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                                am.dispatchMediaKeyEvent(downToggle)
                                am.dispatchMediaKeyEvent(upToggle)
                                dispatchTargetedMediaButtonBroadcast(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, targetPkg)
                            }
                        }, 160)
                    }

                    // Sinkronkan kembali status tombol Play/Pause di UI
                    mainHandler.postDelayed({
                        pushAudioPlayingState(am.isMusicActive)
                        MediaListenerService.instance?.pollActiveMediaSessions()
                    }, 280)
                    mainHandler.postDelayed({
                        pushAudioPlayingState(am.isMusicActive)
                    }, 650)
                } catch (_: Throwable) {}
            }
        }

        @JavascriptInterface
        fun isMusicPlaying(): Boolean {
            return try {
                audioManager?.isMusicActive ?: false
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun launchNativeMaps(query: String) {
            runOnUiThread {
                try {
                    val pm = context.packageManager
                    if (query.isNotBlank()) {
                        val gmmUri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmUri)
                        mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(mapIntent)
                        return@runOnUiThread
                    }
                    val navPackages = listOf(
                        "com.google.android.apps.maps",
                        "com.waze",
                        "com.sygic.aura",
                        "com.here.app.maps",
                        "ru.yandex.yandexnavi"
                    )
                    for (pkg in navPackages) {
                        val intent = pm.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                            return@runOnUiThread
                        }
                    }
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                    fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(fallbackIntent)
                } catch (_: Throwable) {
                    Toast.makeText(context, "Aplikasi Peta belum ditemukan", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun canWriteSystemSettings(): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
        }

        @JavascriptInterface
        fun requestWriteSettingsPermission() {
            runOnUiThread {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Toast.makeText(
                            context,
                            "Aktifkan izin 'Ubah Pengaturan Sistem' agar Brightness terhubung penuh ke Headunit",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                } catch (_: Throwable) {}
            }
        }

        @JavascriptInterface
        fun getSystemBrightness(): Int {
            return readCurrentSystemBrightnessPercent()
        }

        @JavascriptInterface
        fun setSystemBrightness(percent: Int) {
            val clamped = percent.coerceIn(5, 100)
            val sysVal = ((clamped / 100.0) * 255.0).roundToInt().coerceIn(5, 255)
            runOnUiThread {
                // 1. Terapkan langsung pada Window Activity saat ini (hardware backlight override untuk window aktif)
                try {
                    val lp = window.attributes
                    lp.screenBrightness = (clamped / 100f).coerceIn(0.05f, 1.0f)
                    window.attributes = lp
                } catch (_: Throwable) {}

                // 2. Tulis ke Settings.System.SCREEN_BRIGHTNESS & kirim broadcast MCU Headunit (FYT/SYU/Microntek/Topway)
                try {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
                        try {
                            Settings.System.putInt(
                                contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                            )
                        } catch (_: Throwable) {}
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, sysVal)
                        contentResolver.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), null)
                        lastPushedBrightnessPercent = clamped

                        try {
                            sendBroadcast(Intent("com.microntek.backlight").putExtra("backlight", sysVal))
                            sendBroadcast(Intent("com.syu.ms.brightness").putExtra("brightness", sysVal))
                            sendBroadcast(Intent("android.intent.action.SCREEN_BRIGHTNESS_CHANGED").putExtra("brightness", sysVal))
                        } catch (_: Throwable) {}
                    } else if (!hasPromptedWriteSettings) {
                        hasPromptedWriteSettings = true
                        Toast.makeText(
                            context,
                            "Izinkan 'Ubah Pengaturan Sistem' agar bar Brightness dapat mengatur layar Headunit",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                } catch (_: Throwable) {}
            }
        }

        // Atur Volume Sistem & MCU Headunit dari Slider (Anti-Mental ke 100% + Emulasi Steer Control)
        @JavascriptInterface
        fun setSystemVolume(percent: Int) {
            val clamped = percent.coerceIn(0, 100)
            val prevPct = trackedVolumePercent
            lastUserSliderSetTimestamp = SystemClock.uptimeMillis()
            saveTrackedVolumePercent(clamped)
            lastPushedVolumePercent = clamped

            val prevMcuStep = ((prevPct / 100.0) * mcuMaxVolume).roundToInt().coerceIn(0, mcuMaxVolume)
            val targetMcuStep = ((clamped / 100.0) * mcuMaxVolume).roundToInt().coerceIn(0, mcuMaxVolume)
            val deltaMcuSteps = targetMcuStep - prevMcuStep

            runOnUiThread {
                try {
                    val am = audioManager ?: return@runOnUiThread
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (clamped == 0) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                        } else if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                        }
                    }

                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val targetVol = ((clamped / 100.0) * maxVol).roundToInt().coerceIn(0, maxVol)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)

                    val afterVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (afterVol >= maxVol && targetVol < maxVol) {
                        isFixedStreamHeadunit = true
                        prefs?.edit()?.putBoolean("is_fixed_stream_hu", true)?.apply()
                    }

                    writeMcuHardwareVolume(clamped)
                } catch (_: Throwable) {}
            }

            // Jika ROM Headunit mengunci STREAM_MUSIC (MCU Amplifier Mode), kirim step hardware KeyEvent + adjustStreamVolume
            if (deltaMcuSteps != 0) {
                volumeStepExecutor.execute {
                    try {
                        isInternalVolumeStepping = true
                        val am = audioManager
                        val dir = if (deltaMcuSteps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        val keyCode = if (deltaMcuSteps > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                        val stepsCount = abs(deltaMcuSteps).coerceAtMost(12)
                        val inst = Instrumentation()

                        for (i in 0 until stepsCount) {
                            runOnUiThread {
                                try {
                                    am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                                } catch (_: Throwable) {}
                            }
                            try {
                                inst.sendKeyDownUpSync(keyCode)
                            } catch (_: Throwable) {}
                            Thread.sleep(32)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        lastUserSliderSetTimestamp = SystemClock.uptimeMillis()
                        isInternalVolumeStepping = false
                    }
                }
            }
        }

        // Tombol - dan + Volume menggunakan step MCU & KeyEvent asli seperti tombol fisik Steer Control
        @JavascriptInterface
        fun stepSystemVolume(direction: Int): Int {
            return try {
                val am = audioManager
                lastUserSliderSetTimestamp = SystemClock.uptimeMillis()
                val stepPct = (100.0 / mcuMaxVolume.coerceAtLeast(1)).roundToInt().coerceAtLeast(4)
                val targetPct = if (direction > 0) {
                    (trackedVolumePercent + stepPct).coerceIn(0, 100)
                } else {
                    (trackedVolumePercent - stepPct).coerceIn(0, 100)
                }
                saveTrackedVolumePercent(targetPct)
                lastPushedVolumePercent = targetPct

                val adj = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                val keyCode = if (direction > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN

                runOnUiThread {
                    try {
                        if (am != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && direction > 0 && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                            }
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, adj, AudioManager.FLAG_SHOW_UI)
                        }
                        writeMcuHardwareVolume(targetPct)
                    } catch (_: Throwable) {}
                }

                volumeStepExecutor.execute {
                    try {
                        isInternalVolumeStepping = true
                        Instrumentation().sendKeyDownUpSync(keyCode)
                    } catch (_: Throwable) {
                    } finally {
                        isInternalVolumeStepping = false
                    }
                }

                targetPct
            } catch (_: Throwable) {
                trackedVolumePercent
            }
        }

        @JavascriptInterface
        fun getSystemVolume(): Int {
            return readCurrentSystemVolumePercent()
        }

        @JavascriptInterface
        fun launchApp(packageName: String, appLabel: String) {
            runOnUiThread {
                try {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } else {
                        Toast.makeText(context, "Aplikasi $appLabel belum dipilih / tidak ditemukan", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) {
                    Toast.makeText(context, "Gagal membuka $appLabel", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openSystemSettings() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) {}
            }
        }

        @JavascriptInterface
        fun isNotificationListenerEnabled(): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: return false
                val expected = ComponentName(context, MediaListenerService::class.java)
                enabled.split(":").any { ComponentName.unflattenFromString(it) == expected }
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun openNotificationListenerSettings() {
            runOnUiThread {
                // Android 11+ punya halaman detail per aplikasi. Ini lebih
                // langsung daripada daftar "Device & app notifications".
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val component = ComponentName(context, MediaListenerService::class.java)
                        startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(
                                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                    component
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        return@runOnUiThread
                    } catch (_: Throwable) {}
                }
                try {
                    startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
