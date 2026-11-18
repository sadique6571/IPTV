package com.sadique.iptv

import android.os.Handler
import android.os.Looper

/** Stops playback after a chosen delay. In-memory only: leaving the app cancels it. */
internal class SleepTimer(private val onFire: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    var minutesRemaining: Int = 0
        private set

    fun start(minutes: Int) {
        cancel()
        minutesRemaining = minutes
        val task = Runnable {
            minutesRemaining = 0
            runnable = null
            onFire()
        }
        runnable = task
        handler.postDelayed(task, minutes * 60_000L)
    }

    fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        minutesRemaining = 0
    }
}
