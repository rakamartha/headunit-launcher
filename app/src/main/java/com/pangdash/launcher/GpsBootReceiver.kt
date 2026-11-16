package com.pangdash.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

class GpsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Boot Android 10+ memerlukan persetujuan lokasi latar belakang terpisah.
        if (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try { GpsRecordingService.start(context) }
        catch (error: Exception) { Log.w("PangDashGPS", "GPS boot belum dapat dimulai", error) }
    }
}
