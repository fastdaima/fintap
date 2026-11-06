package com.fintap

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Back-tap decision log, for diagnosing misfires after the fact.
// Lives in Android/data/com.fintap/files/ so `adb pull` can read it from a release build:
//   decisions.log      every edge verdict, pair cancel, FIRE, and what the user did with the popup
//   decisions.1.log    previous file after rotation (512 KB)
//   fire-<id>.bin      raw accelerometer, last ~2.6 s before each FIRE (same format as calib/*.bin)
object DecisionLog {
    const val MAX_BYTES = 512 * 1024L
    const val MAX_SNAPSHOTS = 20

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    fun stamp(): String = LocalDateTime.now().format(TIME)

    fun dir(ctx: Context): File {
        val d = ctx.getExternalFilesDir(null)
        check(d != null) { "external files dir unavailable" }
        return d
    }

    // Appends pre-formatted lines (each ending in '\n'). Rotates once the file passes MAX_BYTES.
    fun append(ctx: Context, lines: String) {
        val d = dir(ctx)
        val f = File(d, "decisions.log")
        if (f.length() > MAX_BYTES) {
            val old = File(d, "decisions.1.log")
            old.delete()
            check(f.renameTo(old)) { "log rotate failed" }
        }
        FileOutputStream(f, true).use { it.write(lines.toByteArray()) }
    }

    // Keeps only the newest MAX_SNAPSHOTS fire-*.bin files.
    fun pruneSnapshots(ctx: Context) {
        val snaps = dir(ctx).listFiles { f -> f.name.startsWith("fire-") && f.name.endsWith(".bin") } ?: return
        if (snaps.size <= MAX_SNAPSHOTS) return
        snaps.sortBy { it.name } // fire-<epoch ms>.bin sorts chronologically
        for (i in 0 until snaps.size - MAX_SNAPSHOTS) snaps[i].delete()
    }
}
