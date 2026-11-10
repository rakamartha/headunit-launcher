package com.greycoat.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
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
    fun pollActiveMediaSessions() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            val comp = ComponentName(this, MediaListenerService::class.java)
            val controllers: List<MediaController> = msm.getActiveSessions(comp)
            if (controllers.isNotEmpty()) {
                val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.first()
                val meta = active.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: "Musik Headunit Aktif"
                val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: active.packageName
                val isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING
                onMediaChangedCallback?.invoke(title, artist, active.packageName, isPlaying)
            }
        } catch (_: Throwable) {}
    }
}

class MainActivity : Activity(), LocationListener {

    private var webView: WebView? = null
    private var audioManager: AudioManager? = null
    private var locationManager: LocationManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var cachedAppsJson: String? = null

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            try {
                val track = intent.getStringExtra("track")
                    ?: intent.getStringExtra("title")
                    ?: intent.getStringExtra("EXTRA_TRACK")
                    ?: return
                val artist = intent.getStringExtra("artist")
                    ?: intent.getStringExtra("album")
                    ?: "Musik Headunit"
                val playing = if (intent.hasExtra("playing")) {
                    intent.getBooleanExtra("playing", true)
                } else {
                    audioManager?.isMusicActive ?: true
                }
                val pkg = intent.action ?: "Media"
                pushMediaToWeb(track, artist, pkg, playing)
            } catch (_: Throwable) {}
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

        // Hubungkan MediaListenerService callback
        MediaListenerService.onMediaChangedCallback = { title, artist, pkg, playing ->
            pushMediaToWeb(title, artist, pkg, playing)
        }

        registerMediaBroadcasts()
        registerAutoNetworkSync()

        window?.decorView?.post {
            hideSystemUI()
            requestGpsPermissionsSafely()
            MediaListenerService.instance?.pollActiveMediaSessions()
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(mediaReceiver)
        } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
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
        if (p.contains("spotify") || p.contains("music") || p.contains("youtube") || p.contains("netflix") ||
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

    inner class HeadunitBridge(private val context: Context) {

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

        @JavascriptInterface
        fun sendMediaControl(action: String) {
            try {
                val am = audioManager ?: return
                val keyCode = when (action) {
                    "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                val eventTime = SystemClock.uptimeMillis()
                val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
                val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
                am.dispatchMediaKeyEvent(downEvent)
                am.dispatchMediaKeyEvent(upEvent)

                window?.decorView?.postDelayed({
                    MediaListenerService.instance?.pollActiveMediaSessions()
                }, 350)
            } catch (_: Throwable) {}
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
        fun openMusicApp(sourceType: String) {
            runOnUiThread {
                try {
                    val pm = context.packageManager
                    val candidates = when (sourceType.uppercase()) {
                        "SPOTIFY" -> listOf("com.spotify.music", "com.google.android.apps.youtube.music")
                        "RADIO" -> listOf("com.android.fmradio", "com.caf.fmradio", "com.sec.android.app.fm", "com.syu.radio")
                        "BT" -> listOf("com.syu.bt", "com.android.bluetooth", "com.google.android.bluetooth")
                        else -> listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.android.music", "com.sec.android.app.music", "com.miui.player")
                    }
                    for (pkg in candidates) {
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                            return@runOnUiThread
                        }
                    }
                    // Fallback ke pemutar musik default Android
                    val musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                    musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(musicIntent)
                } catch (_: Throwable) {
                    Toast.makeText(context, "Membuka aplikasi Musik...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openNotificationListenerSettings() {
            runOnUiThread {
                try {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Throwable) {}
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
                    // Cari aplikasi Maps/Navigasi yang terinstal di Headunit
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
                    Toast.makeText(context, "Tidak menemukan aplikasi Peta/Navigasi di perangkat ini", Toast.LENGTH_SHORT).show()
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

        @JavascriptInterface
        fun setSystemVolume(percent: Int) {
            try {
                val am = audioManager ?: return
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVol = ((percent.coerceIn(0, 100) / 100.0) * maxVol).toInt()
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun getSystemVolume(): Int {
            return try {
                val am = audioManager ?: return 75
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (maxVol > 0) ((curVol * 100.0) / maxVol).toInt() else 75
            } catch (_: Throwable) {
                75
            }
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
                        Toast.makeText(context, "Aplikasi $appLabel tidak ditemukan", Toast.LENGTH_SHORT).show()
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
