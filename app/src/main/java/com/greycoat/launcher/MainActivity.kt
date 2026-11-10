package com.greycoat.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

class MainActivity : Activity(), LocationListener {

    private var webView: WebView? = null
    private var audioManager: AudioManager? = null
    private var locationManager: LocationManager? = null

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
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.MixedContentModeCompat()

            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            wv.addJavascriptInterface(HeadunitBridge(this), "AndroidBridge")

            container.addView(wv)
            webView = wv
            setContentView(container)

            wv.loadUrl("file:///android_asset/index.html")
        } catch (t: Throwable) {
            setContentView(container)
            Toast.makeText(this, "WebView init error: ${t.message}", Toast.LENGTH_LONG).show()
        }

        // Panggil hideSystemUI SETELAH setContentView dan saat DecorView sudah siap
        window?.decorView?.post {
            hideSystemUI()
            requestGpsPermissionsSafely()
        }
    }

    private fun WebSettings.MixedContentModeCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                this.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun requestGpsPermissionsSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!fineGranted && !coarseGranted) {
                    // Android 12+ (targetSdk 34) WAJIB meminta FINE dan COARSE secara bersamaan
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
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, this)
            }
        } catch (_: Throwable) {}
    }

    override fun onLocationChanged(location: Location) {
        try {
            val speedKmh = (location.speed * 3.6f).toInt()
            val lat = location.latitude
            val lng = location.longitude
            runOnUiThread {
                webView?.evaluateJavascript(
                    "if(window.onNativeGpsUpdate) window.onNativeGpsUpdate($speedKmh, $lat, $lng);",
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

    inner class HeadunitBridge(private val context: Context) {

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
                        Toast.makeText(context, "Aplikasi $appLabel belum terinstal di perangkat ini", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) {
                    Toast.makeText(context, "Tidak dapat membuka $appLabel", Toast.LENGTH_SHORT).show()
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
