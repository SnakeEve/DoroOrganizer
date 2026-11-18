# Security

Purpose: the threat model and the binding rules for keys, passwords, the app lock, screen and platform hardening, and log hygiene.
Related: [decisions.md](decisions.md) · [architecture.md](architecture.md) · [db-lifecycle.md](db-lifecycle.md) · [data-layer.md](data-layer.md) · [ui.md](ui.md) · [testing.md](testing.md) · [spec](../stakeholder-notepad-spec.md)

Ownership: this file owns the key model, `KeyVault`, the password and unlock flows, the biometric lock logic, window protection, the manifest and backup-rule XML, offline enforcement, the logging policy, secrets in memory and keyboard/autofill rules. File layout, commit, restore and key-change mechanics are in [db-lifecycle.md](db-lifecycle.md); gate states and transitions are in [architecture.md](architecture.md) § SessionGate (T1–T35); screens and strings are in [ui.md](ui.md).

## 1. Threat model

### 1.1 Assets
| Asset | Where it lives |
|---|---|
| Notes, person/project names, dates, links | Live database `databases/doro-<generation>.db` and its `-wal` (DEC-80, DEC-89); staging files in `noBackupFilesDir` |
| Exported backups | User-chosen SAF targets; share copies in `cacheDir/share/` until the next backup, the next cold start or the one-hour sweep while the app runs ([db-lifecycle.md](db-lifecycle.md#share-file-lifetime), DEC-83) |
| DEK and its wrapped form | Process memory as the 67-byte raw key while the database is open (section 6.4); `noBackupFilesDir/db-state.json` (section 3.5) |
| Keystore KEK | AndroidKeyStore (TEE or StrongBox); never in the app process |
| Database and backup passwords | Process memory for one operation (section 6) |
| Screen content | The window, the Recents thumbnail, screenshots, screen recordings, the platform's content capture |
| UI state | `SavedStateHandle` holds drafts, filters and search text (DEC-28, DEC-37); `SettingsStore` holds no user text |

### 1.2 Adversaries in scope
| Adversary | Can | Controls |
|---|---|---|
| A1 Person holding the unlocked phone | Open the app, Recents and Settings; take screenshots; change the system clock | Biometric lock (DEC-65), startup password (DEC-62, backoff on a time base the clock cannot move, section 5.3), `FLAG_SECURE` (DEC-67), Recents protection and data-free gate screens (DEC-66) |
| A2 Holder of a copied backup file | Guess passwords offline without limit | Backup password and SQLCipher 4 KDF (DEC-83), plaintext-export warning, password policy (DEC-64) |
| A3 Someone extracting app files from a lost device | Read `databases/`, `files/`, `cache/` and `noBackupFilesDir` without running the app, including an undeleted share copy (plaintext in `PLAIN` mode, offline-attackable in the other modes) | `DEVICE`/`PASSWORD`: the DEK unwraps only in this device's Keystore; `PASSWORD` also needs the password; file-based encryption (§11 Device Assumptions); no cloud or D2D copies (DEC-69); share copies deleted on the triggers of DEC-83 and after a committed key change (section 4 step 5) |
| A4 Other apps on the device | Send intents, request URIs, draw overlays, hijack tasks, act as keyboard or autofill service | One exported activity that ignores its launch intent, private FileProvider (DEC-68), overlay hiding (section 9.4), keyboard and autofill rules (DEC-71) |
| A5 Screen capture | Screenshots, recording, casting, Recents thumbnails, system content capture | `FLAG_SECURE` (DEC-67), `setRecentsScreenshotEnabled(false)` (DEC-66), content-capture opt-out (section 8) |

Every user-facing setting of section 2 is off at first run (§11 Initial State). The platform controls (DEC-68, DEC-69, DEC-70 and the autofill exclusion of section 11) are always on. With all settings off, A1 sees everything and A3 reads a plaintext database; that is the documented default.

### 1.3 Out of scope
- §11 Out of Scope: multi-user access control, per-note encryption, remote wipe, network security.
- Rooted devices and any attacker with live access to the process (memory dumps, hooking, debugging) or with write access to app-private storage (tampering with `SettingsStore` or the key-state file).
- A compromised OS, Keystore, TEE or firmware; keyboards, accessibility or autofill services the user enabled, beyond DEC-71.

### 1.4 Assumptions (§11 Device Assumptions)
- The device has a secure screen lock and file-based (or full-disk) encryption. App data stays in credential-encrypted storage: the app is never `directBootAware`.
- The AndroidKeyStore is hardware-backed. With a software-only Keystore, `DEVICE` mode resists A3 only as far as the OS protects the Keystore's own files.
- An unencrypted backup exported to an insecure location is exposed (§11). The app warns (DEC-83) but does not prevent it.

## 2. Security settings

All settings are off at first run (§11 Initial State). `SettingsStore` values are not secret and are readable before unlock (DEC-09).

| Setting | Default | Stored where | Effect | DEC |
|---|---|---|---|---|
| Database encryption | off | Key-state file `slot.mode` (`PLAIN`, `DEVICE`, `PASSWORD`); never in `SettingsStore`. Exposed to the UI as `DbMaintenance.keyMode` ([db-lifecycle.md](db-lifecycle.md#interface-and-status)) | `DEVICE`/`PASSWORD`: SQLCipher file keyed by a random DEK (section 3) | DEC-60, DEC-61 |
| Startup password | off | Key-state file (`slot.mode = PASSWORD`, `slot.kdf`); the password itself is never stored | Asked at every cold start before the database opens (section 5) | DEC-62, DEC-64 |
| Biometric lock | off | `SettingsStore` `security.app_lock` | Lock at cold start and on return from background (section 7) | DEC-65, DEC-66 |
| Screenshot protection | off | `SettingsStore` `security.screenshot_protection` | `FLAG_SECURE` on the window (section 8); keyboard-learning opt-out (section 11) | DEC-67, DEC-71 |
| Encrypt backups | off | `SettingsStore` `security.encrypt_backups` | `PLAIN` mode: exports ask for a backup password. `DEVICE`/`PASSWORD` exports always do, whatever this value | DEC-83 |

- Mode mapping: encryption off = `PLAIN`; on without a password = `DEVICE`; on with a password = `PASSWORD`. A startup password without encryption does not exist.
- The six security members of `SettingsStore` ([architecture.md](architecture.md) § Dependency injection) and their DataStore keys:

| `Settings` property | DataStore key | Type | Default | Written by |
|---|---|---|---|---|
| `screenshotProtection` | `security.screenshot_protection` | Boolean | false | Settings screen |
| `biometricLock` | `security.app_lock` | Boolean | false | `AppLockController` after a successful authentication (section 7.1), or auto-off (section 7.6) |
| `encryptBackups` | `security.encrypt_backups` | Boolean | false | Settings screen |
| `unlockFailures` | `security.unlock_failures` | Int | 0 | `KeyVault` only (section 5.3) |
| `unlockRetryAtElapsedMs` | `security.unlock_retry_at` | Long, `AppTime.elapsedMillis()` base | 0 | `KeyVault` only (section 5.3) |
| `lockAutoOffNotice` | `security.lock_auto_off_notice` | Boolean | false | `AppLockController` (set), the shell notice (clear) |

- `SettingsStore` never holds user text, secrets or URIs. Backups never carry these settings and restore never changes them (DEC-84). Erase resets all six (section 5.4).
- A corrupt preferences file is replaced with defaults ([architecture.md](architecture.md) § Dependency injection), which turns the lock and screenshot protection off and resets the backoff; accepted (section 12).

## 3. Key model (DEC-60)

Decided by the owner (Q1, 2026-11-19): random DEK protected by the Keystore, optional startup password, three modes `PLAIN`, `DEVICE`, `PASSWORD`. The password-only alternative is rejected.

### 3.1 Keys and parameters
| Item | Specification |
|---|---|
| DEK | 32 bytes from `SecureRandom()` (default instance, never seeded). A new DEK for every key change (DEC-61); none in `PLAIN` |
| Keystore KEK | AndroidKeyStore AES-256 (`setKeySize(256)`; the default is 128), GCM, no padding, alias `doro_kek_v1`, non-exportable, no user-authentication flags, no `setUnlockedDeviceRequired`, StrongBox when available |
| Password KEK | PBKDF2-HMAC-SHA256 over UTF-8(NFC(password)), 16-byte random salt, ≥ 600,000 iterations (section 3.3), 32-byte output. Never stored |
| Wrap | AES/GCM/NoPadding, 12-byte IV, 128-bit tag. AAD (ASCII), outer layer: `DEVICE` = `doro.kek.v1:DEVICE`; `PASSWORD` = `doro.kek.v1:PASSWORD:PBKDF2-HMAC-SHA256:<iterations>:<salt base64>:<innerIv base64>`. Inner layer: `doro.pw.v1`. The base64 parts are re-encoded from the decoded bytes with the encoder of section 3.5, never taken from the file text |
| `DEVICE` slot | `wrappedDek` = GCM(KEK, DEK): 48 bytes |
| `PASSWORD` slot | inner = GCM(password KEK, DEK): 48 bytes; `wrappedDek` = GCM(KEK, inner): 64 bytes |
| Live database | SQLCipher 4 keyed with the raw key `x'<64 hex>'` (no per-connection KDF): AES-256-CBC, HMAC-SHA512 per page, 4096-byte pages. `PLAIN`: key `ByteArray(0)` on the same engine (DEC-08) |
| Backup file | SQLCipher 4 passphrase mode: PBKDF2-HMAC-SHA512, 256,000 iterations, per-file salt; passphrase = UTF-8(NFC(backup password)) (DEC-83) |

- No GCM tag covers the inner IV, the salt or the iteration count, so the outer AAD of a `PASSWORD` slot binds them. A damaged value then fails the outer tag and is KeyUnavailable, never a wrong password that counts toward the backoff (section 5.2).
- A copied key-state file cannot be attacked offline, because the inner layer is reachable only through this device's Keystore. The Keystore unwrap itself does not depend on the password: guesses made through the app pay the PBKDF2 cost and the backoff (section 5.3), while code running as the app could attack the inner layer offline after one unwrap (out of scope, section 1.3).
- Keystore layer: `Cipher.getInstance("AES/GCM/NoPadding")`, `init(ENCRYPT_MODE, kek)` with no parameters (the Keystore picks the IV and rejects caller IVs), store `cipher.iv`; decrypt with `GCMParameterSpec(128, iv)`. Password layer: `SecretKeySpec(passwordKek, "AES")` with a fresh 12-byte `SecureRandom` IV. Both layers call `updateAAD` before `doFinal`.
- KEK lifecycle: generated only when the alias is missing and the target mode is `DEVICE` or `PASSWORD`. Never deleted or regenerated while the committed slot uses it, so a failure before a commit leaves the old slot readable. Deleted after a committed switch to `PLAIN` (a key change to `PLAIN`, Start fresh, and a Restore from KeyUnavailable, section 4.1) and by Erase (section 5.4); a failed delete is ignored. While the committed mode is `PLAIN`, an unusable alias is deleted and regenerated once.
- All `KeyVault` work runs off the main thread ([architecture.md](architecture.md) § Threading): Keystore calls and file I/O on `dispatchers.io`, PBKDF2 and calibration on `dispatchers.default`.

### 3.2 KEK generation
```kotlin
fun generateKek(context: Context): SecretKey {
    fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
        KEK_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setIsStrongBoxBacked(strongBox)   // no user-auth flags, no setUnlockedDeviceRequired (DEC-60)
        .build()
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
        try { gen.init(spec(true)); return gen.generateKey() }
        catch (e: ProviderException) { deleteKekQuietly() }          // includes StrongBoxUnavailableException
        catch (e: GeneralSecurityException) { deleteKekQuietly() }   // e.g. InvalidAlgorithmParameterException
    }
    gen.init(spec(false)); return gen.generateKey()
}
```
- `prepare` validates what it produced before returning, because a StrongBox key can generate and still misbehave on use, and the failure would otherwise appear only at the next cold start, after the commit: the outer IV is exactly 12 bytes (`check(cipher.iv.size == 12)`), `wrappedDek` has the length of section 3.1, and unwrapping it once with the same KEK reproduces the input (`MessageDigest.isEqual`). If a check fails or the cipher throws and the KEK is a StrongBox key created in this `prepare`, delete the alias, regenerate it with `setIsStrongBoxBacked(false)` and redo the wrap once. Any other failure throws `KeyVaultException(KeyUnavailable)` before any export starts.
- Verify at setup: `cipher.iv.size == 12` for AndroidKeyStore GCM on api28, api35 and the StrongBox device.

### 3.3 Password KEK
`PBEKeySpec` is not used: it takes `char[]` and its char-to-byte conversion is provider-defined, while the derived key must stay reproducible for years. `KeyVault` derives from the bytes of section 6.3:
```kotlin
fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
    require(password.isNotEmpty())                       // SecretKeySpec rejects an empty key; callers filter empty input first (section 3.6)
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
    val u = ByteArray(32)
    mac.update(salt); mac.update(byteArrayOf(0, 0, 0, 1)); mac.doFinal(u, 0)   // RFC 8018, block 1
    val t = u.copyOf()
    repeat(iterations - 1) {
        mac.update(u); mac.doFinal(u, 0)
        for (i in 0 until 32) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
    }
    u.fill(0)
    return t
}
```
- Known-answer test (required): `Pbkdf2Test` compares the first 32 bytes of the RFC 7914 §11 PBKDF2-HMAC-SHA256 vectors (P="passwd", S="salt", c=1; P="Password", S="NaCl", c=80000), and on device equals `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` for ASCII passwords at 600,000 iterations ([testing.md](testing.md) L-04).
- Calibration runs when a password is set or changed, never at unlock: run 50,000 iterations twice on throwaway input on the default dispatcher and take the second duration `t`, measured as the difference of `AppTime.elapsedMillis()` around the run and coerced to at least 1 ms (no other clock is allowed, [architecture.md](architecture.md) § Time). Then `iterations = iterationsFor(t)`:
```kotlin
fun iterationsFor(tMillis: Long): Int {                   // pure function, JVM-tested (KdfCalibrationTest)
    val raw = 50_000L * 750L / maxOf(tMillis, 1L)          // Long arithmetic; target 750 ms
    val rounded = (raw + 9_999L) / 10_000L * 10_000L       // round up to a multiple of 10,000
    return rounded.coerceIn(600_000L, 5_000_000L).toInt()
}
```
  The floor wins over the time target on slow devices. Unlock always uses the stored `slot.kdf.iterations`.

### 3.4 SQLCipher key and settings
```kotlin
fun sqlcipherRawKey(dek: ByteArray): ByteArray {          // ASCII x'<64 hex>' = 67 bytes, no String
    require(dek.size == 32)
    val hex = "0123456789abcdef"
    val out = ByteArray(67)
    out[0] = 'x'.code.toByte(); out[1] = '\''.code.toByte(); out[66] = '\''.code.toByte()
    for (i in 0 until 32) {
        val v = dek[i].toInt() and 0xFF
        out[2 + 2 * i] = hex[v ushr 4].code.toByte()
        out[3 + 2 * i] = hex[v and 0x0F].code.toByte()
    }
    return out
}
```
- Pin the SQLCipher 4 parameters so that a library upgrade (5.x defaults to AES-GCM and 8 KB pages) cannot change the on-disk format of live databases or backups:
  - Process-wide, once, in effect E1 ([architecture.md](architecture.md) § SessionGate), before any other SQLCipher use:
```kotlin
// package com.doro.organizer.data; idempotent. Effect E1 calls it as its first step, before DbMaintenance.reconcileAtStartup;
// RawConnections.open and buildRoom call it defensively. Nothing SQLCipher-related runs in DoroApplication.onCreate.
internal object SqlcipherEngine {
    private var done = false
    @Synchronized fun init() {
        if (done) return
        System.loadLibrary("sqlcipher")                      // DEC-08
        SqlcipherLogging.configure()                            // section 10: release Logger target, before any connection
        val db = SQLiteDatabase.openOrCreateDatabase(":memory:", ByteArray(0), null, null)   // throwaway; Verify at setup: exact factory
        try { db.query("PRAGMA cipher_default_compatibility = 4").use { it.moveToFirst() } } finally { db.close() }
        done = true
    }
}
```
  - Every connection opened with a non-empty key also runs `PRAGMA cipher_compatibility = 4` (connection scope) in `CipherCompatibilityHook.postKey` ([db-lifecycle.md](db-lifecycle.md#open-sequence)).
- Pragma allowlist. Writes: `cipher_default_compatibility = 4` (above), `cipher_compatibility = 4` (the hook) and, release only and only if the section 10 Verify item shows native cipher logs, `cipher_log_level = NONE`. Reads (verification and tests): `cipher_version`, `cipher_integrity_check`, `cipher_page_size`, `cipher_hmac_algorithm`, `cipher_kdf_algorithm`, `kdf_iter`. Every other `cipher_*` or `kdf_iter` pragma is banned.
- Never `PRAGMA rekey` (key changes re-export, DEC-61), never `cipher_plaintext_header_size`, never `temp_store = FILE` (the library is built with `SQLITE_TEMP_STORE=2`, so temporary data stays in RAM). `cipher_memory_security` keeps its default (off).
- Verify at setup: both pragmas run without error through the hook and maintenance APIs; an instrumented test reads `cipher_page_size` 4096, `cipher_hmac_algorithm` HMAC_SHA512, `cipher_kdf_algorithm` PBKDF2_HMAC_SHA512 and `kdf_iter` 256000 on a live encrypted database and on an exported encrypted backup ([testing.md](testing.md) L-07). If an exported target does not report the pinned values, [db-lifecycle.md](db-lifecycle.md#maintenance-connection) `exportTo` also runs `PRAGMA target.cipher_compatibility = 4` right after `ATTACH`. Repeat this test before any SQLCipher 5.x upgrade.

### 3.5 Key-state file
```json
{
  "formatVersion": 1,
  "generation": 4,
  "slot": {
    "mode": "PASSWORD",
    "kekAlias": "doro_kek_v1",
    "outerIv": "<base64>",
    "wrappedDek": "<base64>",
    "innerIv": "<base64>",
    "kdf": { "algorithm": "PBKDF2-HMAC-SHA256", "iterations": 820000, "salt": "<base64>" }
  }
}
```
| Field | Type | `PLAIN` | `DEVICE` | `PASSWORD` |
|---|---|---|---|---|
| `formatVersion` | JSON integer | 1 | 1 | 1 |
| `generation` | JSON integer ≥ 1 | required | required | required |
| `slot.mode` | string | `PLAIN` | `DEVICE` | `PASSWORD` |
| `slot.kekAlias` | string | absent | `doro_kek_v1` | `doro_kek_v1` |
| `slot.outerIv` | base64, 12 bytes | absent | required | required |
| `slot.wrappedDek` | base64 | absent | 48 bytes | 64 bytes |
| `slot.innerIv` | base64, 12 bytes | absent | absent | required |
| `slot.kdf.algorithm` | string | absent | absent | `PBKDF2-HMAC-SHA256` |
| `slot.kdf.iterations` | JSON integer | absent | absent | 600,000 ≤ n ≤ 5,000,000 (the section 3.3 clamp range) |
| `slot.kdf.salt` | base64, 16 bytes | absent | absent | required |

- Path `noBackupFilesDir/db-state.json` (DEC-80); UTF-8 JSON; base64 = `java.util.Base64` basic alphabet with padding (encoder and decoder).
- Parser: `android.util.JsonReader` with an explicit `peek()` check before every read, so a quoted number or a fractional number is never coerced. Do not use `org.json`: its `JSONException` messages contain the input text, which here includes the wrapped DEK, IVs and salt (section 10).
- The file is invalid if any of these holds: `formatVersion` is not the JSON integer 1 (a larger value means a newer app wrote it, section 12); `generation` is not a JSON integer ≥ 1; `mode` is not one of the three strings; `kekAlias` differs from `doro_kek_v1`; `kdf.algorithm` differs; `kdf.iterations` is outside the range of the table; a required field is missing or has the wrong JSON type; a field marked absent for the mode is present; a base64 value does not decode or has the wrong length. Field names not in the table are ignored. Any parser exception is caught at the parsing boundary and replaced by `KeyVaultException(InvalidState)` with constant text and no cause.
- Write only with `androidx.core.util.AtomicFile`: `startWrite()`, write, `finishWrite()` (it syncs before renaming), `failWrite()` on any exception. Read only through `AtomicFile.readFully()`, which reads the base file and never recovers an interrupted write: a stale `db-state.json.new` is ignored and discarded when the base file exists. When only `.new` exists (an interrupted first write) `readFully()` throws `FileNotFoundException`, which `readState()` reports as no file (`null`, Missing), not as an invalid file. Any other `IOException` while reading is an invalid file. Verify at setup: the `.new` behavior of the pinned `androidx.core.util.AtomicFile` ([db-lifecycle.md](db-lifecycle.md#generation-commit)). The directory sync that makes the commit durable is part of `DbMaintenance.commitGeneration` ([db-lifecycle.md](db-lifecycle.md#generation-commit)); `eraseState()` runs the same best-effort `syncDirectory(noBackupFilesDir)` after its deletion. Startup cleanup and reconciliation never write `db-state.json*`; only `commit` and `eraseState` do.
- `AtomicFile` gives no locking: every `KeyVault` operation (read, commit, erase, and every password attempt, section 3.6) runs under one `Mutex`.
- The file exists whenever a database exists, `PLAIN` included (it carries `generation`). Only `KeyVault` writes it, and only when `DbMaintenance.commitGeneration` or Erase calls it: first run, every committed swap (key change, Restore, Start fresh), the adoption of a plaintext file at startup, and Erase (section 5.4). Generation semantics, commit order and the handling of a missing or invalid file next to existing database files (first run, adoption, KeyUnavailable): [db-lifecycle.md](db-lifecycle.md#startup-reconciliation) and [db-lifecycle.md](db-lifecycle.md#failure-classification). An invalid file is never rewritten or deleted automatically (DEC-86).
- Never in SharedPreferences or DataStore (§11 Key Management, DEC-09); never logged.

### 3.6 KeyVault surface
```kotlin
// package com.doro.organizer.model
enum class KeyMode { PLAIN, DEVICE, PASSWORD }

// package com.doro.organizer.security. Every toString() prints class name and mode only; no array content (DEC-70).
class Kdf(val iterations: Int, val salt: ByteArray)
class KeySlot(val mode: KeyMode, val outerIv: ByteArray?, val wrappedDek: ByteArray?, val innerIv: ByteArray?, val kdf: Kdf?) {
    fun sameAs(other: KeySlot): Boolean            // owner definition: db-lifecycle.md § Generation commit. Equal mode; equal outerIv, wrappedDek, innerIv, kdf.iterations and kdf.salt (absent equals absent); kekAlias and kdf.algorithm are validated constants (section 3.5) and so implied; never ==
    override fun toString() = "KeySlot(mode=$mode)"
}
class KeyState(val generation: Long, val slot: KeySlot)
class PreparedKey(val slot: KeySlot, val rawKey: ByteArray)      // rawKey = ByteArray(0) for PLAIN

sealed interface UnlockResult {
    class Key(val rawKey: ByteArray) : UnlockResult              // PLAIN: ByteArray(0)
    data class PasswordRejected(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult
    data class Throttled(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult
    data object PasswordNeeded : UnlockResult                    // PASSWORD slot, password == null
    data object KeyFailed : UnlockResult                         // section 5.2 KeyUnavailable
}
enum class KeyFailure { KeyUnavailable, InvalidState }
class KeyVaultException(val failure: KeyFailure) : Exception(failure.name)   // constant message, no cause

interface KeyVault {
    suspend fun readState(): KeyState?                                       // null = no file; throws KeyVaultException(InvalidState) for an unreadable or invalid file
    suspend fun unlock(password: ByteArray?): UnlockResult                   // effect E2
    suspend fun openKey(slot: KeySlot, password: ByteArray?): UnlockResult   // key change, restore from Corrupt; db-lifecycle.md
    suspend fun prepare(target: KeyMode, newPassword: ByteArray?): PreparedKey   // new DEK and slot; persists nothing but a missing KEK
    suspend fun commit(generation: Long, slot: KeySlot)                      // AtomicFile rewrite = the commit point
    suspend fun eraseState()                                                 // AtomicFile.delete(), then deleteKek()
    suspend fun deleteKek()                                                  // failures ignored
}
```
- `unlock(password)` is effect E2's entry. It reads the committed state with `readState()` (an invalid file returns `KeyFailed`), then: `PLAIN` returns `Key(ByteArray(0))`; `DEVICE` calls `openKey(slot, null)`; `PASSWORD` with `password == null` returns `PasswordNeeded`; otherwise `openKey(slot, password)`. `null` means "no password supplied"; an empty array means "supplied but unusable" (section 6.3 returns null for malformed input and E2 passes `ByteArray(0)` then).
- `openKey(slot, password)` order for a `PASSWORD` slot, all under the mutex so parallel attempts cannot bypass the throttle: (1) throttle check: while `AppTime.elapsedMillis() < retryAt` return `Throttled` without any crypto; (2) an empty password returns `PasswordRejected` with the stored values, not counted (such a password can never have been set); (3) Keystore layer (section 5.2 on failures); (4) PBKDF2 and the inner GCM: `AEADBadTagException` increments `unlockFailures`, persists both values before returning `PasswordRejected` (the write runs in `NonCancellable`); success resets both and returns `Key`. `DEVICE` and `PLAIN` slots involve no backoff.
- `KeyVault` alone reads and writes `unlockFailures` and `unlockRetryAtElapsedMs` (through `SettingsStore`), and `commit` resets both after its file write succeeded (a commit follows a verified password or an explicit recovery action).
- `prepare` is specified in sections 3.2 and 3.3 and never persists anything except creating a missing KEK. For `PASSWORD`, `newPassword` must be non-empty (`require`). It zeroes the DEK and the password KEK before returning. Cancelling the caller while `prepare()` runs does not cancel it, because `DbMaintenance` runs every operation detached in `applicationScope` (section 3.6 below, ownership of secret arrays): the slot that `commit` later stores unlocks with the typed password ([testing.md](testing.md) L-03).
- Test seam for zeroing: `DefaultKeyVault` takes `newSecret: (Int) -> ByteArray = ::ByteArray` and allocates every 32-byte DEK, every 32-byte password-KEK array and the 67-byte raw key through it. `KeyVaultTest` injects an allocator that records every array it hands out and asserts that all of them, except the raw key returned inside `UnlockResult.Key` or `PreparedKey`, are all-zero when `unlock`, `openKey` or `prepare` returns ([testing.md](testing.md) L-03). The calibration of section 3.3 coerces a measured duration of 0 to 1 ms, so `iterationsFor(0)` returns 5,000,000 without a division by zero.
- Every operation that reports a failed password check persists the counters first (item 4 of `openKey`): `KeyVault` writes `security.unlock_failures` and `security.unlock_retry_at` before it returns `PasswordRejected`, and resets both after a correct password and in `commit`. `KeyVault` returns `UnlockResult` and throws `KeyVaultException` rather than `AppResult` because its callers (effect E2 and `DbMaintenance`) map the results to gate events and to `AppError` themselves ([architecture.md](architecture.md#error-model)).
- Error mapping at the boundary ([architecture.md](architecture.md) § Error model): E2 maps `Key` to `DatabaseHolder.open`, `PasswordRejected` and `Throttled` to the gate event `PasswordRejected(failures, retryAt)` (T21), `PasswordNeeded` to T22 and `KeyFailed` to T23. `DbMaintenance` maps `PasswordRejected` to `AppError.WrongPassword`, `Throttled` to `AppError.Throttled(retryAtElapsedMs)`, `KeyFailed` and every `KeyVaultException` to `AppError.KeyUnavailable`; it zeroes `Key.rawKey` at once when it only verified a password.
- Ownership of secret arrays. A secret array changes owner when it is passed to a call that starts a detached operation. `SessionGate.dispatch(PasswordSubmitted(chars))` hands `chars` to effect E2, and every `DbMaintenance` entry point that takes a password hands it to the operation; the receiver zeroes the arrays in its own `finally` after the last use. The caller never reads, zeroes or reuses an array after handing it over, even when the caller is cancelled (the operation runs detached in `applicationScope`, [architecture.md](architecture.md) § Layering and data flow). A caller that still needs the array passes `copyOf()`. `KeyVault` never zeroes its inputs; arrays it returns belong to the receiver. `PreparedKey.rawKey` belongs to `DbMaintenance` until `commit` returns `Committed`, then to `DatabaseHolder`; on any failure before the commit `DbMaintenance` zeroes it.

## 4. Key-change flows (DEC-61)

Each flow re-encrypts through the export-and-swap pipeline with a new DEK (none when the target is `PLAIN`). Mechanics (free space, staging, export, verification, swap, reopen): [db-lifecycle.md](db-lifecycle.md#key-changes). This section owns what is asked, checked and zeroed. Dialog texts: [ui.md](ui.md#88-encryption-and-startup-password-dec-61-dec-64).

| Flow | From → to | Asks for | Export target key |
|---|---|---|---|
| Turn on encryption | `PLAIN` → `DEVICE` | Confirmation | New raw key |
| Turn on encryption with password | `PLAIN` → `PASSWORD` | New password twice + checkbox | New raw key |
| Set password | `DEVICE` → `PASSWORD` | New password twice + checkbox | New raw key |
| Change password | `PASSWORD` → `PASSWORD` | Current password; new password twice + checkbox | New raw key |
| Remove password | `PASSWORD` → `DEVICE` | Current password | New raw key |
| Turn off encryption | `DEVICE` → `PLAIN` | Confirmation | `ByteArray(0)` |
| Turn off encryption | `PASSWORD` → `PLAIN` | Current password + confirmation | `ByteArray(0)` |

Before the two flows that set a first startup password (`PLAIN` → `PASSWORD`, `DEVICE` → `PASSWORD`), the UI offers a backup (DEC-73, [ui.md § 8.8](ui.md#88-encryption-and-startup-password-dec-61-dec-64)). The offer adds no check and no key handling to this section.

Preconditions: `SessionGate` is `Ready` and no `DbMaintenance` operation runs. The biometric lock adds no prompt (the user is already past it). The entry point is `DbMaintenance.changeKey(target, currentPassword, newPassword)`; the arrays it receives are its own (section 3.6).
1. Collect the input in one dialog; password fields follow section 6. The new password is checked with `PasswordPolicy.checkNewPassword` (section 6.3); a violation returns `AppError.InvalidInput(rule)` and stops the flow.
2. Source `PASSWORD`: verify the current password with `KeyVault.openKey(slot, passwordBytes(current))`. `PasswordRejected` maps to `AppError.WrongPassword`, shows inline, counts toward the backoff (section 5.3, except malformed or empty input, which never counts) and stops the flow. `Throttled` maps to `AppError.Throttled`: no PBKDF2 runs, and the dialog shows the countdown. Zero the returned raw key at once; the export uses the live key held by `DatabaseHolder`.
3. `KeyVault.prepare(target, newPassword)`: new DEK, KEK ensured, for `PASSWORD` calibration (section 3.3), derivation and both wraps, raw-key encoding, self-check (section 3.2). It zeroes the DEK and the password KEK before returning and persists nothing.
4. `DbMaintenance` exports from the live key to `rawKey`, verifies, and commits with `KeyVault.commit(newGeneration, slot)` (through `commitGeneration`).
5. After the commit `DatabaseHolder` owns `rawKey` and zeroes the old live key once the old Room instance and the maintenance connection are closed. Exclusive mode itself never zeroes the live key (section 6.4), so the export source and the rollback reopen can use it. Target `PLAIN`: `KeyVault.deleteKek()`. After any committed key change, `cacheDir/share/` is emptied: earlier share copies would otherwise stay readable in the form the user just replaced ([db-lifecycle.md](db-lifecycle.md#key-changes) step 14 and [Share-file lifetime](db-lifecycle.md#share-file-lifetime) list this trigger; DEC-83).
6. Any failure before the commit: zero `rawKey`, keep the old database and slot, show the error. A KEK created in step 3 stays and is reused next time.

- Turning encryption on states that existing backups and share copies are not changed; turning it off states that notes will be stored unencrypted (`enc_on_body`, `enc_off_body` in [ui.md](ui.md#1111-passwords-encryption-and-authentication-prompts)).
- Backup, and a Restore started from `Ready`, keep the slot: the export target key is the live raw key (`DatabaseHolder.copyLiveKey()`) and the new generation commits the same slot. They never change a setting of section 2, and a backup password never becomes the database password (DEC-83, DEC-84). Recovery-origin actions follow section 4.1.

### 4.1 Key handling of recovery actions
Restore and Start fresh are also offered on the Corrupt (DEC-85) and KeyUnavailable (DEC-86) screens, where Room is closed and the live key was zeroed (E3). Mechanics: [db-lifecycle.md](db-lifecycle.md#origins-and-target-key) and [db-lifecycle.md](db-lifecycle.md#start-fresh). Security handling:

| Action | Key configuration of the new generation | Key for the export target | Security side effects |
|---|---|---|---|
| Restore from `Corrupt`, mode `PLAIN` | Unchanged | `ByteArray(0)` | none |
| Restore from `Corrupt`, mode `DEVICE` | Unchanged slot | `KeyVault.openKey(slot, null)` again | A Keystore failure ends the restore with `AppError.KeyUnavailable`; the user stays on the Corrupt screen |
| Restore from `Corrupt`, mode `PASSWORD` | Unchanged slot | The restore dialog asks for the database password once; `openKey(slot, passwordBytes)` | A database-password check: counts toward the backoff and is subject to `Throttled` (section 5.3) |
| Restore from `KeyUnavailable` | `PLAIN` slot (the current configuration cannot produce a key) | `ByteArray(0)` | `commit` of the `PLAIN` slot resets the backoff values; then `KeyVault.deleteKek()`; the confirmation states that encryption will be off |
| Start fresh from `Corrupt` or `KeyUnavailable` | Empty `PLAIN` database, `PLAIN` slot | `ByteArray(0)` | As the row above; the encryption-off notice follows (DEC-85) |

Every other setting of section 2 stays unchanged (L-12 in [testing.md](testing.md)).

## 5. Unlock, errors and erase

### 5.1 Cold start per mode
States and transitions: [architecture.md](architecture.md) § SessionGate. All key work happens in `Opening` (DEC-90).

| Mode | Before `Opening` | In `Opening` |
|---|---|---|
| `PLAIN` | `Locked` if the biometric lock is on | Key `ByteArray(0)` |
| `DEVICE` | `Locked` if the biometric lock is on | Keystore layer → DEK → raw key |
| `PASSWORD` | `NeedsPassword`; the correct password also satisfies the biometric lock (DEC-65, Q8) | Keystore layer → PBKDF2 → inner GCM → DEK → raw key |

1. On submit, the gate UI copies the field into a `CharArray`, clears the field and dispatches `PasswordSubmitted(chars)` (T7, only while no backoff runs). Effect E2 (package `session`) owns the array: it calls `passwordBytes` (section 6.3), passes the bytes (or `ByteArray(0)` when `passwordBytes` returned null) to `KeyVault.unlock`, and zeroes the chars and the bytes in its `finally`. The screen shows progress.
2. `Opening` removes the Keystore layer, derives with the stored salt and iterations, and decrypts the inner layer. An `AEADBadTagException` there is WrongPassword: `PasswordRejected` (T21), back to `NeedsPassword` with the error.
3. On success: encode the raw key, zero the DEK, the password KEK and the password bytes, reset `security.unlock_failures` and `security.unlock_retry_at` to 0 (both done inside `KeyVault`), and open the database with `DatabaseHolder.open(generation, rawKey)`, which takes ownership of the key.
4. The inner GCM tag proves the DEK, so no later open failure is reported as a wrong password or counted toward the backoff. A Corrupt classification goes to the Corrupt screen (T17). After a `viaPassword` open (Password mode) a transient failure returns to `NeedsPassword(couldNotOpen)` with the could-not-open message, not the wrong-password message (T18), because E3 zeroed the key and the password bytes are gone, so the password must be typed again; `unlock_failures` stays 0. In `PLAIN` and `DEVICE` mode a transient failure stays on the open-error screen with "Try again" (T19, T20).

### 5.2 Error classes
| Class | Raised when | Result |
|---|---|---|
| WrongPassword | `AEADBadTagException` on the inner (password) layer. Any other exception on the password layer is KeyUnavailable | `NeedsPassword` with an error; counts for backoff; never Corrupt; never deletes |
| KeyUnavailable | The Keystore layer fails: a missing alias; any `java.security.GeneralSecurityException` (`KeyStoreException`, `UnrecoverableKeyException`, `InvalidKeyException` incl. `KeyPermanentlyInvalidatedException`, `IllegalBlockSizeException`, `BadPaddingException` incl. `AEADBadTagException`, `InvalidAlgorithmParameterException`, `NoSuchAlgorithmException`, `NoSuchProviderException`, `CertificateException`), `java.security.ProviderException` (incl. `StrongBoxUnavailableException`) or `java.io.IOException` raised while loading the KeyStore, fetching the alias or running the outer cipher; an outer plaintext that is not 32 bytes (`DEVICE`) or 48 bytes (`PASSWORD`). Also: the key-state file is invalid (section 3.5) or unreadable, or missing next to a database file that is not plaintext ([db-lifecycle.md](db-lifecycle.md#startup-reconciliation)) | KeyUnavailable screen (DEC-86) |
| Corrupt | Open or `quick_check` fails with a proven key (`PLAIN`: the empty key; otherwise a GCM-verified DEK), or `SQLiteDatabaseCorruptException` at runtime | Corrupt screen (DEC-85) |

- The classification is by exception class, never by message. AndroidKeyStore reports most operation failures from `doFinal` as `IllegalBlockSizeException` (cause `KeyStoreException`) or `BadPaddingException`, so the list is the whole `GeneralSecurityException` family, not a selection: an unlisted exception would crash-loop the app at every cold start, before any recovery screen can show ([architecture.md](architecture.md) § Error model).
- `KeyVault` retries a Keystore-layer exception once after 1 s (`delay(1000)` on `dispatchers.io`), for every item except `AEADBadTagException` and a wrong plaintext length, which are never retried. A retry is not an attempt: it never touches the backoff.
- A damaged inner IV, salt or iteration count of a `PASSWORD` slot fails the outer tag (section 3.1) and is therefore KeyUnavailable, never WrongPassword, and leaves `unlock_failures` unchanged.
- A programming error (`IllegalStateException`, `NullPointerException`, ...) is not classified: it crashes ([architecture.md](architecture.md) § Error model).

Full classification, including `SQLiteNotADatabaseException`, missing files and transient I/O: [db-lifecycle.md](db-lifecycle.md#failure-classification).

### 5.3 Backoff (DEC-62)
- Counts consecutive WrongPassword results (`AEADBadTagException` on the inner layer) of every database-password check: the cold-start password, the current-password check of section 4, and the startup-password question of a Restore from the Corrupt screen in `PASSWORD` mode (section 4.1, [ui.md § 8.7](ui.md#87-restore-dec-84)). Malformed or empty input is answered with the wrong-password message but never counted (section 6.1). Backup passwords at restore are not throttled (the file can be attacked offline anyway).
- Schedule: after failure n ≥ 5 the delay is min(30 s × 2^(n−5), 300 s): 30, 60, 120, 240, then 300 s. Failures 1–4 have no delay. Pure function `UnlockBackoff.delayMillis(failures)` in package `model`.
- Time base: `AppTime.elapsedMillis()` (elapsed realtime), so moving the system clock changes nothing (A1). `AppError.Throttled.retryAtElapsedMs`, `UnlockResult.retryAtElapsedMs`, `NeedsPassword.retryAt` and `unlockRetryAtElapsedMs` all carry this base.
- Persist `unlockFailures` and `unlockRetryAtElapsedMs = AppTime.elapsedMillis() + delayMillis(failures)` (0 when the delay is 0) before the error shows, so killing the app does not reset them.
- Reboot: elapsed realtime restarts at boot. Within one boot `retryAt − now` never exceeds `delayMillis(failures)`; if it does, the device has rebooted and the value is replaced by `now + delayMillis(failures)`. A reboot therefore re-applies the full delay. `UnlockBackoff.effectiveRetryAt(failures, storedRetryAt, now)` (package `model`, pure) implements this; effects E1 and E2 pass the stored pair through it before they put `retryAt` into the gate state, so the password screen shows the effective value, and `KeyVault` writes it back whenever it reads the pair and the value differs.
- While `elapsedMillis() < retryAt`: the password field and the submit button are disabled and a countdown shows ([ui.md](ui.md#102-password-screen)); `KeyVault` returns `Throttled` without PBKDF2 to every caller, including the section 4 current-password check and the Restore-from-Corrupt password question; "Forgot password?" stays available. After a restart the password screen reads the pair from `SettingsStore`, so the countdown is shown at once.
- Both values reset on a correct password, on every `KeyVault.commit` and on Erase. Attempts are never capped and never wipe data.

### 5.4 Forgotten password: Erase (DEC-63)
- "Forgot password?" on `NeedsPassword` opens a screen stating there is no recovery (§8) with the button "Erase all data and start over", which is always enabled ([ui.md](ui.md#103-forgot-password-screen-dec-63)). The button opens a typed-confirmation dialog; the dialog's confirm button enables only while the typed text matches ([ui.md](ui.md#810-erase-all-data-dec-63)). No password or biometric is required: the action equals the system "Clear storage".
- The flow runs as `DbMaintenance.erase()` from `NeedsPassword` (T8); mechanics and the crash matrix: [db-lifecycle.md](db-lifecycle.md#erase-forgotten-password).
1. Delete every generation file set except the committed one `G` (`DELETE_OLD`, [db-lifecycle.md](db-lifecycle.md#erase-forgotten-password) step 2). If any of them remains, the Erase ends with `Err(DatabaseUnavailable)` and outcome `Aborted` and nothing was erased: a leftover plaintext generation would otherwise be adopted at the next start and bring the erased notes back.
2. `KeyVault.eraseState()`: delete `db-state.json`, which makes the encrypted data unreadable at once, then the KEK.
3. Delete every `doro-*` database file, sidecar and staging or share leftover, and the data-reset marker (`DbFiles.deleteEverything()`, [db-lifecycle.md](db-lifecycle.md#files-on-disk)).
4. `SettingsStore.resetSecurity()` (every key of section 2 to its default, both remembered ids cleared), then `SettingsStore.onDataReplaced()` ([architecture.md](architecture.md) § Data reset), so navigation drops ids of the erased database.
5. `MaintenanceFinished(Restart)`: `SessionGate` restarts in the first-run state (T30): an empty `PLAIN` database, with Restore available in Settings.
- Crash points converge. Before step 2 (`db-state.json` still there): the password screen, nothing changed (step 1 deleted only unreferenced generations). Between steps 2 and 3: encrypted files without a key-state file, so the next start shows KeyUnavailable (DEC-86), whose "Start fresh" deletes the files; the settings of step 4 are then not reset except for the two backoff values, which that commit resets (section 3.6). Between steps 3 and 4: startup reconciliation finds a first run and completes the settings reset ([db-lifecycle.md](db-lifecycle.md#startup-reconciliation) step 7).

## 6. Passwords and secrets in memory (DEC-64)

### 6.1 Policy
Applies to the database password and to backup passwords (DEC-83). Implemented once in `core.password.PasswordPolicy` (package `core`, so that `ui` dialogs, `session`, `security` and `data.maintenance` share it; the NFC step uses `android.icu`).
- At least 8 Unicode code points after NFC. No composition rules, no maximum length, no trimming (spaces count), no stored hints.
- A new password is typed twice and both must be equal after NFC, and the "I understand this cannot be recovered" checkbox must be ticked. This applies wherever a new password is set: the database password and every backup export that asks for a backup password ([ui.md](ui.md#86-backup-password-dec-83)).
- The rules, in this order, as `InputRule` values (the enum lives in `core.result` next to `AppError`; it also holds the non-password rules of the other docs): `PasswordMalformed` (a lone surrogate, rejected by the UTF-8 encoder), `PasswordTooShort`, `PasswordMismatch`, `AcknowledgementMissing`.
- A current password is typed once. Malformed or empty input is answered with the wrong-password message, without running PBKDF2 and without counting toward the backoff, because such a password can never have been set.

### 6.2 Fields and state
- Use `OutlinedSecureTextField` with `textObfuscationMode = TextObfuscationMode.Hidden`; `Visible` only while the user's show toggle ([ui.md](ui.md#81-common-rules)) is on. Never `RevealLastTyped` (the component's default): it shows each typed character briefly, so a screen recording or screen share (A5) captures the password one character at a time even with screenshot protection off. Single line, password keyboard options (`KeyboardType.Password`, autocorrect off), cut and copy disabled (the secure-field default).
- Submit (button and IME action) is disabled while the field is empty.
- Hold the text in `remember { TextFieldState() }`. Never `rememberTextFieldState()` or any `rememberSaveable`: both write the text into the saved-instance Bundle.
- Never put a password in a ViewModel property or flow, `SavedStateHandle`, a Bundle, an Intent, a navigation key or a log.
- On submit: `val chars = CharArray(state.text.length) { state.text[it] }`, then `state.clearText()`, then hand `chars` to its owner: the gate dispatches `PasswordSubmitted(chars)`; a dialog calls the ViewModel method that starts the `DbMaintenance` operation. The UI keeps no reference (ownership: section 3.6).
- Every dialog with a password field is wrapped in `SecureDialogWindow` (section 11).

### 6.3 Normalization and encoding
```kotlin
// package com.doro.organizer.core.password
/** CharArray -> NFC CharArray without creating a String. Zeroes its input and the intermediate StringBuilder. */
internal fun nfc(chars: CharArray): CharArray {
    val sb = StringBuilder(chars.size * 3 + 16)                          // no reallocation
    Normalizer2.getNFCInstance().normalize(CharBuffer.wrap(chars), sb)   // android.icu.text (API 24)
    val out = CharArray(sb.length).also { sb.getChars(0, sb.length, it, 0) }
    for (i in sb.indices) sb.setCharAt(i, '\u0000')
    chars.fill('\u0000')
    return out
}

/** NFC chars -> UTF-8 without a String. Zeroes its input. Returns null for malformed input (a lone surrogate). */
internal fun utf8(nfc: CharArray): ByteArray? {
    val buf = ByteBuffer.allocate(nfc.size * 3)                          // UTF-8: at most 3 bytes per char
    try {
        val enc = StandardCharsets.UTF_8.newEncoder()                    // malformed input -> error result
        if (enc.encode(CharBuffer.wrap(nfc), buf, true).isError || enc.flush(buf).isError) return null
        return buf.array().copyOf(buf.position())
    } finally { buf.array().fill(0); nfc.fill('\u0000') }
}

fun passwordBytes(chars: CharArray): ByteArray? = utf8(nfc(chars))

sealed interface PasswordCheck {
    class Ok(val bytes: ByteArray) : PasswordCheck                       // the caller owns bytes
    data class Rejected(val rule: InputRule) : PasswordCheck
}
/** Zeroes both inputs and every intermediate. Rules in the order of section 6.1. */
fun checkNewPassword(first: CharArray, second: CharArray, acknowledged: Boolean): PasswordCheck {
    val n1 = nfc(first); val n2 = nfc(second)
    val length = Character.codePointCount(n1, 0, n1.size)                // before utf8() zeroes n1
    val b1 = utf8(n1); val b2 = utf8(n2)
    try {
        val rule = when {
            b1 == null || b2 == null -> InputRule.PasswordMalformed
            length < 8 -> InputRule.PasswordTooShort
            !MessageDigest.isEqual(b1, b2) -> InputRule.PasswordMismatch
            !acknowledged -> InputRule.AcknowledgementMissing
            else -> return PasswordCheck.Ok(b1)
        }
        b1?.fill(0)
        return PasswordCheck.Rejected(rule)
    } finally { b2?.fill(0) }
}
```
NFC makes the same password typed on another keyboard or device produce the same bytes, which keeps encrypted backups portable. At unlock and at the section 4 current-password check, a null result from `passwordBytes` is passed on as `ByteArray(0)`, which `KeyVault` answers with `PasswordRejected` without PBKDF2 and without counting.

### 6.4 Lifetimes
Within the database lifecycle the table of [db-lifecycle.md](db-lifecycle.md#key-custody) applies; this table is the general policy and agrees with it.

| Secret | Held by | Zeroed |
|---|---|---|
| Password `CharArray` and bytes | The component that received them: effect E2 for a cold start, the `DbMaintenance` operation for key changes, backups and restores | In that operation's `finally`, after the last use. The caller never zeroes them after handing them over (section 3.6) |
| Password KEK (32 bytes) | `KeyVault`, one wrap or unwrap | Right after the GCM call |
| DEK (32 bytes) | `KeyVault`, one unwrap or `prepare` | Right after raw-key encoding, before the call returns |
| Live raw key (67 bytes) | `DatabaseHolder`, from `open` until the generation it keys is replaced or closed | (a) `close()` (effect E3), after `RoomDatabase.close()`; (b) a failed open or reopen; (c) a committed key change, once the old Room instance and the maintenance connection are closed; (d) `Reopen.StayClosed`. Never earlier: an exclusive-mode close for backup, restore or a key change keeps the array, because `DbMaintenance` exports from it and the rollback reopens with it, and SQLCipher keys WAL reader connections later from the same array. Never while a connection keyed with it is open |
| New raw key (`PreparedKey.rawKey`) | `DbMaintenance` until `Committed`, then `DatabaseHolder` | On any failure before the commit, after every connection keyed with it is closed |
| Copy of the live key for a restore | `DbMaintenance` | After target verification of the restored generation, and on every failure or cancellation |
| Backup password bytes | `DbMaintenance`, one export or restore operation | After the last connection keyed with them has closed, including the maintenance connection and any temporary Room instance used for migrations ([db-lifecycle.md](db-lifecycle.md#key-custody)); a retry after a wrong backup password uses new bytes |

- DEC-64's "The DEK stays in memory only while the database is open and is zeroed after `close()`" refers to the raw-key encoding held by `DatabaseHolder`; the 32-byte binary DEK exists only inside one `KeyVault` call.

### 6.5 Known limitations
- Compose text buffers, IME and accessibility copies and any `String` made by text layout cannot be zeroed; a moving GC can leave stale array copies; `SecretKeySpec`, `Mac` and `Cipher` keep key copies that cannot be cleared; SQLCipher keeps its key schedule in native memory while a connection is open. Zeroing shortens lifetimes; it does not guarantee erasure (live process access is out of scope).
- Editor drafts and search text in `SavedStateHandle` (DEC-28, DEC-37) are kept by the system across process death, outside the app's encryption. They are never shown before unlock because `NavDisplay` is composed only in `Ready` and `Maintenance` (DEC-90).

## 7. Biometric app lock (DEC-65)

### 7.1 Availability and the Settings row
`ui` never imports `androidx.biometric` ([architecture.md](architecture.md) § Import rules). `SettingsViewModel` asks `AppLockController.availability()`, which wraps `BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)` and `KeyguardManager.isDeviceSecure()`; it runs each time Settings resumes and before enabling.

```kotlin
enum class BiometricAvailability { Success, StatusUnknown, NoneEnrolled, Unavailable }   // Unavailable = HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, NO_HARDWARE, UNSUPPORTED
class LockAvailability(val biometric: BiometricAvailability, val deviceSecure: Boolean)
```

| Result | Lock off | Lock on |
|---|---|---|
| `BIOMETRIC_SUCCESS` | Toggle shown and enabled | Toggle shown |
| `BIOMETRIC_STATUS_UNKNOWN` | Toggle shown and enabled; it turns on only after the test prompt succeeds | Toggle shown |
| `BIOMETRIC_ERROR_NONE_ENROLLED` | Toggle disabled, with an enroll hint and an enroll action (button text: [ui.md § 9.4](ui.md#94-biometric-lock)) | Toggle shown; turning off uses the fallback (7.6) |
| `ERROR_HW_UNAVAILABLE`, `ERROR_SECURITY_UPDATE_REQUIRED`, `ERROR_NO_HARDWARE`, `ERROR_UNSUPPORTED` | Row hidden | Toggle shown; turning off uses the fallback (7.6) |

Screen layout and strings: [ui.md](ui.md#94-biometric-lock).
- While the lock is on, the row is always shown so that the user can turn it off.
- Enroll, launched through `ExternalActivities` under the re-lock exemption (7.7): API 30+ `Settings.ACTION_BIOMETRIC_ENROLL` with `EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED = BIOMETRIC_STRONG`; API 28–29 `Settings.ACTION_FINGERPRINT_ENROLL`. On `ActivityNotFoundException` (OEMs may not implement the action) `ExternalActivities` launches the request's fallback `Settings.ACTION_SECURITY_SETTINGS`; if that also throws, it ends the exemption and the screen shows only the enroll hint. Never `resolveActivity`: under package visibility its answer depends on the device and can be null although the activity exists.
- Turning on: one successful `BIOMETRIC_STRONG` prompt (never the fallback), then `security.app_lock = true`. Turning off: one successful authentication on the unlock route (7.4), then `false`. A cancel or an error leaves the value unchanged.
- The lock never touches the DEK or the Keystore (§11 App Lock, Key Management).

### 7.2 PromptInfo
```kotlin
val strongPrompt = BiometricPrompt.PromptInfo.Builder()
    .setTitle(title)                                   // texts: ui.md § 8.11 (biometric_unlock_title, biometric_enable_title, biometric_disable_title)
    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    .setNegativeButtonText(cancelText)                 // required without DEVICE_CREDENTIAL
    .setConfirmationRequired(false)                    // a hint; passive face unlock needs no extra tap
    .build()
val credentialPrompt = BiometricPrompt.PromptInfo.Builder()   // API 30+ fallback only
    .setTitle(title)
    .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    .build()                                           // a negative button is not allowed here
```
`authenticate(promptInfo)` is called without a `CryptoObject`. Titles and negative buttons: [ui.md](ui.md#811-system-authentication-prompts-dec-65).

### 7.3 AppLockController and MainActivity
```kotlin
// package com.doro.organizer.security (the only importer of androidx.biometric)
enum class AuthPurpose { Unlock, EnableLock, DisableLock }

/** The platform adapter beneath AppLockController: everything that touches BiometricManager, BiometricPrompt, KeyguardManager and the system intents.
 *  Production: DefaultBiometricGateway. Tests: FakeBiometricGateway (testing.md § 7), so the re-lock rules, the exemption and the auto-prompt run for real. */
interface BiometricGateway {
    fun canAuthenticate(): BiometricAvailability                   // BiometricManager.canAuthenticate(BIOMETRIC_STRONG), mapped as in 7.1
    fun isDeviceSecure(): Boolean                                  // KeyguardManager.isDeviceSecure()
    fun attach(activity: FragmentActivity)                         // creates BiometricPrompt(activity, mainExecutor, callback); only the newest instance receives callbacks
    fun detach(activity: FragmentActivity)
    fun authenticate(request: PromptRequest, onResult: (PromptResult) -> Unit)   // strongPrompt or credentialPrompt (7.2), never a CryptoObject
    fun credentialIntent(title: String): Intent?                   // API 28–29: createConfirmDeviceCredentialIntent(title, null); null without a secure lock screen
    fun enrollIntents(): Pair<Intent, Intent>                      // the API-dependent enrollment intent (7.1) and the ACTION_SECURITY_SETTINGS fallback
}
class PromptRequest(val title: String, val credentialOnly: Boolean, val negativeText: String?)   // negativeText only when !credentialOnly
sealed interface PromptResult { data object Succeeded : PromptResult; class Error(val code: Int, val message: String) : PromptResult }

// package com.doro.organizer.security. Production class DefaultAppLockController(biometric, externalActivities, settingsStore, appTime, processLifecycle, listener: () -> LockListener)
interface AppLockController : ExternalCallbacks {                  // ExternalCallbacks: core.external, architecture.md § Result launchers and process death
    fun attach(activity: FragmentActivity)                         // MainActivity.onCreate, every instance
    fun detach(activity: FragmentActivity)                         // MainActivity.onDestroy
    fun availability(): LockAvailability                           // section 7.1
    fun prompt()                                                   // the lock screen's "Unlock" button
    suspend fun confirm(purpose: AuthPurpose): AppResult<Boolean>  // EnableLock / DisableLock: Ok(true) success, Ok(false) cancelled, Err(AuthFailed) otherwise
    fun onGateLockedChanged(locked: Boolean)                       // called by SessionGate when the state enters or leaves Locked (architecture.md § Event wiring)
    // from ExternalCallbacks: beforeLaunch(kind) arms the exemption (7.7), afterResult(kind) clears it, onCredentialResult(resultCode) (7.6)
}
```
- `AppLockController` is application-scoped (`AppContainer`). It holds only the exemption (`exemptUntil`), `promptInFlight`, the auto-prompt flag and `credentialPurpose`. Whether the app is locked is `SessionGate`'s `Locked` state (DEC-90), which is never persisted (no `SavedStateHandle`, no `rememberSaveable`), so every cold start is locked when the lock is on. It observes the process `LifecycleOwner` that `AppContainer` provides (production: `ProcessLifecycleOwner.get()`; tests: the fake of [testing.md](testing.md) § 7) and reads time only from `AppTime`. It raises gate events through `LockListener` ([architecture.md](architecture.md) § Event wiring).
- `MainActivity` (a `FragmentActivity`, DEC-04) calls `appLockController.attach(this)` in every `onCreate`; `attach` calls `biometric.attach(activity)` and starts the auto-prompt collector below. `onDestroy` calls `detach`. The API 28–29 credential intent is an `ExternalRequest` of kind `CredentialConfirm` ([architecture.md](architecture.md) § Result launchers and process death): the launcher is registered by `ExternalActivities.bind` in `onCreate` (before STARTED), `beforeLaunch` arms the exemption, and the result callback calls `afterResult` and then `onCredentialResult(resultCode)` at once, in every gate state, because that result is what unlocks `Locked`; it never waits in the inbox.
- The prompt survives configuration changes and reports to the new instance: track `promptInFlight` (set by `authenticate`, cleared by every callback) and never call `authenticate` while it is set. `detach(activity)` clears the flag only when `!activity.isChangingConfigurations` (the prompt dies with its activity); across configuration changes the flag is kept, so rotating during the prompt shows one prompt.
- Auto-prompt: a one-shot flag is set by `onGateLockedChanged(true)` when `SessionGate` enters `Locked`, and at each process ON_START while `Locked`, except an ON_START that occurs while an exemption is active (it ends an app-launched trip, 7.7). `attach` collects the flag with `activity.lifecycleScope.launch { activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { … } }`: whenever the activity is RESUMED and the flag is set, `AppLockController` clears it and prompts, provided the gate is still `Locked` and `promptInFlight` is false. This covers both orders: the flag set before ON_RESUME (return from background) and after it (cold start, where `Locked` is entered after E1 finished while the activity is already RESUMED behind the splash). Never prompt from composition.
- After `USER_CANCELED`, `NEGATIVE_BUTTON`, a non-OK credential result or any error that shows the Unlock button, nothing prompts until the next ON_START without an exemption; the lock screen's "Unlock" button prompts on demand ([ui.md](ui.md#104-lock-screen)).

### 7.4 Unlock route and error mapping
At prompt time, `canAuthenticate(BIOMETRIC_STRONG)` = SUCCESS or STATUS_UNKNOWN uses `strongPrompt`; anything else uses the fallback (7.6). `onAuthenticationFailed` (one rejected attempt, prompt still open) changes nothing.

Each prompt carries a purpose (`AuthPurpose`). Only `Unlock` raises gate events; `EnableLock` and `DisableLock` return `AppResult<Boolean>` to `SettingsViewModel`: success is `Ok(true)`, codes 5, 10 and 13 (and a non-OK credential result) are `Ok(false)` (no change, no message), any other error is `Err(AppError.AuthFailed)`.

`AppLockController` raises exactly these gate events ([architecture.md](architecture.md) § SessionGate):
- `Unlocked`: `onAuthenticationSucceeded`, credential `RESULT_OK`, and auto-off (7.6).
- `AuthError(LockError(code, message = null))` (silent): codes 5, 10, 13, 3, and credential results other than OK.
- `AuthError(LockError(code, message = errString))`: codes 2, 4, 7, 8 and any code not listed below; the lock screen shows `errString`. A `null` message is the only thing that distinguishes the silent case ([architecture.md](architecture.md) § States, events and effects).
- No event for codes 1, 9, 11, 12, 15: the fallback starts at once. Code 14 (credential prompt only) is auto-off.

| `onAuthenticationError` code | Gate behavior |
|---|---|
| 5 CANCELED | Silent; stay `Locked`; auto-prompt at the next return to foreground |
| 10 USER_CANCELED, 13 NEGATIVE_BUTTON, 3 TIMEOUT | Silent; "Unlock" button; no automatic retry |
| 2 UNABLE_TO_PROCESS, 4 NO_SPACE, 8 VENDOR, any other code | Show `errString`; "Unlock" button |
| 7 LOCKOUT | Show `errString`; "Unlock" button (the system enforces the 30 s wait) |
| 1 HW_UNAVAILABLE, 9 LOCKOUT_PERMANENT, 11 NO_BIOMETRICS, 12 HW_NOT_PRESENT, 15 SECURITY_UPDATE_REQUIRED | Fallback (7.6) at once |
| 14 NO_DEVICE_CREDENTIAL (credential prompt only) | Auto-off (7.6) |
| API 28–29 credential intent: `RESULT_OK` | `Unlocked` |
| API 28–29 credential intent: any other result code | Silent; "Unlock" button; no automatic retry (as 10) |

- Verify at setup: what `canAuthenticate(BIOMETRIC_STRONG)` returns during a temporary LOCKOUT (code 7) and after LOCKOUT_PERMANENT. If it is not SUCCESS during LOCKOUT, use `strongPrompt` for that case so that row 7 applies instead of the fallback.

### 7.5 Password mode (Q8)
- Cold start: `NeedsPassword` only; the correct password also clears the lock.
- Return from background with the lock on: `Locked`, biometric only; the database stays open and no password is asked.
- Without the lock, `PASSWORD` mode never prompts on return from background (DEC-62).

### 7.6 Device-credential fallback (Q7)
- Used when strong biometrics are unavailable or permanently locked out (7.4), for unlocking and for turning the lock off.
- API 30+: `credentialPrompt` on the same `BiometricPrompt`.
- API 28–29: `BiometricGateway.credentialIntent(title)` (`KeyguardManager.createConfirmDeviceCredentialIntent(title, null)`) posted as an `ExternalRequest` of kind `CredentialConfirm`, under the re-lock exemption (it starts a system activity, so `MainActivity` stops); `credentialPurpose` records `Unlock` or `DisableLock`. `RESULT_OK` unlocks (`Unlock`) or sets `security.app_lock = false` (`DisableLock`); any other result code is handled like USER_CANCELED (7.4). Returning from the credential screen produces a process ON_START, which does not set the auto-prompt flag because the exemption is still active (the result callback clears it afterwards); this is what prevents a loop after Back. Below API 30, BiometricPrompt supports neither `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` nor `DEVICE_CREDENTIAL` alone.
- Auto-off: if `KeyguardManager.isDeviceSecure()` is false (or the intent is null), set `security.app_lock = false` and `security.lock_auto_off_notice = true`, raise `Unlocked`, and continue as with the lock off. The notice shows once and clears the flag ([ui.md](ui.md#94-biometric-lock)).
- Verify at setup: the deprecated `createConfirmDeviceCredentialIntent` works on the API 28 and 29 emulators.

### 7.7 Re-lock rules
- `AppLockController` observes the process lifecycle from process start. `ProcessLifecycleOwner` posts its delayed pause 700 ms after the last activity pauses and dispatches ON_STOP at that moment, or when the activity stops if that is later; it never fires for configuration changes. A background trip shorter than 700 ms therefore does not lock. Screen-off counts as background.
- On ON_STOP with the lock on and no active exemption, `AppLockController` raises `LockNow` whatever the gate state. `SessionGate` applies it per [architecture.md](architecture.md): `Opening` → `Opening(lockPending)` and the operation ends in `Locked(...)` (T24, T16, T17, T23); `Ready` → `Locked(Ready)`, content leaves composition and Room stays open (T25); `Maintenance` of any origin → `Locked(this Maintenance)` at once, the operation continues in `applicationScope` and its outcome is applied by T14 and T15 (T32); `Corrupt` and `KeyUnavailable` → `Locked(this state)` (T34); `NeedsPassword` and `Locked` ignore it (T35). There is no grace period, so the first frame after ON_START is the gate.
- A `LockNow` in `Starting` needs no row of its own: it is dropped (T35, or T36 for `Starting(true)`), because `StartupLoaded` and `StartupKeyFailed` read the lock setting and enter `Locked` (T3, T5) whenever the lock is on ([architecture.md](architecture.md) § Transition table, "Locking while starting").
- Exemption: `ExternalActivities` calls `beforeLaunch(kind)` immediately before launching SAF `CreateDocument`/`OpenDocument`, the share chooser (launched for a result), the enrollment intent or the API 28–29 credential intent. It sets `exemptUntil = appTime.elapsedMillis() + 600_000`; a launch while an exemption is active replaces it and restarts `exemptUntil`.
- Every launch runs inside `try` in `ExternalActivities`; on `ActivityNotFoundException` or `SecurityException` it calls `afterResult(kind)` before reporting the failure (otherwise a launch that never paused the activity would leave the exemption armed for 10 minutes and a real background trip would not lock). SAF and share launch failures become `AppError.FileAccess` after the exemption is cleared; enrollment failures follow 7.1 (the request carries the `ACTION_SECURITY_SETTINGS` fallback intent).
- While the exemption is active, ON_STOP does not lock. The activity-result callback (`afterResult`) clears it; results arrive after the process ON_START and before `onResume`, so the exemption is still set at ON_START. The next activity ON_RESUME also clears it. At an ON_START after `exemptUntil` the exemption is cleared and `LockNow` is raised: the exempted stop was a real background trip.
- Results that arrive while `Locked`: `CredentialConfirm` is delivered at once, in every gate state (7.3). `Backup` and `Restore` results wait in the `ExternalActivities` inbox and are processed after unlock (DEC-90). `Share` and `Enroll` results are discarded after `afterResult`. A process killed in background restarts locked.
- Verify at setup: with the real `ProcessLifecycleOwner` on api28 and api35, the `CreateDocument` result callback runs after the process ON_START and before `MainActivity.onResume`; the whole exemption rule depends on that order.

## 8. Screen protection (DEC-66, DEC-67)

- In `MainActivity.onCreate`, before `setContent`: `window.addFlags(FLAG_SECURE)` and, on API 33+, `setRecentsScreenshotEnabled(false)`. Starting closed guarantees protection before the first frame. Then collect the window policy ([architecture.md](architecture.md#mainactivity-startup-order) § MainActivity startup order) and apply it live, without recreation:
  - `windowPolicy = combine(settingsStore.settings, dbMaintenance.keyMode, sessionGate.state)` ([architecture.md](architecture.md) § Dependency injection owns the code). While the settings value or the key mode is null (the first `SettingsStore` read has not finished, or the key-state is not valid), the policy stays closed: `FLAG_SECURE` on, the recents screenshot off and overlays hidden. A closed policy is still emitted, so the splash is released.
  - `FLAG_SECURE` ⇔ `security.screenshot_protection` (`addFlags`/`clearFlags`), or the policy is closed.
  - API 33+: `setRecentsScreenshotEnabled(!(biometricLock || keyMode != PLAIN))`.
  - API 31+: `setHideOverlayWindows(hideOverlays)` (section 9.4).
  - `LocalKeyboardPrivacy` (section 11) is provided from the same policy.
- The flag covers the whole window: lists, calendar, editor, pickers, Settings and gate screens (spec errata, DEC-67).
- Compose `Dialog`, `Popup`, `ModalBottomSheet`, `DropdownMenu`, `AlertDialog` and `DatePickerDialog` keep `securePolicy = SecureFlagPolicy.Inherit` (the default). `SecureFlagPolicy.SecureOff` is banned (`ArchitectureTest`, [architecture.md § Boundary test](architecture.md#boundary-test)).
- No View-based windows (`android.app.Dialog`, `PopupWindow`, framework pickers). If one becomes unavoidable, it sets `window.setFlags(FLAG_SECURE, FLAG_SECURE)` itself while protection is on. The toggle sits on the Settings screen with no dialog open, so no window keeps a stale flag.
- No user data in Toasts (drawn by the system outside the window on API 30+) or notifications (the app posts none and never requests `POST_NOTIFICATIONS`). Snackbars carry counts only (DEC-36).
- While `Locked`, and on `Starting`, `NeedsPassword`, `Opening` and the Erase screen, content UI is not composed and no screen shows previews, names, counts or dates (DEC-66). The Corrupt and KeyUnavailable screens may show DEC-84's restore confirmation, whose counts are read from the chosen backup file, never from the live database.
- System content capture: on API 29+ the platform can stream the text of an app's views to an OEM intelligence service unless the app opts out. `MainActivity.onCreate` calls `getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)` (the setting is not persisted across reboots, so it runs on every `onCreate`). Verify at setup: `isContentCaptureEnabled()` returns false afterwards on api35.

## 9. Platform hardening (DEC-68, DEC-69)

### 9.1 Manifest
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" tools:node="remove" />
    <uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" />

    <application
        android:name=".DoroApplication"
        android:allowBackup="false"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:enableOnBackInvokedCallback="true"
        android:taskAffinity=""
        tools:targetApi="33"> <!-- enableOnBackInvokedCallback is an API 33 attribute; icon, label, theme: ui.md; class paths: architecture.md -->
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <provider
            android:name=".BackupFileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```
- `res/xml/file_paths.xml` is exactly `<paths><cache-path name="share" path="share/" /></paths>`; never `files-path`, `root-path` or `path="."`. `BackupFileProvider` (root package) is an empty subclass of `androidx.core.content.FileProvider` (its docs advise subclassing). Share mechanics: [db-lifecycle.md](db-lifecycle.md#backup).
- No other intent filters (no `VIEW` or `SEND` for files), no services or receivers declared by the app in the release manifest (debug-only components live in `src/debug`, which the `manifest` CI job rejects in release), never `directBootAware`. Keep `androidx.startup.InitializationProvider` (ProcessLifecycleOwner needs it) and the profileinstaller receiver.
- `MainActivity` is exported only as the launcher and reads nothing from its launch `Intent` or `onNewIntent` (no extras, data or `ClipData`): a crafted intent from another app (A4) cannot steer it.
- The release manifest has neither `android:debuggable="true"` nor `android:testOnly="true"` (a debuggable release allows `run-as` and debugger attachment, which is live process access; section 9.3).

### 9.2 Backup and transfer rules
`res/xml/data_extraction_rules.xml` (API 31+):
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```
`res/xml/backup_rules.xml` (API ≤ 30):
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="root" path="." />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="external" path="." />
    <exclude domain="device_root" path="." />
    <exclude domain="device_file" path="." />
    <exclude domain="device_database" path="." />
    <exclude domain="device_sharedpref" path="." />
</full-backup-content>
```
- DEC-69 is Owner Q9. If the owner allows device-to-device transfer, only the `<device-transfer>` section changes, and only `PLAIN` databases would arrive usable: `noBackupFilesDir` (the key-state file) and Keystore keys never transfer, so `DEVICE` and `PASSWORD` databases would arrive as KeyUnavailable (Verify at setup).
- There is no `<cross-platform-transfer>` section: it requires `<platform-specific-params>` naming an iOS counterpart app, and none exists. Verify at setup on an API 36.1 image that nothing is offered for cross-platform transfer.

### 9.3 CI checks
Wiring: [testing.md](testing.md#9-ci-pipeline) (`manifest` and `dependencies` jobs).
- Permission allowlist on the release APK's merged manifest (`apkanalyzer manifest permissions`), the content of `config/permission-allowlist.txt`: `android.permission.USE_BIOMETRIC`, `android.permission.USE_FINGERPRINT`, `android.permission.HIDE_OVERLAY_WINDOWS`, `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (stored with the literal placeholder; the job substitutes the `application-id` of the APK, so the gate survives the Q12 decision). Anything else fails. Verify at setup: the exact list the pinned libraries contribute.
- Exported components: only `.MainActivity` and `androidx.profileinstaller.ProfileInstallReceiver` (guarded by `android.permission.DUMP`). DEC-68's "only the launcher activity is exported" refers to the app's own components; the library's DUMP-protected receiver is accepted. Also asserted: `allowBackup="false"`, both rule resources referenced, the provider not exported, no `android:debuggable="true"`, no `android:testOnly="true"` (`apkanalyzer manifest debuggable` and `print`).
- Banned dependencies, matched by group prefix or artifact on `releaseRuntimeClasspath`, `debugRuntimeClasspath`, `debugUnitTestRuntimeClasspath` and `debugAndroidTestRuntimeClasspath` ([testing.md](testing.md#9-ci-pipeline) `dependencies` job; the list is this file's contribution to `config/banned-dependencies.txt`, and "any analytics or ad SDK" means exactly these groups, so a new SDK is banned by adding its group here): `com.google.firebase`, `com.google.android.gms`, `com.google.android.play`, `com.google.android.datatransport`, `com.crashlytics`, `io.sentry`, `com.bugsnag`, `com.squareup.okhttp3`, `com.squareup.retrofit2`, `io.ktor`, `com.facebook.android`, `com.appsflyer`, `com.adjust.sdk`, `com.amplitude`, `com.mixpanel.android`, `com.segment.analytics.kotlin`, `com.datadoghq`, `com.newrelic.agent.android`, `io.embrace`, `com.instabug.library`, `com.onesignal`, `com.applovin`, `com.unity3d.ads`, `com.ironsource.sdk`, `io.branch.sdk.android`, `com.huawei.hms`, `com.jakewharton.timber` (logging policy, section 10), `androidx.security:security-crypto` (DEC-09), `net.zetetic:android-database-sqlcipher` (legacy). Verify at setup: the group ids against Maven Central.
- Manual QA per release: [testing.md](testing.md#10-manual-qa-checklist-before-release) M-06 (backup exclusion).

### 9.4 Task hijacking and overlays
- StrandHogg: Google's mitigation is minSdk 30; version 2 is prevented only by that OS patch. At minSdk 28, unpatched API 28–29 devices stay exposed; accepted. `android:taskAffinity=""` partially mitigates version 1.
- On API 31+ (normal permission `HIDE_OVERLAY_WINDOWS`), `MainActivity` applies `window.setHideOverlayWindows(hideOverlays)` from the window policy (section 8), with `hideOverlays = gate ∉ {Ready, Maintenance with origin Ready}`: the password, lock, recovery, Erase and maintenance-screen states are never overlaid. Dialog windows are separate windows, so every security dialog also sets it on its own window through `SecureDialogWindow(hideOverlays = true)` (section 11). Security dialogs: the section 4 key changes, the backup and restore password dialogs, the restore confirmation, Start fresh and Erase.
- On API 28–30 overlay tapjacking is accepted; `filterTouchesWhenObscured` is not used because it blocks input under screen-dimmer apps.

## 10. Logging and leak hygiene (DEC-70)

- Never log, and never put into an exception message, in any build type: note content, person/project fields, search text, passwords, key material, key-state fields, backup file names, URIs. Allowed: IDs, counts, durations, enum and state names, exception class names, SQLite result codes.
- All app logging goes through `AppLog` (`core.log`): the `src/debug` implementation forwards to `android.util.Log`, the `src/release` one is empty. No other class references `android.util.Log`. `println`, `printStackTrace`, `System.out`, `System.err` and Timber are banned.
- SQL is always parameterized ([data-layer.md](data-layer.md)), so SQLite messages never contain user text. Room `setQueryCallback` exists only in `src/debug` (`DebugRoomHooks`, [db-lifecycle.md](db-lifecycle.md#room-builder)) and logs the SQL text and the bind-argument count, never the values.
- Exception messages are constant text plus IDs; wrap with `cause` only when the cause's message cannot contain processed input. The default uncaught-exception handler prints the whole cause chain to logcat in release too, so this rule is the only protection there. No custom crash handler, crash file or crash SDK (DEC-68).
- Never log, rethrow or pass as `cause` an exception whose message can contain processed input: `org.json.JSONException` (its tokenizer errors append the entire input), `NumberFormatException`, `java.time.DateTimeException`, and `IllegalArgumentException` from Base64, `Normalizer2` or `CharsetEncoder`. Catch it at the parsing boundary and throw a new exception whose message holds only constant text, ids and `e.javaClass.simpleName`, with no cause. Known sites: `NoteRepository`'s JSON mapping of `persons_json`/`projects_json` ([data-layer.md](data-layer.md)) and `KeyVault`'s key-state parser (section 3.5).
- Release configuration:
```
# app/proguard-rules.pro (release, on top of proguard-android-optimize.txt, DEC-13)
-maximumremovedandroidloglevel 7
```
```kotlin
// package com.doro.organizer.data, two copies; called by SqlcipherEngine.init() in effect E1 (section 3.4)
// src/release/java/com/doro/organizer/data/SqlcipherLogging.kt
internal object SqlcipherLogging {
    fun configure() { net.zetetic.database.Logger.setTarget(net.zetetic.database.NoopTarget()) }
}
// src/debug/java/com/doro/organizer/data/SqlcipherLogging.kt
internal object SqlcipherLogging { fun configure() = Unit }      // keeps the default logcat target
```
- Native SQLite and SQLCipher log lines (result codes, the app's own file paths) bypass R8 and the Java logger; they carry no user data.
- Verify at setup: R8 accepts `-maximumremovedandroidloglevel`; the `Logger`/`NoopTarget` package names; whether SQLCipher's native cipher log reaches logcat, and if so add `PRAGMA cipher_log_level = NONE` to the release `SqlcipherLogging.configure()` on its own throwaway connection (the one allowed exception of section 3.4).
- `toString()` redaction is required on every data class, and on every class with a property of type `String`, `CharSequence`, `CharArray`, `ByteArray`, `Uri` or `File` (or a collection of them), in any package under `com.doro.organizer`. It prints the class name, ids, enum values and booleans only; never text, lengths, bytes, URIs or file names. Known members: `NoteEntity`, `PersonEntity`, `ProjectEntity`, `NoteRow`, `NoteRowRaw`, `EntityRef`, `NoteDraft`, `NewEntity`, `NoteDetail`, `EntityOption`, `NoteFilter`, `NoteQuery`, the editor, settings and gate UI states, `GateState`, `GateEvent` (incl. `PasswordSubmitted`), `SaveResult`, `GateEffect`, `LockError`, `AppError`, `ExternalRequest`, `ExternalResult`, `MaintenanceResult.BackupShared`, `UserMessage`, `Kdf`, `KeySlot`, `KeyState`, `PreparedKey`, `UnlockResult`:
```kotlin
class KeySlot(val mode: KeyMode, val outerIv: ByteArray?, val wrappedDek: ByteArray? /* , … */) {
    override fun toString() = "KeySlot(mode=$mode)"
}
// data-layer.md's entities follow the same rule, e.g. NoteEntity(id=…) and NewEntity(key=…)
```
- Enforcement (owned by [testing.md](testing.md)): I-39 `RedactionTest` constructs each class above with `SENTINEL` ([testing.md](testing.md) § 7 Fixtures and fakes) in every text field and asserts that `toString()` lacks it; I-40 `LogPrivacyTest` (including a malformed `persons_json` containing `SENTINEL`, which must leave no sentinel in logcat); I-41 `ArchitectureTest` enforces the banned tokens and the `toString` rule of [architecture.md § Boundary test](architecture.md#boundary-test), the single list of rules (this section does not repeat the patterns); the `release-logs` CI job; manual QA M-17 (no line containing user data; framework lines under the app's pid are expected).

## 11. Keyboard and autofill (DEC-71, Q11)

Per DEC-71 the search fields are covered too, and autofill is excluded for every window of the app, password fields included, so password managers cannot fill or save the database or backup password (accepted for v1).
- Password fields: section 6.2.
- Fields covered below: note content, person name, project name, and every search field (perspective search and `EntityPicker` search, which carry the same words; person and project names are typed in the picker's search field).
- Autofill: always excluded. Compose has no per-field exclusion (`ContentType` has no "not important" value, and fields without one are still offered to service heuristics), so `MainActivity` sets `window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` after `setContent`. Compose `Dialog`, `AlertDialog` and `ModalBottomSheet` create their own windows with their own decor view, which that flag does not reach, so every one of them wraps its content in `SecureDialogWindow` (`ui/common`):
```kotlin
@Composable
fun SecureDialogWindow(hideOverlays: Boolean = false, content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view, hideOverlays) {
        view.rootView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (hideOverlays && Build.VERSION.SDK_INT >= 31)                    // section 9.4: security dialogs
            (view.parent as? DialogWindowProvider)?.window?.setHideOverlayWindows(true)
        onDispose { }
    }
    content()
}
```
  Verify at setup: `view.parent` is the `DialogWindowProvider` for `AlertDialog`, `Dialog` and `ModalBottomSheet` in the pinned Compose BOM; with a test autofill service enabled, typing in the editor, an `EntityPicker` search field, the change-password dialog and the backup-password dialog produces no fill request containing app text and no save request.
- Keyboard learning: the covered fields always sit inside `NoPersonalizedLearning(enabled = LocalKeyboardPrivacy.current)` (`ui/common`, Compose UI 1.7+). `LocalKeyboardPrivacy` is a `CompositionLocal<Boolean>` that `MainActivity` provides with `windowPolicy.secure` ([architecture.md](architecture.md) § Dependency injection), which equals `security.screenshot_protection` once the policy is open (DEC-71), so composables never read `SettingsStore`. A changed value replaces the interceptor, which restarts the input session; wrapping conditionally would move the field in the composition and lose its state:
```kotlin
@Composable
fun NoPersonalizedLearning(enabled: Boolean, content: @Composable () -> Unit) {
    val interceptor = remember(enabled) {
        PlatformTextInputInterceptor { request, next ->
            if (!enabled) next.startInputMethod(request)
            next.startInputMethod(object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                    request.createInputConnection(outAttributes).also {
                        outAttributes.imeOptions =
                            outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
            })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
```
  Verify at setup: whether `InterceptPlatformTextInput` and `PlatformTextInputInterceptor` still need `@OptIn(ExperimentalComposeUiApi::class)`, and the members of `PlatformTextInputMethodRequest`, in the pinned Compose BOM.
- The flag is a request that keyboards may ignore; it does not limit what a malicious keyboard sees (section 1.3).

## 12. Residual risks and documented limitations

| Risk | Why it remains | What the app does or says |
|---|---|---|
| Unencrypted backups | `PLAIN` mode with "Encrypt backups" off exports plaintext SQLite; share copies stay in `cacheDir/share/` until the next backup, the next cold start or the in-app one-hour sweep, and while the process is dead until the next launch (DEC-83) | Warning before every plaintext export (DEC-83, [ui.md](ui.md#85-unencrypted-backup-warning-dec-83)) |
| Flash remnants after turning encryption on | The old plaintext `doro-<n>.db` is deleted, but flash blocks can survive until reused; so can plaintext staging copies (a restore of a plain backup into an encrypted database, an export to `PLAIN`); earlier backups are unchanged | Relies on file-based encryption (§11 Device Assumptions); the confirmation says earlier backups are unchanged (section 4) |
| Keystore loss → KeyUnavailable | OS or OEM defects can lose the KEK; the wrapped DEK is then useless, by design without recovery | KeyUnavailable screen (DEC-86); backups are the only recovery |
| Newer key-state format after an app downgrade | A key-state with a larger `formatVersion` reads as invalid, so an older app shows KeyUnavailable, whose "Start fresh" would delete data the newer app could open | Install the newer version before choosing Start fresh |
| Recents thumbnail on API 28–32 | No public API hides only the thumbnail; with the lock or encryption on and screenshot protection off, Recents shows the last screen, and in `PASSWORD` mode after process death while the password is asked; the system keeps that snapshot outside the app's encryption | The lock's help text recommends screenshot protection (`biometric_recents_hint`, [ui.md](ui.md#94-biometric-lock)), and so does the encryption row when encryption is on and the lock is off (`encryption_recents_hint`, [ui.md](ui.md#93-database-encryption-and-startup-password)); API 33+ is covered (DEC-66) |
| Device without a screen lock | No biometric can be enrolled, storage keys are not bound to a user secret, and `PLAIN`/`DEVICE` open for anyone holding the phone | DEC-72 hint; `PASSWORD` mode still requires the password |
| Offline brute force of exported encrypted backups | No backoff off the device; each guess costs one SQLCipher 4 PBKDF2-HMAC-SHA512 at 256,000 iterations | Minimum 8 characters (DEC-64); the export dialog recommends a long passphrase (`backup_password_body`) |
| Same password for database and backups | An offline attack on a backup (A2) then yields the database password | Backup passwords are independent by design (DEC-83); the export dialog does not forbid reuse |
| Guessing on a held phone | Attempts are unlimited (DEC-62) | Backoff up to 5 min plus the PBKDF2 cost per guess; the delay uses elapsed realtime, so a changed system clock does not skip it, and a reboot re-applies it |
| The biometric lock is UI-only | In `PLAIN`/`DEVICE` mode the data opens without it (§11 App Lock, v1) | `PASSWORD` mode gives cryptographic protection |
| `PASSWORD` mode without the biometric lock | The password is asked only at cold start (DEC-62); the open session stays readable on every return from background | Accepted; the lock is the remedy |
| Re-lock exemption window | Up to 10 min without a lock after an app-launched SAF, share, enrollment or credential trip (DEC-65) | One exemption at a time, cleared on result, on the next resume and on a failed launch |
| `SettingsStore` reset by corruption | A corrupt preferences file is replaced with defaults through `ReplaceFileCorruptionHandler` ([architecture.md](architecture.md) § SettingsStore), which turns the biometric lock and screenshot protection off and resets the backoff; pinned by [testing.md](testing.md) I-44 | Accepted; a fail-closed handler needs a new decision first |
| Keyboards, accessibility services, clipboard | Software the user enabled can read typed or displayed text; copied text leaves the app; a screen read started by an assistant is out of scope | DEC-71 opt-out (best effort); system content capture is disabled by the app (section 8) |
| Task hijacking and overlays on old APIs, secrets in memory | See sections 9.4 and 6.5 | Accepted |
