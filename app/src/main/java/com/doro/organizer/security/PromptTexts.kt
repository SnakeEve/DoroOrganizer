package com.doro.organizer.security

import android.content.Context
import com.doro.organizer.R

/** The resolved titles and button texts of the system prompts (ui.md § 8.11), so the controller needs no `Context`. */
class PromptTexts(
    val unlockTitle: String,
    val enableTitle: String,
    val disableTitle: String,
    val slotTitle: String,
    val resetTitle: String,
    val cancel: String,
    val usePassword: String,
) {
    override fun toString() = "PromptTexts"

    companion object {
        fun from(context: Context) = PromptTexts(
            unlockTitle = context.getString(R.string.biometric_unlock_title),
            enableTitle = context.getString(R.string.biometric_enable_title),
            disableTitle = context.getString(R.string.biometric_disable_title),
            slotTitle = context.getString(R.string.biometric_slot_title),
            resetTitle = context.getString(R.string.biometric_reset_title),
            cancel = context.getString(R.string.action_cancel),
            usePassword = context.getString(R.string.action_use_password),
        )
    }
}
