package com.doro.organizer.debug

import com.doro.organizer.security.BioSlot
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.KeyVault

/**
 * A vault whose read right after the next commit throws [failure] once, for the `CommitOutcome.Unknown` cases of
 * testing.md L-14, L-15 and L-16 (a read-back that fails although the commit landed). Debug-only, because the
 * `:crashtest` process of L-15 cannot load test classes.
 */
class ReadBackFailingVault(private val delegate: KeyVault, private val failure: () -> Throwable) : KeyVault by delegate {
    /** Set by the test: the next commit arms one failing read. */
    @Volatile
    var failReadAfterNextCommit = false

    @Volatile
    private var armed = false

    override suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?) {
        delegate.commit(generation, slot, bio)
        if (failReadAfterNextCommit) {
            failReadAfterNextCommit = false
            armed = true
        }
    }

    override suspend fun readState(): KeyState? {
        if (armed) {
            armed = false
            throw failure()
        }
        return delegate.readState()
    }
}
