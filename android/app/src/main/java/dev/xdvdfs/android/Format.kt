package dev.xdvdfs.android

import java.util.Locale

/** Mirrors Rust's `Path::with_extension` (files) and the "append" variant used for directories. */
fun withExtension(name: String, ext: String, isDir: Boolean): String {
    if (isDir) return "$name.$ext"
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) + "." + ext else "$name.$ext"
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes bytes"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
