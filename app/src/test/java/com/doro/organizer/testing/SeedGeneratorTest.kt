package com.doro.organizer.testing

import com.doro.organizer.model.EpochDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seed of testing.md § 7 has the shape its oracles and performance tests rely on, and is deterministic. */
class SeedGeneratorTest {

    @Test
    fun sameSeed_sameModel() {
        val again = SeedGenerator().generate()

        assertEquals(notes.map(::fingerprint), again.notes.map(::fingerprint))
        assertEquals(model.persons.map { it.name to it.createdAt }, again.persons.map { it.name to it.createdAt })
    }

    @Test
    fun counts() {
        assertEquals((1L..10_000L).toList(), notes.map { it.id })
        assertEquals((1L..300L).toList(), model.persons.map { it.id })
        assertEquals((1L..100L).toList(), model.projects.map { it.id })
        assertEquals(2_000, notes.count { 1L in it.personIds })
        assertEquals(1_500, notes.count { 1L in it.projectIds })
    }

    @Test
    fun proportions() {
        assertShare("orphans", 0.25, notes.count { it.personIds.isEmpty() && it.projectIds.isEmpty() })
        assertShare("checked", 0.20, notes.count { it.checked })
        assertShare("dated", 0.50, notes.count { it.date != null })
        assertShare("empty", 0.02, notes.count { it.content.isEmpty() })
        assertShare("whitespace-only", 0.01, notes.count { it.content.isNotEmpty() && it.content.isBlank() })
        assertEquals(5, notes.count { it.content.length == SeedGenerator.MAX_CONTENT_CHARS })
    }

    @Test
    fun rowsSatisfyTheDatabaseInvariants() {
        notes.forEach { note ->
            assertTrue("$note content", note.content.length <= SeedGenerator.MAX_CONTENT_CHARS)
            assertTrue("$note lone surrogate", note.content.isEmpty() || !note.content.last().isHighSurrogate())
            assertTrue("$note timestamps", note.createdAt <= note.updatedAt)
            assertTrue("$note checked_at", note.checkedAt == null || note.checkedAt >= note.createdAt)
            assertTrue("$note date", note.date == null || note.date in EpochDays.MIN..EpochDays.MAX)
            assertTrue("$note persons", note.personIds.all { it in 1L..300L } && note.personIds.size <= 3)
            assertTrue("$note projects", note.projectIds.all { it in 1L..100L } && note.projectIds.size <= 2)
        }
        (model.persons + model.projects).forEach { entity ->
            assertTrue("$entity name", entity.name.isNotEmpty() && entity.name == entity.name.trim())
        }
        assertTrue("duplicate names", model.persons.map { it.name }.toSet().size < model.persons.size)
    }

    @Test
    fun everyTieBreakerOfTheCanonicalOrderIsExercised() {
        val unchecked = notes.filterNot { it.checked }
        val sharedBoth = unchecked.groupBy { it.updatedAt to it.createdAt }.values.filter { it.size > 1 }
        val sharedUpdate = unchecked.groupBy { it.updatedAt }.values.filter { group ->
            group.size > 1 && group.map { it.createdAt }.toSet().size > 1
        }
        val sharedCheck = notes.filter { it.checked }.groupBy { it.checkedAt }.values.filter { it.size > 1 }

        assertTrue("ties on updated_at and created_at: ${sharedBoth.size}", sharedBoth.size >= 50)
        assertTrue("ties on updated_at only: ${sharedUpdate.size}", sharedUpdate.size >= 50)
        assertTrue("ties on checked_at: ${sharedCheck.size}", sharedCheck.size >= 10)
    }

    @Test
    fun contentHoldsEveryScriptAndLikeWildcard() {
        val text = notes.joinToString(" ") { it.content.take(2_000) }

        listOf("%", "_", "\\", "Ä", "ł", "Привет", "Σ", "😀").forEach { assertTrue(it, text.contains(it)) }
    }

    private fun assertShare(label: String, expected: Double, count: Int) {
        val share = count.toDouble() / notes.size
        assertTrue("$label: $share", share in expected * 0.75..expected * 1.25)
    }

    private fun fingerprint(note: SeedNote) = listOf(
        note.id, note.date, note.checkedAt, note.createdAt, note.updatedAt, note.content.hashCode(),
        note.personIds, note.projectIds,
    )

    private companion object {
        /** Generated once for the class: the model is immutable and holds about ten million characters. */
        val model: SeedModel by lazy { SeedGenerator().generate() }
        val notes: List<SeedNote> get() = model.notes
    }
}
