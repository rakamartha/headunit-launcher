package com.pangdash.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/** Tidak bergantung pada Activity/WebView atau tampilan kamera OEM. */
class GpsRecordingService : Service(), LocationListener {
    private var manager: LocationManager? = null
    private var listening = false
    private var lastElapsed = 0L
    private var bootId = 0

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, GpsRecordingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val channelId = "pangdash_trip_recording"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Pencatatan perjalanan", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("PangDash — pencatatan perjalanan")
            .setContentText("GPS tetap direkam saat kamera atau aplikasi lain tampil")
            .setContentIntent(open).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(2401, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(2401, notification)
            manager = getSystemService(LOCATION_SERVICE) as? LocationManager
            bootId = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
        } catch (error: Exception) {
            Log.w("PangDashGPS", "Tidak dapat memulai foreground GPS", error)
            stopSelf()
        }
    }
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!listening) {
            try {
                val lm = manager ?: return START_NOT_STICKY
                // GPS saja: NETWORK tidak boleh ikut menambah jarak.
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                listening = true
            } catch (error: Exception) {
                Log.w("PangDashGPS", "GPS tidak tersedia", error)
            }
        }
        return START_STICKY
    }
    override fun onLocationChanged(location: Location) {
        val elapsed = location.elapsedRealtimeNanos / 1_000_000L
        val now = SystemClock.elapsedRealtime()
        if (elapsed <= lastElapsed || elapsed > now || now - elapsed > 10000L || elapsed <= 0L) return
        if (location.provider != LocationManager.GPS_PROVIDER) return
        val payload = JSONObject().apply {
            put("wall", System.currentTimeMillis() - (now - elapsed))
            put("elapsed", elapsed); put("boot", bootId)
            put("lat", location.latitude); put("lng", location.longitude)
            put("speed", if (location.hasSpeed()) location.speed * 3.6 else 0.0)
            put("hasSpeed", location.hasSpeed())
            put("bearing", if (location.hasBearing()) location.bearing.toDouble() else 0.0)
            put("accuracy", if (location.hasAccuracy()) location.accuracy.toDouble() else 9999.0)
        }
        try {
            GpsJournal.get(this).append(payload.toString())
            lastElapsed = elapsed
        } catch (error: Exception) { Log.e("PangDashGPS", "Gagal menyimpan fix", error) }
    }
    override fun onDestroy() {
        manager?.removeUpdates(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
