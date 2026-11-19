package com.doro.organizer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** db-lifecycle.md § Erase step 5: the committed generation set is deleted last, whatever its number. */
class ErasureOrderTest {
    @Test
    fun theCommittedGeneration_comesLast_alsoBelowAHigherLeftover() {
        assertEquals(listOf(3L, 9L, 5L), erasureOrder(setOf(9L, 5L, 3L), committed = 5L))
    }

    @Test
    fun withoutACommittedGeneration_orWithItGone_everySetIsDeletedInNumberOrder() {
        assertEquals(listOf(2L, 7L), erasureOrder(setOf(7L, 2L), committed = null))
        assertEquals(listOf(2L, 7L), erasureOrder(setOf(7L, 2L), committed = 4L))
        assertEquals(emptyList<Long>(), erasureOrder(emptySet(), committed = 1L))
    }
}
