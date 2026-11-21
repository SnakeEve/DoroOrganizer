package com.doro.organizer.ui.notes

private const val END_PICKER = "DateEndPicker"

/**
 * The saved form of a [ListDialog] (ui.md § 3.10, key `listDialog`): its simple name, with the end for
 * [ListDialog.DateEndPicker], for example `"DateEndPicker:From"`.
 */
internal fun ListDialog.toKey(): String = when (this) {
    ListDialog.DateFilterSheet -> "DateFilterSheet"
    is ListDialog.DateEndPicker -> "$END_PICKER:${end.name}"
    ListDialog.RangePicker -> "RangePicker"
}

/** The [ListDialog] of a saved [key]; null for none, or for a key this version does not know. */
internal fun listDialogOf(key: String?): ListDialog? = when {
    key == null -> null
    key == "DateFilterSheet" -> ListDialog.DateFilterSheet
    key == "RangePicker" -> ListDialog.RangePicker
    key.startsWith("$END_PICKER:") ->
        RangeEnd.entries.firstOrNull { it.name == key.removePrefix("$END_PICKER:") }?.let(ListDialog::DateEndPicker)
    else -> null
}
