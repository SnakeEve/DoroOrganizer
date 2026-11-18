package com.doro.organizer.core.time

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** testing.md I-30, receiver part: the broadcasts are protected, so the test calls onReceive directly. */
@RunWith(AndroidJUnit4::class)
class TimeChangeReceiverTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var calls = 0
    private val receiver = TimeChangeReceiver { calls++ }

    @Test
    fun timeDateAndZoneChanges_callOnTimeChanged() {
        listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED).forEach {
            receiver.onReceive(context, Intent(it))
        }

        assertEquals(3, calls)
    }

    @Test
    fun otherActions_areIgnored() {
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_TICK))
        receiver.onReceive(context, Intent())

        assertEquals(0, calls)
    }

    /**
     * API 34+ throws SecurityException for a flagless registration unless every action is a protected broadcast, so
     * the check is that [TimeChangeReceiver.register] returns normally.
     */
    @Test
    fun register_withoutExportFlag_doesNotThrow() {
        val registered = TimeChangeReceiver {}

        TimeChangeReceiver.register(context, registered)

        context.unregisterReceiver(registered)
    }
}
