package com.doro.organizer.debug

import android.os.Process
import com.doro.organizer.security.BioSlot
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyVault

/**
 * A vault that kills the process as soon as a commit carrying a biometric slot returned, before the caller's
 * read-back: row CS-30 of the crash-safety matrix, the one instant of a slot creation that no fault hook reaches
 * (testing.md L-15). Only slot creations over a key-state without a slot use it, so the slot it sees is the new one.
 * Debug-only, because the `:crashtest` process cannot load test classes.
 */
class SlotCommitKillingVault(private val delegate: KeyVault) : KeyVault by delegate {
    override suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?) {
        delegate.commit(generation, slot, bio)
        if (bio != null) Process.killProcess(Process.myPid())
    }
}
