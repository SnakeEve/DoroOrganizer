package com.doro.organizer.data

import com.doro.organizer.testing.SENTINEL
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugRoomHooksTest {
    @Test
    fun describe_logsSqlAndArgumentCountButNoValue() {
        val sql = "INSERT INTO persons (name, created_at, updated_at) VALUES (?, ?, ?)"

        val line = DebugRoomHooks.describe(sql, listOf(SENTINEL, 1L, null))

        assertEquals("query (3 args): $sql", line)
    }
}
