package com.doro.organizer.model

/**
 * The outcome of a batch action (data-layer.md § Batch operations): [selected] distinct note ids were passed in,
 * which the snackbar shows (DEC-36), and [changed] rows were written or deleted.
 */
data class BatchResult(val selected: Int, val changed: Int) {
    override fun toString() = "BatchResult(selected=$selected, changed=$changed)"
}
