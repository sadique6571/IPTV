package com.sadique.iptv

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Network speed + today's data usage for the header status label
 * (v2.0.44): "500 Kbps/30 MB" — live device speed, then the total
 * internet used today. The daily counter resets itself at midnight
 * (the stored date is checked on every tick) and survives app
 * restarts and reboots: the accumulated total is kept in
 * SharedPreferences, and a counter reset (reboot) only re-baselines
 * without wiping the day's total.
 *
 * Both numbers use the device-wide TrafficStats counters (all apps),
 * like a status-bar speed meter — on the TV that is mostly this app.
 * Speed is measured from the byte delta between consecutive ticks
 * (call tick() once a second); call reset() when the ticker stops so
 * the first tick after a long pause does not average over the gap.
 */
internal object NetworkUsage {

    private const val PREFS = "network_usage"
    private const val KEY_DATE = "date"
    private const val KEY_DAILY = "daily_bytes"
    private const val KEY_LAST = "last_total_bytes"

    private var lastTotal = -1L
    private var lastTickMs = -1L

    /** Clears the in-memory speed state (call when the ticker stops). */
    internal fun reset() {
        lastTotal = -1L
        lastTickMs = -1L
    }

    /** One tick: returns "500 Kbps/30 MB". Call once per second. */
    internal fun tick(context: Context): String {
        val total = TrafficStats.getTotalRxBytes().coerceAtLeast(0) +
            TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val storedDate = prefs.getString(KEY_DATE, null)
        var daily = prefs.getLong(KEY_DAILY, 0L)
        val last = prefs.getLong(KEY_LAST, -1L)
        if (storedDate != today) {
            // New day: reset the counter and re-baseline.
            daily = 0L
            prefs.edit()
                .putString(KEY_DATE, today)
                .putLong(KEY_DAILY, 0L)
                .putLong(KEY_LAST, total)
                .apply()
        } else if (last >= 0L && total >= last) {
            daily += total - last
            prefs.edit()
                .putLong(KEY_DAILY, daily)
                .putLong(KEY_LAST, total)
                .apply()
        } else {
            // First run, or the counters reset (reboot): re-baseline
            // without wiping the accumulated daily total.
            prefs.edit().putLong(KEY_LAST, total).apply()
        }
        val now = SystemClock.elapsedRealtime()
        var speedBps = 0L
        if (lastTotal >= 0L && lastTickMs > 0L && total >= lastTotal) {
            val dt = now - lastTickMs
            if (dt > 0) speedBps = (total - lastTotal) * 1000 / dt
        }
        lastTotal = total
        lastTickMs = now
        return "${formatSpeed(speedBps)}/${formatData(daily)}"
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val bits = bytesPerSec * 8
        return if (bits >= 1_000_000) {
            String.format(Locale.US, "%.1f Mbps", bits / 1_000_000.0)
        } else {
            "${bits / 1000} Kbps"
        }
    }

    private fun formatData(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 ->
                String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024L -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
