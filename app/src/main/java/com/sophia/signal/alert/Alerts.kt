package com.sophia.signal.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sophia.signal.R
import com.sophia.signal.data.Prefs
import com.sophia.signal.data.Settings
import com.sophia.signal.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

class Alerts(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        const val CH_ALERT = "sophia_alerts"
        const val CH_SCAN = "sophia_scan"
        val TONES: List<Pair<String, Int>> = listOf(
            "Beep" to ToneGenerator.TONE_PROP_BEEP,
            "Double beep" to ToneGenerator.TONE_PROP_BEEP2,
            "Acknowledge" to ToneGenerator.TONE_PROP_ACK,
            "Guard tone" to ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
            "Error tone" to ToneGenerator.TONE_SUP_ERROR,
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val nid = AtomicInteger(2000)

    init {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Watchlist & priority alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown when a watched or priority identifier appears"
                setSound(null, null) // the app plays its own configurable tone
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, "Active scanning", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent notice while SØPHIA is scanning"
            }
        )
    }

    fun alert(title: String, text: String) {
        val s = prefs.settings.value
        if (!s.alertsEnabled) return
        playTone(s)
        if (s.vibrate) vibrate()
        notify(title, text)
    }

    fun playTone(s: Settings = prefs.settings.value) {
        main.post {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, s.alertVolume.coerceIn(0, 100))
                tg.startTone(TONES.getOrElse(s.alertTone) { TONES[0] }.second, 450)
                main.postDelayed({ tg.release() }, 900)
            } catch (e: RuntimeException) {
                // ToneGenerator can fail if the audio system is busy; alerts still notify.
            }
        }
    }

    private fun vibrate() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun notify(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_sophia)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(nid.incrementAndGet(), n) } catch (e: SecurityException) { }
    }
}
