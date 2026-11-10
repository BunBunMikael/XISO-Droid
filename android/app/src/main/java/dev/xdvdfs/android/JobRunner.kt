package dev.xdvdfs.android

import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs one job at a time on a worker thread. The job itself is started from
 * [XisoService] (a foreground service, so Android keeps it alive in the background),
 * but everything is held here so the UI only has to observe the [WorkflowModel]s.
 */
object JobRunner {
    private const val UI_INTERVAL_MS = 100L
    private const val NOTIFICATION_INTERVAL_MS = 1000L
    private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "xdvdfs-job") }
    private val models = Tool.values().associateWith { WorkflowModel(it) }

    fun model(tool: Tool): WorkflowModel = models.getValue(tool)

    /** The tool whose job is running, if any. Only one job runs at a time. */
    var activeTool by mutableStateOf<Tool?>(null)
        private set

    private val lock = Any()
    private var pending: JobRequest? = null

    @Volatile
    private var cancelRequested = false

    // -- entry points ------------------------------------------------------------

    /** Called on the main thread when the last step of a workflow has been completed. */
    fun submit(request: JobRequest): Boolean {
        val app = XisoApp.instance
        synchronized(lock) {
            if (activeTool != null) return false
            activeTool = request.tool
            pending = request
        }
        cancelRequested = false
        model(request.tool).begin(request.outputUri, request.outputName)

        try {
            ContextCompat.startForegroundService(app, Intent(app, XisoService::class.java))
        } catch (e: Exception) {
            // The service could not be started; the job still runs, just without the
            // protection of a foreground service.
            execute(app) {}
        }
        return true
    }

    fun cancel() {
        cancelRequested = true
        try {
            Native.cancel()
        } catch (_: Throwable) {
            // library not loaded, nothing to cancel natively
        }
    }

    /**
     * Starts the pending job on the worker thread. [onDone] is invoked on the worker
     * thread once the job is over, or immediately if there is nothing to run.
     */
    fun execute(context: Context, onDone: () -> Unit) {
        val request = synchronized(lock) {
            val r = pending
            pending = null
            r
        }
        if (request == null) {
            if (activeTool == null) onDone()
            return
        }

        val app = context.applicationContext
        executor.execute {
            val power = app.getSystemService(PowerManager::class.java)
            val wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xdvdfs:job")
            try {
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                runJob(app, request)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } catch (_: Exception) {
                }
                app.getSystemService(NotificationManager::class.java)?.cancel(Notifications.ID_PROGRESS)
                activeTool = null
                onDone()
            }
        }
    }

    // -- the job -----------------------------------------------------------------

    private fun runJob(app: Context, request: JobRequest) {
        val tool = request.tool
        val model = model(tool)
        val resolver = app.contentResolver
        val host = JobHost(app, tool, model)

        var message: String? = null
        try {
            if (cancelRequested) throw JobCancelledException()
            message = when (tool) {
                Tool.Pack -> runPack(resolver, request, host)
                Tool.Unpack -> runUnpack(resolver, request, host)
                Tool.Compress -> runCompress(resolver, request, host)
            }
        } catch (e: JobCancelledException) {
            message = Native.CANCELLED
        } catch (e: Throwable) {
            message = describe(e)
        }

        host.flush()
        host.closeDirectories()

        // The output grant was only needed for this run.
        Saf.release(resolver, request.outputUri, write = true)

        val cancelled = message == Native.CANCELLED
        if (message != null && tool == Tool.Pack && !host.outputRejected) {
            // Do not leave a truncated image behind. (Never done when the output was
            // refused up front, since it could then be the user's own source file.)
            Saf.deleteQuietly(resolver, request.outputUri)
        }

        val before = model.state
        val after = when {
            message == null -> before.copy(
                phase = Phase.Finished,
                done = if (tool == Tool.Compress) before.done else maxOf(before.done, before.total),
                sectDone = maxOf(before.sectDone, before.sectTotal),
                itemName = null,
                error = null,
            )
            cancelled -> before.copy(phase = Phase.Error, itemName = null, error = "Cancelled.")
            else -> before.copy(
                phase = Phase.Error,
                itemName = null,
                error = Texts.failure(tool) + "\n\n" + withHostError(message, host.hostError),
            )
        }
        model.state = after

        when {
            message == null -> Notifications.finished(app, "XISO Packer", Texts.doneMessage(tool, after))
            cancelled -> Notifications.finished(app, "XISO Packer", "Cancelled.")
            else -> Notifications.finished(app, "XISO Packer", Texts.failure(tool))
        }
    }

    private fun withHostError(message: String, hostError: String?): String =
        if (hostError == null || message.contains(hostError)) message else "$message\n\nCaused by:\n    $hostError"

    private fun describe(e: Throwable): String {
        val chain = StringBuilder()
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 6) {
            if (depth == 0) chain.append(cause.message ?: cause.javaClass.simpleName)
            else chain.append("\n    ").append(depth - 1).append(": ").append(cause.message ?: cause.javaClass.simpleName)
            cause = cause.cause
            depth++
        }
        return chain.toString()
    }

    /** Refuses outputs that would overwrite or end up inside the source (see `cmd_pack`). */
    private fun checkOutput(request: JobRequest, host: JobHost) {
        val reject = { reason: String ->
            host.outputRejected = true
            throw java.io.IOException(reason)
        }

        if (!request.inputIsDir) {
            if (request.inputUri == request.outputUri) reject("Source and destination paths are the same")
            return
        }

        try {
            if (request.inputUri.authority == request.outputUri.authority) {
                val treeId = DocumentsContract.getTreeDocumentId(request.inputUri).trimEnd('/')
                val outId = DocumentsContract.getDocumentId(request.outputUri)
                if (outId.startsWith("$treeId/")) reject("Destination path is contained by source path")
            }
        } catch (e: IllegalArgumentException) {
            // not a document URI we can reason about, the provider decides
        }
    }

    /** Opens the input image and the output file, handing both over to the native code. */
    private inline fun withImageAndOutput(
        resolver: ContentResolver,
        request: JobRequest,
        host: JobHost,
        block: (srcFd: Int, outFd: Int) -> String?,
    ): String? {
        checkOutput(request, host)
        val src = Saf.openRead(resolver, request.inputUri)
        val out = try {
            Saf.openWrite(resolver, request.outputUri)
        } catch (t: Throwable) {
            src.close()
            throw t
        }
        if (cancelRequested) {
            src.close()
            out.close()
            throw JobCancelledException()
        }
        return block(src.detachFd(), out.detachFd())
    }

    private fun scan(resolver: ContentResolver, request: JobRequest, host: JobHost): ScanResult {
        val result = Saf.scanTree(resolver, request.inputUri) { cancelRequested }
        host.uris = result.uris
        if (cancelRequested) throw JobCancelledException()
        return result
    }

    private fun runPack(resolver: ContentResolver, request: JobRequest, host: JobHost): String? {
        if (request.inputIsDir) {
            checkOutput(request, host)
            val scan = scan(resolver, request, host)
            val out = Saf.openWrite(resolver, request.outputUri)
            return Native.packFolder(scan.manifest, out.detachFd(), host)
        }
        return withImageAndOutput(resolver, request, host) { src, out -> Native.packImage(src, out, host) }
    }

    private fun runUnpack(resolver: ContentResolver, request: JobRequest, host: JobHost): String? {
        val src = Saf.openRead(resolver, request.inputUri)
        host.writer = TreeWriter(resolver, request.outputUri)
        if (cancelRequested) {
            src.close()
            throw JobCancelledException()
        }
        return Native.unpack(src.detachFd(), host)
    }

    private fun runCompress(resolver: ContentResolver, request: JobRequest, host: JobHost): String? {
        val baseName = withExtension(request.inputName, "cso", request.inputIsDir)
        host.writer = TreeWriter(resolver, request.outputUri)

        if (request.inputIsDir) {
            val scan = scan(resolver, request, host)
            return Native.compressFolder(scan.manifest, baseName, host)
        }

        val src = Saf.openRead(resolver, request.inputUri)
        if (cancelRequested) {
            src.close()
            throw JobCancelledException()
        }
        return Native.compressImage(src.detachFd(), baseName, host)
    }

    // -- native callbacks --------------------------------------------------------

    /**
     * Receives the callbacks of the native code on the worker thread. Progress is
     * accumulated locally and published to the UI (and the notification) at a
     * limited rate, because the native side reports every single file.
     */
    private class JobHost(
        private val app: Context,
        private val tool: Tool,
        private val model: WorkflowModel,
    ) : Native.Host {
        @Volatile
        var uris: List<Uri?> = emptyList()

        @Volatile
        var writer: TreeWriter? = null

        /** Set when the output was refused before anything was written to it. */
        @Volatile
        var outputRejected = false

        /** The last failure of a callback; the native side only sees "-1". */
        @Volatile
        var hostError: String? = null

        private val resolver = app.contentResolver

        private var stage = Stage.CreatingFilesystem
        private var total = 0L
        private var done = 0L
        private var itemName: String? = null
        private var itemSize = -1L
        private var sectTotal = 0L
        private var sectDone = 0L

        private var lastUi = 0L
        private var lastNotification = 0L

        override fun openSourceFile(index: Int): Int {
            return try {
                val uri = uris.getOrNull(index) ?: throw java.io.IOException("No such source entry ($index)")
                Saf.openRead(resolver, uri).detachFd()
            } catch (t: Throwable) {
                hostError = describe(t)
                -1
            }
        }

        override fun createOutputFile(path: String): Int {
            return try {
                val w = writer ?: throw java.io.IOException("No output folder")
                w.createFile(path)
            } catch (t: Throwable) {
                hostError = describe(t)
                -1
            }
        }

        override fun createOutputDir(path: String): Int {
            return try {
                val w = writer ?: throw java.io.IOException("No output folder")
                w.createDir(path)
                0
            } catch (t: Throwable) {
                hostError = describe(t)
                -1
            }
        }

        override fun onProgress(kind: Int, a: Long, b: Long, text: String?) {
            var force = false
            when (kind) {
                Native.EV_TOTAL -> total = a
                Native.EV_ITEM -> {
                    done = a
                    itemSize = b
                    itemName = text
                }
                Native.EV_STAGE -> {
                    force = true
                    when (a) {
                        Native.ST_PACKING -> stage = Stage.PackingImage
                        Native.ST_COMPRESSING -> stage = Stage.Compressing
                        Native.ST_FLUSH -> {
                            // When compressing, the flush after packing is immediately
                            // followed by the actual compression.
                            if (!(tool == Tool.Compress && stage != Stage.Compressing)) {
                                stage = Stage.WaitingForFlush
                            }
                        }
                    }
                }
                Native.EV_SECT_TOTAL -> {
                    sectTotal = a
                    force = true
                }
                Native.EV_SECT_DONE -> sectDone = a
            }
            publish(force)
        }

        fun flush() = publish(true)

        fun closeDirectories() {
            writer = null
        }

        private fun publish(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastUi < UI_INTERVAL_MS) return
            lastUi = now

            val next = model.state.copy(
                stage = stage,
                total = total,
                done = done,
                itemName = itemName,
                itemSize = itemSize,
                sectTotal = sectTotal,
                sectDone = sectDone,
            )
            model.state = next

            if (now - lastNotification >= NOTIFICATION_INTERVAL_MS) {
                lastNotification = now
                val percent = if (next.total > 0 || next.sectTotal > 0) Texts.percent(tool, next) else -1
                val text = Texts.item(tool, next) ?: Texts.stage(next)
                Notifications.updateProgress(
                    app,
                    Notifications.progress(app, Texts.notificationTitle(tool), text, percent),
                )
            }
        }
    }

    /** Text for the notification the service shows before the first progress arrives. */
    fun initialNotification(context: Context) = Notifications.progress(
        context,
        activeTool?.let { Texts.notificationTitle(it) } ?: "XISO Packer",
        "Starting…",
        -1,
    )
}
