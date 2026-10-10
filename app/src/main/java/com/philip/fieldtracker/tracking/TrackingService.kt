package com.philip.fieldtracker.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.philip.fieldtracker.MainActivity
import com.philip.fieldtracker.R
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.watch.WatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Service au premier plan : garde le GPS actif pendant la séance, écran éteint compris. */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppState.init(this)
        when (intent?.action) {
            ACTION_STOP -> {
                Tracker.stop(this)
                GpsEngine.stop(TAG)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                GpsEngine.start(this, TAG)
                if (ticker == null) {
                    ticker = scope.launch {
                        while (isActive) {
                            delay(1000)
                            WatchLink.pushState()
                            if (!Tracker.isActive) {
                                GpsEngine.stop(TAG)
                                ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                                stopSelf()
                                break
                            }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Séance en cours", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sport = Tracker.live.value.sport?.name ?: "Séance"
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("FieldTracker – $sport")
            .setContentText("Suivi GPS du terrain en cours")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(Tracker.live.value.startMs.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "tracking"
        private const val CHANNEL = "session"
        private const val NOTIF_ID = 42
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
