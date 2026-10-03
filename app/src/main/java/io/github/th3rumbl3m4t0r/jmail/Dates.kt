package io.github.th3rumbl3m4t0r.jmail

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FMT_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val FMT_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val FMT_FULL = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH)

/** The list's date column: time today, day and month this year, else the date. */
fun fmtShort(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (ms <= 0) return ""
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    return when {
        t.toLocalDate() == today -> t.format(FMT_TIME)
        t.year == today.year -> t.format(FMT_DAY).lowercase()
        else -> t.toLocalDate().toString()
    }
}

fun fmtFull(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (ms <= 0) "" else Instant.ofEpochMilli(ms).atZone(zone).format(FMT_FULL).lowercase()

fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} kB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

fun ago(at: Long, now: Long): String {
    val s = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
