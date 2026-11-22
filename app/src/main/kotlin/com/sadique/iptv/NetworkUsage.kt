package com.sadique.iptv

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import android.os.Process
import java.util.Locale

/** Header speed and daily usage for this app's Linux UID. */
internal object NetworkUsage {
    private var tracker: DailyNetworkUsage? = null

    private var lastTotal = -1L
    private var lastTickMs = -1L

    /** Clears the in-memory speed state (call when the ticker stops). */
    internal fun reset() {
        if (lastTotal >= 0L) tracker?.sample(lastTotal, force = true)
        lastTotal = -1L
        lastTickMs = -1L
    }

    /** One tick: returns "500 Kbps/30 MB". Call once per second. */
    internal fun tick(context: Context): String {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val usage = tracker ?: DailyNetworkUsage(context.applicationContext,
            context.getSharedPreferences("iptv_uid_usage", Context.MODE_PRIVATE)).also { tracker = it }
        if (rx < 0 || tx < 0) {
            reset()
            return "—/${formatData(usage.sample(-1L))}"
        }
        val total = rx + tx
        val daily = usage.sample(total)
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
