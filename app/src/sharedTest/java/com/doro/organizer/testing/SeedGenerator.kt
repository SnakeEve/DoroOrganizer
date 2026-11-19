package com.doro.organizer.testing

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Random

/**
 * The deterministic dataset of testing.md § 7, pure Kotlin: the same [seed] gives the same [SeedModel] on the JVM and
 * on the device (`java.util.Random` is specified bit for bit). For the default 10,000 notes:
 * - 300 persons and 100 projects; person 1 has 2,000 notes and project 1 has 1,500; some names repeat (§1);
 * - about 25 % orphans; every other note has 0–3 persons and 0–2 projects, at least one link;
 * - about 20 % checked; about 50 % dated within [BASE_DAY] ± 365 days, the rest undated;
 * - content: about 2 % empty, 1 % whitespace-only, five notes of 200,000 UTF-16 units, the rest 20–2,000 units
 *   mixing ASCII, German, Polish, Cyrillic, Greek, emoji and literal `%`, `_` and `\`;
 * - ties: about 1 % of unchecked notes share `updated_at` and `created_at` with another note, 1 % share only
 *   `updated_at`, and 1 % of checked notes share `checked_at`, so every tie-breaker of DEC-23 is exercised.
 */
class SeedGenerator(private val seed: Long = 42, private val notes: Int = 10_000) {
    /** Five notes of [MAX_CONTENT_CHARS], spread over the id range. */
    private val longNoteIds: Set<Long> = (0 until 5).map { 1L + it * (notes / 5) + notes / 10 }.toSet()

    fun generate(): SeedModel {
        val random = Random(seed)
        val persons = entities(random, PERSONS, PERSON_NAMES)
        val projects = entities(random, PROJECTS, PROJECT_NAMES)
        val drafts = List(notes) { draft(random, id = it + 1L) }
        link(random, drafts)
        tie(random, drafts)
        return SeedModel(drafts.map { it.toNote() }, persons, projects)
    }

    private fun entities(random: Random, count: Int, names: List<String>): List<SeedEntity> = List(count) { i ->
        // Every tenth entity repeats the name of the one nine before it: duplicates are allowed (§1, DEC-32).
        val n = if (i % 10 == 9) i - 9 else i
        val name = "${names[n % names.size]} ${n / names.size}"
        SeedEntity(id = i + 1L, name = name, createdAt = BASE_MILLIS - random.nextInt(DAYS) * DAY_MILLIS)
    }

    private fun draft(random: Random, id: Long): Draft {
        val createdAt = BASE_MILLIS - (random.nextDouble() * DAYS * DAY_MILLIS).toLong()
        val updatedAt = createdAt + (random.nextDouble() * minOf(30 * DAY_MILLIS, BASE_MILLIS - createdAt)).toLong()
        val checkedAt = if (random.nextDouble() < 0.2) {
            updatedAt + (random.nextDouble() * (BASE_MILLIS - updatedAt)).toLong()
        } else {
            null
        }
        val date = if (random.nextBoolean()) BASE_DAY + random.nextInt(2 * DAYS + 1) - DAYS else null
        return Draft(id, date, checkedAt, createdAt, updatedAt, content(random, id))
    }

    private fun content(random: Random, id: Long): String {
        if (id in longNoteIds) return words(random, MAX_CONTENT_CHARS)
        val kind = random.nextDouble()
        return when {
            kind < 0.02 -> ""
            kind < 0.03 -> String(CharArray(1 + random.nextInt(20)) { WHITESPACE[random.nextInt(WHITESPACE.size)] })
            else -> words(random, 20 + random.nextInt(1_981))
        }
    }

    /**
     * Words from [WORDS], cut to exactly [length] UTF-16 units. A cut inside an emoji ends in `x` instead of a lone
     * high surrogate, so the long notes sit exactly at the cap of DEC-24.
     */
    private fun words(random: Random, length: Int): String {
        val text = StringBuilder(length + 16)
        while (text.length < length) {
            if (text.isNotEmpty()) text.append(if (random.nextInt(12) == 0) '\n' else ' ')
            text.append(WORDS[random.nextInt(WORDS.size)])
        }
        text.setLength(length)
        if (text.last().isHighSurrogate()) text.setCharAt(length - 1, 'x')
        return text.toString()
    }

    private fun link(random: Random, drafts: List<Draft>) {
        val linked = drafts.filter { random.nextDouble() >= 0.25 }
        val heavyPerson = linked.shuffled(random).take(notes / 5).toSet()
        val heavyProject = linked.shuffled(random).take(notes * 3 / 20).toSet()
        linked.forEach { draft ->
            do {
                draft.personIds = pick(random, if (draft in heavyPerson) 2 else 3, 2..PERSONS)
                draft.projectIds = pick(random, if (draft in heavyProject) 1 else 2, 2..PROJECTS)
                if (draft in heavyPerson) draft.personIds += HEAVY_PERSON
                if (draft in heavyProject) draft.projectIds += HEAVY_PROJECT
            } while (draft.personIds.isEmpty() && draft.projectIds.isEmpty())
        }
    }

    /** 0..[max] distinct ids from [range]. */
    private fun pick(random: Random, max: Int, range: IntRange): Set<Long> =
        List(random.nextInt(max + 1)) { (range.first + random.nextInt(range.last - range.first + 1)).toLong() }.toSet()

    private fun tie(random: Random, drafts: List<Draft>) {
        val (checked, unchecked) = drafts.partition { it.checkedAt != null }
        val shuffled = unchecked.shuffled(random)
        val share = unchecked.size / 100
        shuffled.take(share).forEach { draft ->
            val other = unchecked[random.nextInt(unchecked.size)]
            draft.createdAt = other.createdAt
            draft.updatedAt = other.updatedAt
        }
        shuffled.drop(share).take(share).forEach { draft ->
            val other = unchecked[random.nextInt(unchecked.size)]
            draft.updatedAt = other.updatedAt
            draft.createdAt = minOf(draft.createdAt, other.updatedAt)
        }
        checked.shuffled(random).take(checked.size / 100).forEach { draft ->
            val checkedAt = checked[random.nextInt(checked.size)].checkedAt!!
            draft.checkedAt = checkedAt
            draft.createdAt = minOf(draft.createdAt, checkedAt) // a note is checked after it exists
        }
    }

    private class Draft(
        val id: Long,
        val date: Long?,
        var checkedAt: Long?,
        var createdAt: Long,
        var updatedAt: Long,
        val content: String,
    ) {
        var personIds: Set<Long> = emptySet()
        var projectIds: Set<Long> = emptySet()

        fun toNote() = SeedNote(id, date, checkedAt, createdAt, updatedAt, content, personIds, projectIds)
    }

    companion object {
        /** The default `FakeAppTime` start: every seeded timestamp lies before it. */
        val BASE_MILLIS: Long = Instant.parse("2026-11-17T09:00:00Z").toEpochMilli()

        /** 2026-11-17, the centre of the seeded dates. */
        val BASE_DAY: Long = LocalDate.of(2026, 11, 17).toEpochDay()

        /** The 200,000-unit content cap of DEC-24, copied so a changed constant fails the seed's invariants. */
        const val MAX_CONTENT_CHARS = 200_000

        const val PERSONS = 300
        const val PROJECTS = 100

        /** The person linked to 2,000 notes. */
        const val HEAVY_PERSON = 1L

        /** The project linked to 1,500 notes. */
        const val HEAVY_PROJECT = 1L

        /** Words that the search tests and oracles reuse; every script and LIKE wildcard of testing.md § 7. */
        val WORDS = listOf(
            "meeting", "Budget", "follow-up", "TODO", "review", "Q3", "Ärger", "Straße", "Übergabe", "Łódź",
            "zażółć", "gęślą", "Привет", "отчёт", "ЗАДАЧА", "ΣΟΦΙΑ", "οδός", "Αθήνα", "\uD83D\uDE00", "\uD83D\uDE80",
            "50%", "a_b", "C:\\x", "100%_done", "back\\slash", "naïve", "café",
        )

        private const val DAYS = 365
        private val DAY_MILLIS = Duration.ofDays(1).toMillis()
        private val WHITESPACE = charArrayOf(' ', '\n', '\t', '\u00A0', '\u2003', '\u3000')
        private val PERSON_NAMES = listOf("Anna", "Bartosz", "Zoë", "Ольга", "Νίκος", "O'Brien", "Jürgen", "Li")
        private val PROJECT_NAMES = listOf("Apollo", "Gemini", "Übersicht", "Проект", "Ἀρχή", "Q3 \"plan\"")
    }
}
