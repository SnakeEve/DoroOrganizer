package com.doro.organizer.gates

import com.doro.organizer.gates.manifest.ManifestRules
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigEntriesTest {
    @Test
    fun skipsBlankAndCommentLinesAndTrims() {
        assertEquals(listOf("a", "b.c", "d:e"), configEntries("# header\n\n  a  \r\nb.c\n   # indented comment\nd:e\n"))
    }

    @Test
    fun committedPermissionAllowlist_isExactlySecurityMd93() {
        assertEquals(
            setOf(
                "android.permission.USE_BIOMETRIC",
                "android.permission.USE_FINGERPRINT",
                "android.permission.HIDE_OVERLAY_WINDOWS",
                "com.doro.organizer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            ManifestRules.permissionAllowlist(ConfigFiles.read("permission-allowlist.txt"), "com.doro.organizer"),
        )
    }

    @Test
    fun committedReleasedSchemaVersions_holdsOnlyIntegers() {
        configEntries(ConfigFiles.read("released-schema-versions.txt")).forEach { assertEquals(it, it.toIntOrNull()?.toString(), it) }
    }
}
