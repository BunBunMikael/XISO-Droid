package dev.xdvdfs.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Notification helpers for the foreground service and the "job finished" message. */
object Notifications {
    private const val CHANNEL_PROGRESS = "xiso_progress"
    private const val CHANNEL_DONE = "xiso_done"

    const val ID_PROGRESS = 1
    private const val ID_DONE = 2

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.channel_done),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(context: Context): PendingIntent {
        val intent = Intent(context, XisoService::class.java).setAction(XisoService.ACTION_CANCEL)
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The ongoing notification shown while a job runs. [percent] < 0 means indeterminate. */
    fun progress(context: Context, title: String, text: String, percent: Int): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_xiso)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.cancel), cancelIntent(context))

        if (percent < 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        }
        return builder.build()
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun updateProgress(context: Context, notification: Notification) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java)?.notify(ID_PROGRESS, notification)
    }

    fun finished(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_xiso)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ID_DONE, notification)
    }
}
