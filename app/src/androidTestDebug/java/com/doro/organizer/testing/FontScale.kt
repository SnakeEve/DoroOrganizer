package com.doro.organizer.testing

import com.doro.organizer.DebugTools

/**
 * Runs [block] with `MainActivity` at [scale] times the font size (testing.md I-35): every activity [block] launches
 * gets it in its configuration, which its dialog and sheet windows inherit. Debug test sources only: the release
 * `DebugTools` that `qa` and `benchmark` compile has no font-scale seam, so no test there can pass at the phone's scale.
 */
fun atFontScale(scale: Float, block: () -> Unit) {
    DebugTools.fontScale = scale
    try {
        block()
    } finally {
        DebugTools.fontScale = null
    }
}
