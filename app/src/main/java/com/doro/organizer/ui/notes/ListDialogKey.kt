package com.doro.organizer.ui.notes

private const val END_PICKER = "DateEndPicker"
private const val RENAME = "Rename"
private const val DELETE_ENTITY = "DeleteEntity"

/**
 * The saved form of a [ListDialog] (ui.md § 3.10, key `listDialog`): its simple name, with the end for
 * [ListDialog.DateEndPicker] or the entity id for [ListDialog.Rename] and [ListDialog.DeleteEntity], for example
 * `"DateEndPicker:From"` or `"Rename:12"`. The impact counts are not saved: a restored delete dialog reads them again.
 */
internal fun ListDialog.toKey(): String = when (this) {
    ListDialog.DateFilterSheet -> "DateFilterSheet"
    is ListDialog.DateEndPicker -> "$END_PICKER:${end.name}"
    ListDialog.RangePicker -> "RangePicker"
    ListDialog.SetDate -> "SetDate"
    ListDialog.BatchDelete -> "BatchDelete"
    is ListDialog.Rename -> "$RENAME:$id"
    is ListDialog.DeleteEntity -> "$DELETE_ENTITY:$id"
}

/** How a saved `listDialog` comes back (ui.md § 3.10). */
internal sealed interface SavedListDialog {
    /** Shown again as it was. */
    data class Shown(val dialog: ListDialog) : SavedListDialog {
        override fun toString() = "Shown($dialog)"
    }

    /** The delete dialog of entity [id], whose counts are read again before it shows (DEC-113 (6)). */
    data class DeleteEntity(val id: Long) : SavedListDialog {
        override fun toString() = "DeleteEntity(id=$id)"
    }
}

/** The dialog of a saved [key]; null for none, or for a key this version does not know. */
internal fun savedListDialogOf(key: String?): SavedListDialog? {
    val parts = key?.split(':') ?: return null
    return when (parts.size) {
        1 -> bareDialogOf(parts[0])?.let(SavedListDialog::Shown)
        2 -> dialogWithArgumentOf(parts[0], parts[1])
        else -> null
    }
}

/** The dialog saved as its name alone. */
private fun bareDialogOf(name: String): ListDialog? = when (name) {
    "DateFilterSheet" -> ListDialog.DateFilterSheet
    "RangePicker" -> ListDialog.RangePicker
    "SetDate" -> ListDialog.SetDate
    "BatchDelete" -> ListDialog.BatchDelete
    else -> null
}

/** The dialog saved as its name and [argument]: the end of a date picker, or the id of an entity. */
private fun dialogWithArgumentOf(name: String, argument: String): SavedListDialog? = when (name) {
    END_PICKER -> RangeEnd.entries.firstOrNull { it.name == argument }
        ?.let { SavedListDialog.Shown(ListDialog.DateEndPicker(it)) }
    RENAME -> argument.toLongOrNull()?.let { SavedListDialog.Shown(ListDialog.Rename(it)) }
    DELETE_ENTITY -> argument.toLongOrNull()?.let(SavedListDialog::DeleteEntity)
    else -> null
}
