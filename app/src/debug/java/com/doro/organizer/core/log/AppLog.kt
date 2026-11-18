package com.doro.organizer.core.log

import android.util.Log

/** Debug: the app's only logcat writer. Messages carry ids, counts and class names only (security.md § 10, DEC-70). */
object AppLog {
    private const val TAG = "Doro"

    fun d(message: String) {
        Log.d(TAG, message)
    }
}
