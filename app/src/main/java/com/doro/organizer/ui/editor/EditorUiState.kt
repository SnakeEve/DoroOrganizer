package com.doro.organizer.ui.editor

import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.model.EntityKind
import java.time.LocalDate

/**
 * The one state of the editor (ui.md § 6.14). The content text is not here: it lives in
 * `EditorViewModel.content`, the field's `TextFieldState` (ui.md § 6.3). While [loading] nothing of the draft is
 * shown and Save and the menu are disabled. [closed] asks the screen to leave: the note is gone, was deleted, or a
 * Save finished (ui.md § 6.8, § 6.12).
 */
data class EditorUiState(
    val loading: Boolean,
    val isNew: Boolean,
    val noteId: Long?,
    val date: LocalDate?,
    val overdue: Boolean,
    val checked: Boolean,
    val people: List<LinkChip>,
    val projects: List<LinkChip>,
    val dialog: EditorDialog?,
    val message: UserMessage?,
    val closed: Boolean,
) {
    override fun toString() = "EditorUiState(noteId=$noteId)" // DEC-70
}

/**
 * A chip of the association area (ui.md § 6.4): [id] for a stored entity, [newKey] for one created in a picker during
 * this edit, which is inserted only with the note save (DEC-33).
 */
data class LinkChip(val id: Long?, val newKey: String?, val name: String) {
    override fun toString() = "LinkChip(id=$id)" // DEC-70
}

/** The dialog or sheet the editor shows (ui.md § 6.14), saved as the String key `editorDialog`. */
sealed interface EditorDialog {
    data object DeleteNote : EditorDialog

    data object Unlink : EditorDialog

    data object DatePicker : EditorDialog

    /** The impact dialog of DEC-34 (ui.md § 8.4): [n] linked notes, [m] of them would become orphans. */
    data class DeleteImpact(val kind: EntityKind, val id: Long, val name: String, val n: Int, val m: Int) : EditorDialog {
        override fun toString() = "DeleteImpact($kind, id=$id, n=$n, m=$m)" // DEC-70
    }
}

/** The saved form of an [EditorDialog]: a restored impact dialog keeps only its kind and id (ui.md § 6.14). */
internal sealed interface SavedDialog {
    data class Plain(val dialog: EditorDialog) : SavedDialog {
        override fun toString() = "Plain($dialog)"
    }

    /** Re-read with `personDeleteImpact` / `projectDeleteImpact`, so a restored dialog never shows stale counts. */
    data class Impact(val kind: EntityKind, val id: Long) : SavedDialog {
        override fun toString() = "Impact($kind, id=$id)"
    }
}

/** `DeleteNote`, `Unlink`, `DatePicker`, or `DeleteImpact:<kind>:<id>`. */
internal fun EditorDialog.toKey(): String = when (this) {
    is EditorDialog.DeleteImpact -> "$IMPACT:${kind.name}:$id"
    EditorDialog.DeleteNote -> "DeleteNote"
    EditorDialog.Unlink -> "Unlink"
    EditorDialog.DatePicker -> "DatePicker"
}

/** The dialog that [key] names; an unknown key restores as no dialog. */
internal fun savedDialogOf(key: String?): SavedDialog? {
    val parts = key?.split(':') ?: return null
    return when (parts[0]) {
        "DeleteNote" -> SavedDialog.Plain(EditorDialog.DeleteNote)
        "Unlink" -> SavedDialog.Plain(EditorDialog.Unlink)
        "DatePicker" -> SavedDialog.Plain(EditorDialog.DatePicker)
        IMPACT -> {
            val kind = parts.getOrNull(1)?.let { name -> EntityKind.entries.firstOrNull { it.name == name } }
            val id = parts.getOrNull(2)?.toLongOrNull()
            if (kind != null && id != null && parts.size == 3) SavedDialog.Impact(kind, id) else null
        }
        else -> null
    }
}

private const val IMPACT = "DeleteImpact"
