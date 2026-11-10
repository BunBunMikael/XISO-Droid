package dev.xdvdfs.android

/**
 * JNI bridge to the Rust library (`xdvdfs-android`).
 *
 * Every operation blocks until it is done and returns `null` on success, or an
 * error message. [CANCELLED] is returned when [cancel] stopped the operation.
 * File descriptors passed in are owned by the native side afterwards (obtain them
 * with `ParcelFileDescriptor.detachFd()`).
 */
object Native {
    const val CANCELLED = "__CANCELLED__"

    // Progress event kinds (see host.rs)
    const val EV_TOTAL = 0
    const val EV_ITEM = 1
    const val EV_STAGE = 2
    const val EV_SECT_TOTAL = 3
    const val EV_SECT_DONE = 4

    // Native stage codes (see host.rs)
    const val ST_PACKING = 1L
    const val ST_FLUSH = 2L
    const val ST_COMPRESSING = 3L

    /** Callbacks the native code makes while an operation runs (on the calling thread). */
    interface Host {
        /** Detached read-only fd for entry [index] of the folder manifest, or -1. */
        fun openSourceFile(index: Int): Int

        /** Detached writable fd for a new file at the relative [path], or -1. */
        fun createOutputFile(path: String): Int

        /** Creates the directory at the relative [path]; 0 on success, -1 on failure. */
        fun createOutputDir(path: String): Int

        fun onProgress(kind: Int, a: Long, b: Long, text: String?)
    }

    init {
        System.loadLibrary("xdvdfs_android")
    }

    external fun packImage(srcFd: Int, outFd: Int, host: Host): String?
    external fun packFolder(manifest: String, outFd: Int, host: Host): String?
    external fun unpack(srcFd: Int, host: Host): String?
    external fun compressImage(srcFd: Int, baseName: String, host: Host): String?
    external fun compressFolder(manifest: String, baseName: String, host: Host): String?
    external fun cancel()
}
