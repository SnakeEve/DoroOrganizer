package com.doro.organizer.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.TEST_SLOT_KEY_ALIAS
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The strict key-state codec of security.md § 3.5 and the file-format cases of testing.md L-05: (c) truncated JSON,
 * (e) `formatVersion` 2, (f) an unknown mode, (g) a field missing for the mode, (h) a 47-byte `wrappedDek`, (i) a
 * switched mode, (j) an extra unknown field, (n) a malformed `bioSlot`. Instrumented, because the codec runs on
 * `android.util.JsonReader`; the tests edit the JSON with `org.json`, which production never uses.
 */
@RunWith(AndroidJUnit4::class)
class KeyStateJsonTest {
    private fun bytes(size: Int, seed: Int) = ByteArray(size) { (it * 7 + seed).toByte() }

    private val plain = KeySlot(KeyMode.PLAIN, null, null, null, null)
    private val device = KeySlot(KeyMode.DEVICE, bytes(12, 1), bytes(48, 2), null, null)
    private val password = KeySlot(KeyMode.PASSWORD, bytes(12, 1), bytes(64, 2), bytes(12, 3), Kdf(820_000, bytes(16, 4)))
    private val bio = BioSlot(TEST_SLOT_KEY_ALIAS, bytes(12, 5), bytes(48, 6))

    private fun json(slot: KeySlot = password, bio: BioSlot? = null) =
        JSONObject(String(KeyStateJson.serialize(4, slot, bio), Charsets.UTF_8))

    private fun parse(text: String) = KeyStateJson.parse(text.toByteArray(Charsets.UTF_8))

    private fun assertInvalid(text: String) = assertInvalid(text.toByteArray(Charsets.UTF_8))

    private fun assertInvalid(bytes: ByteArray) {
        val e = assertThrows(KeyVaultException::class.java) { KeyStateJson.parse(bytes) }
        assertEquals(KeyFailure.InvalidState, e.failure)
        assertEquals("InvalidState", e.message)
        assertNull("no cause may quote the input", e.cause)
    }

    private fun JSONObject.slot(): JSONObject = getJSONObject("slot")

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun everyMode_roundTrips() {
        for ((slot, bioSlot) in listOf(plain to null, device to null, password to null, password to bio)) {
            val state = KeyStateJson.parse(KeyStateJson.serialize(9, slot, bioSlot))

            assertEquals(9L, state.generation)
            assertTrue("$slot", state.slot.sameAs(slot))
            assertEquals(bioSlot != null, state.bioSlot?.sameAs(requireNotNull(bioSlot)) ?: false)
            assertFalse(state.deadBioSlot)
        }
    }

    @Test
    fun serialized_hasTheDocumentedShape() {
        val o = json(password, bio)

        assertEquals(1, o.get("formatVersion"))
        assertEquals(4, o.get("generation"))
        assertEquals("PASSWORD", o.slot().getString("mode"))
        assertEquals("doro_kek_v1", o.slot().getString("kekAlias"))
        assertEquals(encode(password.wrappedDek!!), o.slot().getString("wrappedDek"))
        assertEquals("PBKDF2-HMAC-SHA256", o.slot().getJSONObject("kdf").getString("algorithm"))
        assertEquals(820_000, o.slot().getJSONObject("kdf").get("iterations"))
        assertEquals(TEST_SLOT_KEY_ALIAS, o.getJSONObject("bioSlot").getString("keyAlias")) // the slot's own key (DEC-98)
        assertFalse(json(plain).slot().has("kekAlias"))
        assertFalse(json(device).slot().has("kdf"))
    }

    @Test
    fun l05c_truncatedJson_isInvalid() {
        val text = json().toString()

        assertInvalid(text.substring(0, text.length / 2))
        assertInvalid("")
        assertInvalid(text + " {}")
    }

    @Test
    fun l05e_formatVersion_mustBeTheInteger1() {
        for (value in listOf<Any>(2, 0, "1", 1.5, JSONObject.NULL)) {
            assertInvalid(json().put("formatVersion", value).toString())
        }
        assertInvalid(json().apply { remove("formatVersion") }.toString())
        assertInvalid(json().toString().replace("\"formatVersion\":1", "\"formatVersion\":1.0"))
    }

    @Test
    fun generation_mustBeAnIntegerOfAtLeast1() {
        for (value in listOf<Any>(0, -1, "4", 2.5)) {
            assertInvalid(json().put("generation", value).toString())
        }
        for (literal in listOf("4e0", "4.0", "92233720368547758070")) {
            assertInvalid(json().toString().replace("\"generation\":4", "\"generation\":$literal"))
        }
        assertEquals(Long.MAX_VALUE, parse(json().put("generation", Long.MAX_VALUE).toString()).generation)
    }

    @Test
    fun l05f_unknownMode_isInvalid() {
        for (mode in listOf("BIOMETRIC", "device", "")) {
            assertInvalid(json(device).apply { slot().put("mode", mode) }.toString())
        }
    }

    @Test
    fun l05g_aFieldMissingForTheMode_isInvalid() {
        for (field in listOf("kekAlias", "outerIv", "wrappedDek", "innerIv", "kdf")) {
            assertInvalid(json(password).apply { slot().remove(field) }.toString())
        }
        for (field in listOf("algorithm", "iterations", "salt")) {
            assertInvalid(json(password).apply { slot().getJSONObject("kdf").remove(field) }.toString())
        }
        assertInvalid(json(device).apply { slot().remove("outerIv") }.toString())
        assertInvalid(json().apply { remove("slot") }.toString())
    }

    @Test
    fun aFieldAbsentForTheMode_mustNotBePresent() {
        assertInvalid(json(plain).apply { slot().put("kekAlias", "doro_kek_v1") }.toString())
        assertInvalid(json(plain).apply { slot().put("outerIv", encode(bytes(12, 1))) }.toString())
        assertInvalid(json(device).apply { slot().put("innerIv", encode(bytes(12, 1))) }.toString())
        assertInvalid(json(device).apply { slot().put("kdf", json(password).slot().getJSONObject("kdf")) }.toString())
    }

    @Test
    fun l05h_wrongLengths_areInvalid() {
        assertInvalid(json(device).apply { slot().put("wrappedDek", encode(bytes(47, 2))) }.toString())
        assertInvalid(json(password).apply { slot().put("wrappedDek", encode(bytes(48, 2))) }.toString())
        assertInvalid(json(device).apply { slot().put("outerIv", encode(bytes(11, 1))) }.toString())
        assertInvalid(json(password).apply { slot().put("innerIv", encode(bytes(16, 1))) }.toString())
        assertInvalid(json(password).apply { slot().getJSONObject("kdf").put("salt", encode(bytes(15, 1))) }.toString())
    }

    @Test
    fun l05i_aSwitchedMode_isInvalid() {
        assertInvalid(json(device).apply { slot().put("mode", "PASSWORD") }.toString())
        assertInvalid(json(password).apply { slot().put("mode", "DEVICE") }.toString())
        assertInvalid(json(password).apply { slot().put("mode", "PLAIN") }.toString())
    }

    @Test
    fun constantsAndRanges_areEnforced() {
        assertInvalid(json(device).apply { slot().put("kekAlias", "doro_kek_v2") }.toString())
        assertInvalid(json(password).apply { slot().getJSONObject("kdf").put("algorithm", "PBKDF2-HMAC-SHA512") }.toString())
        for (iterations in listOf(599_999, 5_000_001)) {
            assertInvalid(json(password).apply { slot().getJSONObject("kdf").put("iterations", iterations) }.toString())
        }
        for (iterations in listOf(600_000, 5_000_000)) {
            val text = json(password).apply { slot().getJSONObject("kdf").put("iterations", iterations) }.toString()
            assertEquals(iterations, parse(text).slot.kdf?.iterations)
        }
    }

    @Test
    fun wrongJsonTypes_areInvalid() {
        assertInvalid(json(device).apply { slot().put("outerIv", 12) }.toString())
        assertInvalid(json(device).apply { slot().put("mode", JSONObject.NULL) }.toString())
        assertInvalid(json(password).apply { slot().getJSONObject("kdf").put("iterations", "820000") }.toString())
        assertInvalid(json(password).apply { slot().put("kdf", JSONArray()) }.toString())
        assertInvalid(json().put("slot", "PLAIN").toString())
        assertInvalid("[]")
    }

    @Test
    fun base64_mustBeCanonicalAndPadded() {
        val salt = encode(bytes(16, 4))
        check(salt.endsWith("=="))

        assertInvalid(json(password).apply { slot().getJSONObject("kdf").put("salt", salt.dropLast(2)) }.toString())
        assertInvalid(json(password).apply { slot().getJSONObject("kdf").put("salt", "-" + salt.drop(1)) }.toString())
        assertInvalid(json(device).apply { slot().put("outerIv", "*" + encode(bytes(12, 1)).drop(1)) }.toString())
    }

    @Test
    fun duplicateKnownFields_areInvalid() {
        val text = json(device).toString()

        assertInvalid(text.replace("\"generation\":4", "\"generation\":4,\"generation\":5"))
        assertInvalid(text.replace("\"mode\":\"DEVICE\"", "\"mode\":\"DEVICE\",\"mode\":\"DEVICE\""))
    }

    @Test
    fun malformedUtf8_isInvalid() {
        val text = json(device).toString().toByteArray(Charsets.UTF_8)
        val at = String(text, Charsets.UTF_8).indexOf("DEVICE")

        assertInvalid(text.copyOf().also { it[at] = 0xC3.toByte(); it[at + 1] = 0x28 })
    }

    @Test
    fun l05j_unknownFields_areIgnored() {
        val o = json(password, bio).apply {
            put("future", JSONObject().put("nested", JSONArray().put(1).put("x")))
            slot().put("note", 3)
            slot().getJSONObject("kdf").put("memory", 64)
            getJSONObject("bioSlot").put("createdAt", 5)
        }

        val state = parse(o.toString())

        assertTrue(state.slot.sameAs(password))
        assertTrue(bio.sameAs(requireNotNull(state.bioSlot)))
    }

    @Test
    fun l05n_aMalformedBioSlot_isIgnoredAndFlaggedDead_neverInvalid() {
        val variants = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("bioSlot").put("iv", "*" + encode(bio.iv).drop(1)) }, // a flipped base64 character
            { it.getJSONObject("bioSlot").put("wrappedDek", encode(bytes(47, 6))) },
            { it.getJSONObject("bioSlot").put("keyAlias", "doro_bio_v2") },
            { it.getJSONObject("bioSlot").put("keyAlias", 7) },
            { it.getJSONObject("bioSlot").remove("iv") },
            { it.getJSONObject("bioSlot").put("wrappedDek", 48) },
            { it.put("bioSlot", JSONObject.NULL) },
            { it.put("bioSlot", JSONArray()) },
        )

        variants.forEachIndexed { i, mutate ->
            val state = parse(json(password, bio).also(mutate).toString())

            assertTrue("variant $i", state.slot.sameAs(password))
            assertNull("variant $i", state.bioSlot)
            assertTrue("variant $i", state.deadBioSlot)
        }
    }

    /** DEC-98: only the fresh-alias form, so a slot can never name the KEK, a pre-DEC-98 key or any other key. */
    @Test
    fun keyAlias_mustHaveTheFreshAliasForm() {
        val accepted = listOf(TEST_SLOT_KEY_ALIAS, "doro_bio_v1_" + "0".repeat(32), "doro_bio_v1_" + "f".repeat(32))
        val rejected = listOf(
            "doro_bio_v1", // the constant of the builds before DEC-98
            KEK_ALIAS,
            "doro_bio_v1_" + "0".repeat(31),
            "doro_bio_v1_" + "0".repeat(33),
            "doro_bio_v1_" + "A".repeat(32),
            "doro_bio_v1_" + "g".repeat(32),
            "doro_bio_v2_" + "0".repeat(32),
            " " + TEST_SLOT_KEY_ALIAS,
            TEST_SLOT_KEY_ALIAS + "\n",
        )

        for (alias in accepted) {
            val state = parse(json(password, bio).apply { getJSONObject("bioSlot").put("keyAlias", alias) }.toString())
            assertEquals(alias, requireNotNull(state.bioSlot).keyAlias)
        }
        for (alias in rejected) {
            val state = parse(json(password, bio).apply { getJSONObject("bioSlot").put("keyAlias", alias) }.toString())
            assertNull(alias, state.bioSlot)
            assertTrue(alias, state.deadBioSlot)
        }
    }

    @Test
    fun l05n_aRepeatedBioSlotMember_isDead_neverInvalid() {
        val text = json(password, bio).toString()
        val member = "\"bioSlot\":" + json(password, bio).getJSONObject("bioSlot")

        for (second in listOf(member, "\"bioSlot\":7")) {
            val state = parse(text.dropLast(1) + "," + second + "}")

            assertTrue(state.slot.sameAs(password))
            assertNull(state.bioSlot)
            assertTrue(state.deadBioSlot)
        }
    }

    @Test
    fun l05n_aBioSlotNextToANonPasswordSlot_isDead() {
        val bioJson = json(password, bio).getJSONObject("bioSlot")

        for (slot in listOf(device, plain)) {
            val state = parse(json(slot).put("bioSlot", bioJson).toString())

            assertTrue(state.slot.sameAs(slot))
            assertNull(state.bioSlot)
            assertTrue(state.deadBioSlot)
        }
    }
}
