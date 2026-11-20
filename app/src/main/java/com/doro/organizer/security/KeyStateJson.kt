package com.doro.organizer.security

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.doro.organizer.model.KeyMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Base64

/** The only KDF the key-state file names (security.md § 3.5). */
internal const val KDF_ALGORITHM = "PBKDF2-HMAC-SHA256"

/**
 * The strict codec of `db-state.json` (security.md § 3.5). The parser is `android.util.JsonReader` with a `peek()`
 * check before every read, so a quoted or fractional number is never coerced; `org.json` is not used because its
 * exception messages contain the input. The main-slot rules alone decide validity; a malformed `bioSlot` is
 * ignored and flagged as dead. Every failure is `KeyVaultException(InvalidState)` without a cause.
 */
internal object KeyStateJson {
    private const val FORMAT_VERSION = 1L
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
    /** Main members that may appear once; a repeated `bioSlot` only makes the bio slot dead (security.md § 3.5). */
    private val TOP_LEVEL = setOf("formatVersion", "generation", "slot")
    private val SLOT_STRINGS = setOf("mode", "kekAlias", "outerIv", "wrappedDek", "innerIv")
    private val KDF_FIELDS = setOf("algorithm", "iterations", "salt")
    private val BIO_FIELDS = setOf("keyAlias", "iv", "wrappedDek")
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun parse(bytes: ByteArray): KeyState = try {
        // A strict decoder: malformed UTF-8 is an error, never replaced.
        JsonReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8.newDecoder())).use { readDocument(it) }
    } catch (e: KeyVaultException) {
        throw e
    } catch (e: Exception) {
        // JSON, number and base64 exceptions may quote the input (security.md § 10), so none is passed on.
        throw invalid()
    }

    /** Writes exactly its inputs; every writer writes `formatVersion` 1. */
    fun serialize(generation: Long, slot: KeySlot, bio: BioSlot?): ByteArray {
        val out = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
            w.setIndent("  ")
            w.beginObject()
            w.name("formatVersion").value(FORMAT_VERSION)
            w.name("generation").value(generation)
            w.name("slot").beginObject()
            w.name("mode").value(slot.mode.name)
            if (slot.mode != KeyMode.PLAIN) w.name("kekAlias").value(KEK_ALIAS)
            slot.outerIv?.let { w.name("outerIv").value(encode(it)) }
            slot.wrappedDek?.let { w.name("wrappedDek").value(encode(it)) }
            slot.innerIv?.let { w.name("innerIv").value(encode(it)) }
            slot.kdf?.let {
                w.name("kdf").beginObject()
                w.name("algorithm").value(KDF_ALGORITHM)
                w.name("iterations").value(it.iterations.toLong())
                w.name("salt").value(encode(it.salt))
                w.endObject()
            }
            w.endObject()
            bio?.let {
                w.name("bioSlot").beginObject()
                w.name("keyAlias").value(it.keyAlias)
                w.name("iv").value(encode(it.iv))
                w.name("wrappedDek").value(encode(it.wrappedDek))
                w.endObject()
            }
            w.endObject()
        }
        return out.toByteArray()
    }

    private fun readDocument(reader: JsonReader): KeyState {
        expect(reader, JsonToken.BEGIN_OBJECT)
        reader.beginObject()
        val seen = HashSet<String>()
        var formatVersion: Long? = null
        var generation: Long? = null
        var slot: KeySlot? = null
        var bioPresent = false
        var bioRepeated = false
        var bio: BioSlot? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name in TOP_LEVEL && !seen.add(name)) throw invalid()
            when (name) {
                "formatVersion" -> formatVersion = readInteger(reader)
                "generation" -> generation = readInteger(reader)
                "slot" -> slot = readSlot(reader)
                "bioSlot" -> if (bioPresent) {
                    bioRepeated = true // ambiguous, so neither copy is used
                    reader.skipValue()
                } else {
                    bioPresent = true
                    bio = readBioSlot(reader)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid()
        // A larger version means a newer app wrote the file (security.md § 12): invalid as well.
        if (formatVersion != FORMAT_VERSION) throw invalid()
        if (generation == null || generation < 1) throw invalid()
        if (slot == null) throw invalid()
        val bioSlot = bio?.takeIf { slot.mode == KeyMode.PASSWORD && !bioRepeated }
        return KeyState(generation, slot, bioSlot, deadBioSlot = bioPresent && bioSlot == null)
    }

    private fun readSlot(reader: JsonReader): KeySlot {
        expect(reader, JsonToken.BEGIN_OBJECT)
        reader.beginObject()
        val strings = HashMap<String, String>()
        var kdf: KdfFields? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            when {
                name in SLOT_STRINGS -> if (strings.put(name, readString(reader)) != null) throw invalid()
                name == "kdf" -> {
                    if (kdf != null) throw invalid()
                    kdf = readKdf(reader)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val mode = KeyMode.entries.firstOrNull { it.name == strings["mode"] } ?: throw invalid()
        val wrapped = mode != KeyMode.PLAIN
        val password = mode == KeyMode.PASSWORD
        val kekAlias = strings["kekAlias"]
        if (if (wrapped) kekAlias != KEK_ALIAS else kekAlias != null) throw invalid()
        // DEVICE: GCM(KEK, DEK); PASSWORD: GCM(KEK, GCM(password KEK, DEK)) (security.md § 3.1).
        val wrappedSize = SECRET_SIZE + GCM_TAG_SIZE * (if (password) 2 else 1)
        val parsedKdf = kdf
        return KeySlot(
            mode = mode,
            outerIv = field(strings["outerIv"], required = wrapped, size = IV_SIZE),
            wrappedDek = field(strings["wrappedDek"], required = wrapped, size = wrappedSize),
            innerIv = field(strings["innerIv"], required = password, size = IV_SIZE),
            kdf = when {
                password -> parsedKdf?.validated() ?: throw invalid()
                parsedKdf != null -> throw invalid()
                else -> null
            },
        )
    }

    /** A main-slot base64 field: present exactly when [required], then with exactly [size] bytes. */
    private fun field(text: String?, required: Boolean, size: Int): ByteArray? = when {
        required -> decodeOrNull(text, size) ?: throw invalid()
        text != null -> throw invalid()
        else -> null
    }

    private class KdfFields(val algorithm: String?, val iterations: Long?, val salt: String?) {
        fun validated(): Kdf {
            if (algorithm != KDF_ALGORITHM) throw invalid()
            if (iterations == null || iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw invalid()
            return Kdf(iterations.toInt(), decodeOrNull(salt, SALT_SIZE) ?: throw invalid())
        }
    }

    private fun readKdf(reader: JsonReader): KdfFields {
        expect(reader, JsonToken.BEGIN_OBJECT)
        reader.beginObject()
        val seen = HashSet<String>()
        var algorithm: String? = null
        var iterations: Long? = null
        var salt: String? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name in KDF_FIELDS && !seen.add(name)) throw invalid()
            when (name) {
                "algorithm" -> algorithm = readString(reader)
                "iterations" -> iterations = readInteger(reader)
                "salt" -> salt = readString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return KdfFields(algorithm, iterations, salt)
    }

    /** The optional biometric slot: null when malformed in any way, which never makes the file invalid. */
    private fun readBioSlot(reader: JsonReader): BioSlot? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        reader.beginObject()
        val strings = HashMap<String, String?>()
        var malformed = false
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name in BIO_FIELDS) {
                malformed = malformed || name in strings
                strings[name] = stringOrSkip(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        // Only the fresh-alias form (DEC-98): a slot can never name the KEK or any other key.
        val keyAlias = strings["keyAlias"]?.takeIf(::isSlotKeyAlias)
        if (malformed || keyAlias == null) return null
        val iv = decodeOrNull(strings["iv"], IV_SIZE) ?: return null
        val wrappedDek = decodeOrNull(strings["wrappedDek"], SECRET_SIZE + GCM_TAG_SIZE) ?: return null
        return BioSlot(keyAlias, iv, wrappedDek)
    }

    private fun readInteger(reader: JsonReader): Long {
        expect(reader, JsonToken.NUMBER)
        val literal = reader.nextString() // the literal text, so 1.0 or 1e0 is rejected instead of coerced
        if (!INTEGER.matches(literal)) throw invalid()
        return literal.toLongOrNull() ?: throw invalid()
    }

    private fun readString(reader: JsonReader): String {
        expect(reader, JsonToken.STRING)
        return reader.nextString()
    }

    private fun stringOrSkip(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.STRING) return reader.nextString()
        reader.skipValue()
        return null
    }

    private fun expect(reader: JsonReader, token: JsonToken) {
        if (reader.peek() != token) throw invalid()
    }

    /** Canonical padded base64 of exactly [size] bytes, or null. */
    private fun decodeOrNull(text: String?, size: Int): ByteArray? {
        if (text == null) return null
        val bytes = try {
            decoder.decode(text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { it.size == size && encode(it) == text }
    }

    private fun encode(bytes: ByteArray): String = base64Encoder.encodeToString(bytes)

    private fun invalid() = KeyVaultException(KeyFailure.InvalidState)
}
