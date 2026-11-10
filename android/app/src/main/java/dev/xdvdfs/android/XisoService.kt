package dev.xdvdfs.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps the process alive while a job runs, so packing a
 * multi-gigabyte image does not stop when the user leaves the app.
 */
class XisoService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            JobRunner.cancel()
            if (JobRunner.activeTool == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = JobRunner.initialNotification(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_PROGRESS, notification, type)
        } catch (e: Exception) {
            // Not allowed to run in the foreground (restricted by the system): carry on
            // as a plain service, the job is still started below.
        }

        JobRunner.execute(this) { stopSelf() }
        return START_NOT_STICKY
    }

    /** Android 15 limits `dataSync` services to about six hours; stop cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        JobRunner.cancel()
        stopSelf()
    }

    companion object {
        const val ACTION_CANCEL = "dev.xdvdfs.android.action.CANCEL"
    }
}
