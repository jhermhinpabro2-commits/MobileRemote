package com.remotecheck

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service required by Android while the screen is being captured. Shows a notification the whole time. */
class ScreenShareService : Service() {

    companion object {
        const val EXTRA_DATA = "proj"
        const val EXTRA_FROM = "from"
        const val ACTION_STOP = "com.remotecheck.STOP"
        private const val CH = "rc_share"

        fun start(ctx: Context, data: Intent, from: String) {
            val i = Intent(ctx, ScreenShareService::class.java)
                .putExtra(EXTRA_DATA, data).putExtra(EXTRA_FROM, from)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Hub.endSession()
            stopSelf()
            return START_NOT_STICKY
        }

        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        val from = intent?.getStringExtra(EXTRA_FROM)
        if (data == null || from == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        Hub.startHost(from, data)
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
            b = Notification.Builder(this, CH)
        } else {
            @Suppress("DEPRECATION")
            b = Notification.Builder(this)
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return b.setContentTitle("RemoteCheck")
            .setContentText("Your screen is being shared")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stop).build())
            .build()
    }
}
