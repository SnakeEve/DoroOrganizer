package com.doro.organizer.testing

import java.time.Instant
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransition

/** A zone whose 2026 DST gap starts at local midnight, so that day has no 00:00 (testing.md I-28, I-30). */
class MidnightGap(val zone: ZoneId, val transition: ZoneOffsetTransition) {
    companion object {
        private val from = Instant.parse("2026-01-01T00:00:00Z")
        private val until = Instant.parse("2027-01-01T00:00:00Z")

        /** The first such zone by id; fails when the JDK's time-zone data has none, as the catalog requires. */
        fun in2026(): MidnightGap = ZoneId.getAvailableZoneIds().sorted().asSequence()
            .map(ZoneId::of)
            .flatMap { zone -> transitions2026(zone).map { MidnightGap(zone, it) } }
            .firstOrNull { it.transition.isGap && it.transition.dateTimeBefore.toLocalTime().toSecondOfDay() == 0 }
            ?: throw AssertionError("no zone with a DST gap at local midnight in 2026")

        private fun transitions2026(zone: ZoneId): Sequence<ZoneOffsetTransition> =
            generateSequence(zone.rules.nextTransition(from)) { zone.rules.nextTransition(it.instant) }
                .takeWhile { it.instant < until }
    }
}
