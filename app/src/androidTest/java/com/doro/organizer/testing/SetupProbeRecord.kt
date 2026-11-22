package com.doro.organizer.testing

import android.util.Log

/**
 * The record channel of the setup probes (SPEC T22) for a value the docs leave open: a "Verify at setup" item that asks
 * which value the device shows, where the design accepts more than one. The probe asserts what the docs require and
 * writes the exact observed value here, so a docs-consistent answer is recorded instead of failing the test. Each line
 * goes to logcat under [TAG] as `<probe>: <observed>`, which a device run captures with `adb logcat`.
 * Both parts are constant text plus pragma values, counts, sizes and file suffixes, never user data (DEC-70).
 */
object SetupProbeRecord {
    const val TAG = "DoroSetupProbe"

    fun record(probe: String, observed: String) {
        Log.i(TAG, "$probe: $observed")
    }
}
