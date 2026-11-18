package com.doro.organizer.core.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Forwards the wall-clock, date and zone change broadcasts to [onTimeChanged] (architecture.md § Time, trigger 3).
 * Registered at runtime only, never in the manifest.
 */
class TimeChangeReceiver(private val onTimeChanged: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in ACTIONS) onTimeChanged()
    }

    companion object {
        private val ACTIONS =
            setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED)

        /**
         * Registers [receiver] without RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED: only the system sends these
         * protected broadcasts, so they are exempt from the API 34 flag rule (architecture.md § Time). The filter is
         * built here, action by action, so lint can see that every action is protected.
         */
        fun register(context: Context, receiver: TimeChangeReceiver) {
            val filter = IntentFilter(Intent.ACTION_TIME_CHANGED)
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
            filter.addAction(Intent.ACTION_DATE_CHANGED)
            context.registerReceiver(receiver, filter)
        }
    }
}
