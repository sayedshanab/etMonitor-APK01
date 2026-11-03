package com.netmonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat

object Notifier {
    // Channel sounds cannot be changed after creation, so bump the suffix if you change a sound.
    const val CH_STATUS = "status_v1"
    const val CH_DOWN = "down_v1"
    const val CH_UP = "up_v1"

    const val ID_FG = 1
    const val ID_DOWN = 2
    const val ID_UP = 3

    const val ACTION_MUTE = "com.netmonitor.app.MUTE"
    const val ACTION_STOP = "com.netmonitor.app.STOP"

    private fun rawUri(ctx: Context, res: Int): Uri =
        Uri.parse("android.resource://${ctx.packageName}/$res")

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val notifAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val status = NotificationChannel(
            CH_STATUS, "حالة المراقبة", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "إشعار ثابت يبقي المراقبة شغالة في الخلفية"
            setShowBadge(false)
        }

        val down = NotificationChannel(
            CH_DOWN, "تنبيه فصل النت", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "نغمة تنبيه عند فصل الانترنت"
            setSound(rawUri(ctx, R.raw.disconnect), alarmAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
        }

        val up = NotificationChannel(
            CH_UP, "تنبيه رجوع النت", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "نغمة عند رجوع الانترنت"
            setSound(rawUri(ctx, R.raw.reconnect), notifAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
        }

        nm.createNotificationChannel(status)
        nm.createNotificationChannel(down)
        nm.createNotificationChannel(up)
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionIntent(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** The persistent notification required for a foreground service. */
    fun status(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_net)
            .setContentTitle("مراقب النت الذكي")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "إيقاف", actionIntent(ctx, ACTION_STOP, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun disconnect(ctx: Context, threshold: Int, repeat: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(ctx, CH_DOWN)
            .setSmallIcon(R.drawable.ic_net)
            .setContentTitle("🚨 الانترنت فصل!")
            .setContentText("فشل الاتصال $threshold مرات متتالية")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "🔇 إسكات", actionIntent(ctx, ACTION_MUTE, 1))
            .build()
        // Repeats the channel sound until the notification is dismissed (works on many, not all, devices).
        if (repeat) n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(ID_DOWN, n)
    }

    fun reconnect(ctx: Context, downSeconds: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_DOWN)
        val n = NotificationCompat.Builder(ctx, CH_UP)
            .setSmallIcon(R.drawable.ic_net)
            .setContentTitle("✅ الانترنت رجع!")
            .setContentText("كان فاصل لمدة $downSeconds ثانية")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(openApp(ctx))
            .build()
        nm.notify(ID_UP, n)
    }

    fun mute(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_DOWN)
    }
}
