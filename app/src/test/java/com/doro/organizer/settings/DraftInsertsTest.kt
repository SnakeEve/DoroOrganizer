package com.doro.organizer.settings

import com.doro.organizer.model.DraftInsert
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `nav.draft_inserts` value of architecture.md § SettingsStore: encoding, replacement and the cap of eight. */
class DraftInsertsTest {
    private val record =
        DraftInsert(noteId = 7, createdPersons = mapOf("u1" to 1L, "u2" to 2L), createdProjects = mapOf("u3" to 3L))

    @Test
    fun encodeThenDecode_roundTrips() {
        val records = listOf("a" to record, "b" to DraftInsert(8, emptyMap(), emptyMap()))

        assertEquals(records, DraftInserts.decode(DraftInserts.encode(records)))
    }

    @Test
    fun decode_absentOrMalformed_isEmpty() {
        assertEquals(emptyList<Pair<String, DraftInsert>>(), DraftInserts.decode(null))
        assertEquals(emptyList<Pair<String, DraftInsert>>(), DraftInserts.decode("{not json"))
        assertEquals(emptyList<Pair<String, DraftInsert>>(), DraftInserts.decode("""[{"key":"a"}]"""))
    }

    @Test
    fun with_appendsAsNewest_andReplacesTheSameKey() {
        val first = DraftInserts.with(emptyList(), "a", record)
        val second = DraftInserts.with(first, "b", record)

        val replaced = DraftInserts.with(second, "a", record.copy(noteId = 9))

        assertEquals(listOf("b", "a"), replaced.map { it.first })
        assertEquals(9L, replaced.last().second.noteId)
    }

    @Test
    fun with_keepsOnlyTheNewestEight() {
        val records = (1..9).fold(emptyList<Pair<String, DraftInsert>>()) { acc, i ->
            DraftInserts.with(acc, "k$i", record.copy(noteId = i.toLong()))
        }

        assertEquals((2..9).map { "k$it" }, records.map { it.first })
    }
}
