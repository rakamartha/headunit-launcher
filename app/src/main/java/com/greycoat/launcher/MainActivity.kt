package com.greycoat.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private var cachedAppsJson: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPushedVolumePercent: Int = -1

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

    // 2. BroadcastReceiver Khusus Volume Sistem & Steer Control (SWC) Headunit
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pushCurrentSystemVolumeToWeb(force = false)
        }
    }

    // 3. ContentObserver untuk memantau perubahan volume level MCU / Settings.System
    private val settingsVolumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            pushCurrentSystemVolumeToWeb(force = false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {}

        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            // Pastikan tombol volume fisik / Steer Control selalu mengatur STREAM_MUSIC
            volumeControlStream = AudioManager.STREAM_MUSIC
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
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Headunit) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            wv.webViewClient = WebViewClient()
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

        window?.decorView?.post {
            hideSystemUI()
            requestGpsPermissionsSafely()
            pushCurrentSystemVolumeToWeb(force = true)
            MediaListenerService.instance?.pollActiveMediaSessions()
        }
    }

    // Tangkap langsung tombol Steer Control (SWC) Volume & Media ketika Launcher sedang tampil di layar
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val handled = super.dispatchKeyEvent(event)
                // Segera sinkronkan posisi slider volume di layar WebView mengikuti Steer Control
                mainHandler.postDelayed({ pushCurrentSystemVolumeToWeb(force = true) }, 15)
                mainHandler.postDelayed({ pushCurrentSystemVolumeToWeb(force = true) }, 120)
                return handled
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun registerVolumeSyncWatchers() {
        try {
            val volFilter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
                addAction("android.media.MASTER_MUTE_CHANGED_ACTION")
                addAction("android.media.RINGER_MODE_CHANGED")
                // Broadcast khusus berbagai merek Headunit Android (SYU / FYT / Microntek / Topway)
                addAction("com.microntek.volume")
                addAction("com.syu.ms.vol")
                addAction("com.syu.ms")
                addAction("android.intent.action.VOLUME_CHANGED")
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
        } catch (_: Throwable) {}
    }

    private fun readCurrentSystemVolumePercent(): Int {
        return try {
            val am = audioManager ?: return 75
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                return 0
            }
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (maxVol > 0) ((curVol * 100.0) / maxVol).roundToInt().coerceIn(0, 100) else 75
        } catch (_: Throwable) {
            75
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

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        val playing = audioManager?.isMusicActive ?: false
        pushAudioPlayingState(playing)
        pushCurrentSystemVolumeToWeb(force = true)
        runOnUiThread {
            webView?.evaluateJavascript("if(window.onNativeResumeCheck) window.onNativeResumeCheck();", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this)
            }
            val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastLoc != null) {
                onLocationChanged(lastLoc)
            }
        } catch (_: Throwable) {}
    }

    override fun onLocationChanged(location: Location) {
        try {
            val speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).toInt() else 0
            val lat = location.latitude
            val lng = location.longitude
            val bearing = if (location.hasBearing()) location.bearing else 0f
            runOnUiThread {
                webView?.evaluateJavascript(
                    "if(window.onNativeGpsUpdate) window.onNativeGpsUpdate($speedKmh, $lat, $lng, $bearing);",
                    null
                )
            }
        } catch (_: Throwable) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

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
            val size = 72
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)
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
        fun isDefaultLauncher(): Boolean {
            return try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                }
                val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                val currentHomePkg = resolveInfo?.activityInfo?.packageName
                currentHomePkg == context.packageName
            } catch (_: Throwable) {
                false
            }
        }

        @JavascriptInterface
        fun promptSetDefaultLauncher() {
            runOnUiThread {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val roleManager = getSystemService(RoleManager::class.java)
                        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME) && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                            startActivityForResult(intent, 2002)
                            return@runOnUiThread
                        }
                    }
                    try {
                        val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS)
                        homeSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(homeSettings)
                    } catch (_: Throwable) {
                        val chooser = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(Intent.createChooser(chooser, "Pilih GREYCOAT sebagai Layar Utama"))
                    }
                } catch (_: Throwable) {
                    Toast.makeText(context, "Buka Setelan -> Aplikasi Default -> Aplikasi Layar Utama", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun getInstalledAppsJson(forceRefresh: Boolean): String {
            if (!forceRefresh && cachedAppsJson != null) {
                return cachedAppsJson!!
            }
            return try {
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
                    val iconDrawable = ri.loadIcon(pm)
                    val iconBase64 = if (iconDrawable != null) drawableToBase64(iconDrawable) else ""
                    val cat = categorizeApp(pkg, label, ri.activityInfo?.applicationInfo)

                    val obj = JSONObject()
                    obj.put("name", label)
                    obj.put("pkg", pkg)
                    obj.put("cat", cat)
                    obj.put("icon", iconBase64)
                    arr.put(obj)
                }
                val result = arr.toString()
                cachedAppsJson = result
                result
            } catch (e: Throwable) {
                "[]"
            }
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
        fun setSystemBrightness(percent: Int) {
            try {
                val clamped = percent.coerceIn(10, 100)
                runOnUiThread {
                    try {
                        val lp = window.attributes
                        lp.screenBrightness = clamped / 100f
                        window.attributes = lp
                    } catch (_: Throwable) {}
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
                    val sysVal = ((clamped / 100.0) * 255).toInt()
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, sysVal)
                }
            } catch (_: Throwable) {}
        }

        // Atur Volume Sistem Headunit dari Slider (Menggunakan FLAG_SHOW_UI + Fallback Step MCU)
        @JavascriptInterface
        fun setSystemVolume(percent: Int) {
            runOnUiThread {
                try {
                    val am = audioManager ?: return@runOnUiThread
                    val clamped = percent.coerceIn(0, 100)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (clamped == 0) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                        } else if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                        }
                    }

                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val beforeVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = ((clamped / 100.0) * maxVol).roundToInt().coerceIn(0, maxVol)

                    // Gunakan FLAG_SHOW_UI agar MCU amplifier headunit menerima interupsi perubahan volume
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)

                    // Jika MCU Headunit mengunci setStreamVolume langsung, dorong dengan adjustStreamVolume bertahap
                    val afterVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (afterVol == beforeVol && targetVol != beforeVol) {
                        val direction = if (targetVol > beforeVol) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        val steps = abs(targetVol - beforeVol).coerceAtMost(6)
                        for (i in 0 until steps) {
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                        }
                    }

                    lastPushedVolumePercent = readCurrentSystemVolumePercent()
                } catch (_: Throwable) {}
            }
        }

        // Tombol - dan + Volume menggunakan adjustStreamVolume asli seperti tombol fisik Steer Control
        @JavascriptInterface
        fun stepSystemVolume(direction: Int): Int {
            try {
                val am = audioManager ?: return 75
                val adj = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && direction > 0 && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, adj, AudioManager.FLAG_SHOW_UI)
                val newPct = readCurrentSystemVolumePercent()
                lastPushedVolumePercent = newPct
                return newPct
            } catch (_: Throwable) {
                return readCurrentSystemVolumePercent()
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
    }
}
