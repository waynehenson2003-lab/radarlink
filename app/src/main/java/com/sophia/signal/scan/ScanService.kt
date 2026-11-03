package com.sophia.signal.scan

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sophia.signal.R
import com.sophia.signal.alert.Alerts
import com.sophia.signal.ui.MainActivity

/**
 * Foreground service that keeps the process alive while scanning so observation continues
 * with the screen off where Android permits. The engines themselves live in ScanController.
 */
class ScanService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Alerts.CH_SCAN)
            .setSmallIcon(R.drawable.ic_stat_sophia)
            .setContentTitle("SØPHIA scanning")
            .setContentText("Passive Wi-Fi / BLE observation active. Data stays on this device.")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        } catch (e: Exception) {
            // e.g. location permission missing on Android 14+; scanning continues in foreground only.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 1001
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, ScanService::class.java)) } catch (e: Exception) { }
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScanService::class.java))
        }
    }
}
