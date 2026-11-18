package com.doro.organizer.gates.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R8OutputsTest {
    @Test
    fun logStrippingRule_mustBeAnActiveLine() {
        assertTrue(R8Outputs.hasLogStrippingRule(sequenceOf("# app", "  -maximumremovedandroidloglevel 7  ", "-dontwarn x")))
        assertFalse(R8Outputs.hasLogStrippingRule(sequenceOf("# -maximumremovedandroidloglevel 7")))
        assertFalse(R8Outputs.hasLogStrippingRule(sequenceOf("-maximumremovedandroidloglevel 5")))
    }

    @Test
    fun originalClassNames_readsClassLinesOnly() {
        val mapping = sequenceOf(
            "# compiler: R8",
            "androidx.compose.runtime.Recomposer -> rz:",
            "# {\"id\":\"sourceFile\",\"fileName\":\"Recomposer.kt\"}",
            "    int state -> a",
            "    1:4:void run():10:13 -> b",
            "com.doro.organizer.MainActivity -> com.doro.organizer.MainActivity:\r",
        )
        assertEquals(
            mapOf("rz" to "androidx.compose.runtime.Recomposer", "com.doro.organizer.MainActivity" to "com.doro.organizer.MainActivity"),
            R8Outputs.originalClassNames(mapping),
        )
    }
}
