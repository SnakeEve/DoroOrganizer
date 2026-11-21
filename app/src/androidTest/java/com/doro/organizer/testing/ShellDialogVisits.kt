package com.doro.organizer.testing

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import com.doro.organizer.security.SlotOffer
import kotlinx.coroutines.runBlocking

/**
 * Over a launched `Ready` shell, shows the shell's dialogs one after another (ui.md § 2.4): the biometric-lock auto-off
 * notice, the kekBroken notice and the fingerprint set-up offer for each [SlotOffer], and calls [visit] in each while
 * it is shown; each is gone before the next appears.
 */
fun ScriptedGateComposeTest.visitShellDialogs(visit: (name: String) -> Unit) {
    runBlocking { scene.settings.setLockAutoOff() }
    ui.awaitText(AUTO_OFF_TITLE)
    visit("auto-off notice")
    runBlocking { scene.settings.clearLockAutoOffNotice() }
    compose.waitUntil(GateUi.TIMEOUT_MS) { compose.onAllNodesWithText(AUTO_OFF_TITLE).fetchSemanticsNodes().isEmpty() }

    shellDialog(KEK_NOTICE, "kekBroken notice", visit) { shown -> scene.lock.kekNotice.value = shown }
    for (offer in SlotOffer.entries) {
        shellDialog(SLOT_OFFER, "set-up offer $offer", visit) { shown ->
            scene.lock.slotOffer.value = offer.takeIf { shown }
        }
    }
}

private fun ScriptedGateComposeTest.shellDialog(
    tag: String,
    name: String,
    visit: (String) -> Unit,
    show: (Boolean) -> Unit,
) {
    show(true)
    ui.awaitTag(tag)
    visit(name)
    show(false)
    compose.waitUntil(GateUi.TIMEOUT_MS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
}

private const val KEK_NOTICE = "kek_notice"
private const val SLOT_OFFER = "slot_offer"

/** Literal copied from ui.md § 11. */
private const val AUTO_OFF_TITLE = "Biometric lock turned off"
