package com.philip.fieldtracker.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Garde le GPS et le cardio de la montre actifs pendant la séance, écran éteint compris. */
class WatchTrackingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WatchRecorder.init(this)
        if (intent?.action == ACTION_STOP) {
            cleanup()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Séance", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("FieldTracker")
            .setContentText(WatchRecorder.config.value.sportName.ifEmpty { "Séance" })
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 29 && granted(Manifest.permission.ACCESS_FINE_LOCATION)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 30 && granted(Manifest.permission.BODY_SENSORS)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        try {
            ServiceCompat.startForeground(this, 7, n, type)
        } catch (_: Exception) {
            ServiceCompat.startForeground(this, 7, n, 0)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fieldtracker:watch")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        WatchRecorder.gpsStart("rec")
        WatchRecorder.hrStart()
        return START_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun cleanup() {
        WatchRecorder.gpsStop("rec")
        WatchRecorder.hrStop()
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        private const val CH = "watchsession"
        private const val ACTION_STOP = "stop"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchTrackingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WatchTrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
