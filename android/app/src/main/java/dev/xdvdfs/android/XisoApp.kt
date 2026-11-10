package dev.xdvdfs.android

import android.app.Application

class XisoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.ensureChannel(this)
        releaseStaleGrants()
    }

    /**
     * Folder and file grants are persisted only to survive the activity being closed
     * during a job. Nothing is remembered across process restarts, so give them back
     * (Android caps the number of persisted grants per app).
     */
    private fun releaseStaleGrants() {
        try {
            for (permission in contentResolver.persistedUriPermissions) {
                Saf.release(contentResolver, permission.uri, permission.isWritePermission)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        lateinit var instance: XisoApp
            private set
    }
}
