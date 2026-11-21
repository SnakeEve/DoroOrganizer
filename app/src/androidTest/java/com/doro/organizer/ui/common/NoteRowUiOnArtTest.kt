package com.doro.organizer.ui.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.NoteRow
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The row title on ART (DEC-24, DEC-39): ART's `Char.isWhitespace()` counts U+180E as whitespace, the app's
 * `TextWhitespace` does not, so a note holding only U+180E has that title here too, not "(No data)". The JVM's rule
 * agrees with `TextWhitespace`, which is why this case runs on the device.
 */
@RunWith(AndroidJUnit4::class)
class NoteRowUiOnArtTest {
    private val today = LocalDate.of(2026, 11, 17)

    @Test
    fun mongolianVowelSeparator_isText_notWhitespace() {
        val ui = row("᠎\n ᠎ x").toRowUi(today)

        assertEquals("᠎", ui.title)
        assertEquals("᠎ x", ui.body)
    }

    @Test
    fun appWhitespaceOnly_isBlank() {
        assertNull(row("   　\n\t").toRowUi(today).title)
    }

    private fun row(preview: String) = NoteRow(1, preview, null, false, null, 0, 0, emptyList(), emptyList())
}
