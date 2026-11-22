# Security

Purpose: the threat model and the binding rules for keys, passwords, the app lock, screen and platform hardening, and log hygiene.
Related: [decisions.md](decisions.md) · [architecture.md](architecture.md) · [db-lifecycle.md](db-lifecycle.md) · [data-layer.md](data-layer.md) · [ui.md](ui.md) · [testing.md](testing.md) · [spec](../stakeholder-notepad-spec.md)

Ownership: this file owns the threat model and residual risks; the key model (including the biometric slot: key parameters, `CryptoObject` use, all slot crypto through `SlotCipher`, invalidation handling and re-creation); the key-state file fields and format version; the `KeyVault` API and its key-state types; `UnlockPolicy` with `UnlockRoute`, `PasswordReason` and `PasswordCause` (the unlock-method rules per mode); the `AppLockController`, `BiometricGateway` and `BiometricSlotWriter` contracts with `AuthPurpose`, `ToggleOutcome`, `SlotState` and `SlotOffer`; the password and unlock flows; the 14-day password reminder and its timestamp; the fingerprint password reset policy; window protection; the manifest and backup-rule XML; offline enforcement; the logging policy; secrets in memory; keyboard and autofill rules. File layout, the `DbMaintenance` API, key-state commits (including the slot-only commits `createBiometricSlot` and `removeBiometricSlot`), restore, erase, start-fresh and key-change mechanics, failure classification and the crash-safety rows CS-nn are in [db-lifecycle.md](db-lifecycle.md); gate states, events, effects, transitions (T-n) and `lockScreenOf` are in [architecture.md](architecture.md) § SessionGate; screens, dialogs and strings are in [ui.md](ui.md). Other docs use the names defined here; where one differs, this file wins (decisions.md wins over all).

## 1. Threat model

### 1.1 Assets
| Asset | Where it lives |
|---|---|
| Notes, person/project names, dates, links | Live database `databases/doro-<generation>.db` and its `-wal` (DEC-80, DEC-89); staging files in `noBackupFilesDir` |
| Exported backups | User-chosen SAF targets; share copies in `cacheDir/share/` until the next backup, the next cold start or the one-hour sweep while the app runs ([db-lifecycle.md](db-lifecycle.md#share-file-lifetime), DEC-83) |
| DEK and its wrapped forms | Process memory as the 67-byte raw key while the database is open (section 6.4); `noBackupFilesDir/db-state.json` (section 3.5): wrapped by the KEK and, in `PASSWORD` mode with fingerprint unlock, also by the biometric key (`bioSlot`, section 3.7) |
| Keystore KEK | AndroidKeyStore (TEE or StrongBox); never in the app process |
| Biometric keys `doro_bio_v1_<32 hex>`, one per slot (DEC-98) | AndroidKeyStore (TEE-backed, never StrongBox, section 3.7.1); usable only for one operation after a strong biometric authentication; never in the app process (section 3.7) |
| Password-reminder timestamp | `SettingsStore` `security.password_verified_at` (section 5.6); not secret |
| Database and backup passwords | Process memory for one operation (section 6) |
| Screen content | The window, the Recents thumbnail, screenshots, screen recordings, the platform's content capture |
| UI state | `SavedStateHandle` holds drafts, filters and search text (DEC-28, DEC-37); `SettingsStore` holds no user text |

### 1.2 Adversaries in scope
| Adversary | Can | Controls |
|---|---|---|
| A1 Person holding the unlocked phone | Open the app, Recents and Settings; take screenshots; change the system clock | Biometric lock (DEC-65); in `PASSWORD` mode the fingerprint opens the database only through the enrollment-bound biometric slot, so a finger added later does not work (section 3.7); startup password (DEC-62, backoff on a time base the clock cannot move, section 5.3), `FLAG_SECURE` (DEC-67), Recents protection and data-free gate screens (DEC-66) |
| A2 Holder of a copied backup file | Guess passwords offline without limit | Backup password and SQLCipher 4 KDF (DEC-83), plaintext-export warning, password policy (DEC-64) |
| A3 Someone extracting app files from a lost device | Read `databases/`, `files/`, `cache/` and `noBackupFilesDir` without running the app, including an undeleted share copy (plaintext in `PLAIN` mode, offline-attackable in the other modes) | `DEVICE`/`PASSWORD`: the DEK unwraps only in this device's Keystore; `PASSWORD` also needs the password, or a strong biometric authentication on this device for the biometric slot (section 3.7); file-based encryption (§11 Device Assumptions); no cloud or D2D copies (DEC-69); share copies deleted on the triggers of DEC-83 and after a committed key change (section 4 step 5) |
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
| Startup password | off | Key-state file (`slot.mode = PASSWORD`, `slot.kdf`); the password itself is never stored | Asked before the database opens at every cold start when fingerprint unlock is not available; with the biometric lock on and a usable strong biometric the fingerprint replaces it, except when the 14-day reminder is due (sections 5.1, 5.6) | DEC-62, DEC-64, DEC-74 |
| Biometric lock | off | `SettingsStore` `security.app_lock`; in `PASSWORD` mode also the biometric slot (`bioSlot` in the key-state file and the slot's own Keystore key, named by `bioSlot.keyAlias`, section 3.7) | Lock at cold start and on return from background (section 7). `PLAIN`/`DEVICE`: a strong-biometric prompt without a `CryptoObject`, with the device-credential fallback (Q7). `PASSWORD`: the fingerprint unlocks the database key through the biometric slot; without a usable strong biometric the startup password alone, also on return from background (sections 5.1, 5.5) | DEC-60, DEC-65, DEC-66 |
| Screenshot protection | off | `SettingsStore` `security.screenshot_protection` | `FLAG_SECURE` on the window (section 8). It does not affect the keyboard: section 11 always opts out of keyboard learning, whatever this value (DEC-71) | DEC-67 |
| Password reminder (not a setting) | always active | `SettingsStore` `security.password_verified_at`, written by `KeyVault` only | `PASSWORD` mode with fingerprint unlock: once the startup password has not been entered successfully for 14 days, the next unlock asks for it (section 5.6) | DEC-74 |
| Encrypt backups | off | `SettingsStore` `security.encrypt_backups` | `PLAIN` mode: exports ask for a backup password. `DEVICE`/`PASSWORD` exports always do, whatever this value | DEC-83 |

- Mode mapping: encryption off = `PLAIN`; on without a password = `DEVICE`; on with a password = `PASSWORD`. A startup password without encryption does not exist.
- The seven security members of `SettingsStore` ([architecture.md](architecture.md) § Dependency injection) and their DataStore keys:

| `Settings` property | DataStore key | Type | Default | Written by |
|---|---|---|---|---|
| `screenshotProtection` | `security.screenshot_protection` | Boolean | false | Settings screen |
| `biometricLock` | `security.app_lock` | Boolean | false | `AppLockController`: after a successful authentication (section 7.1; in `PASSWORD` mode before the slot is armed or removed, DEC-65), set back to false after a failed arming, or by the auto-off (section 7.6) |
| `encryptBackups` | `security.encrypt_backups` | Boolean | false | Settings screen |
| `unlockFailures` | `security.unlock_failures` | Int | 0 | `KeyVault` only (section 5.3) |
| `unlockRetryAtElapsedMs` | `security.unlock_retry_at` | Long, `AppTime.elapsedMillis()` base | 0 | `KeyVault` only (section 5.3) |
| `lockAutoOffNotice` | `security.lock_auto_off_notice` | Boolean | false | `AppLockController` (set), the shell notice (clear) |
| `passwordVerifiedAt` | `security.password_verified_at` | Long, `AppTime.nowMillis()` base (epoch ms, UTC) | 0 (reminder due) | `KeyVault` only (section 5.6) |

- `SettingsStore` never holds user text, secrets or URIs. Backups never carry these settings and restore never changes them (DEC-84). Erase resets all seven (section 5.4).
- A corrupt preferences file is replaced with defaults ([architecture.md](architecture.md) § Dependency injection), which turns the lock and screenshot protection off, resets the backoff and loses the reminder timestamp (the reminder is then due, which only matters once the lock is on again); in `PASSWORD` mode the next cold start asks the password (the lock now reads as off), and the biometric slot left behind is removed by `reconcileLock()` in effect E5 right after that password unlock (section 3.7.5, DEC-09); accepted (section 12).

## 3. Key model (DEC-60)

Decided by the owner (Q1, 2026-11-19): random DEK protected by the Keystore, optional startup password, three modes `PLAIN`, `DEVICE`, `PASSWORD`. The password-only alternative is rejected.

### 3.1 Keys and parameters
| Item | Specification |
|---|---|
| DEK | 32 bytes from `SecureRandom()` (default instance, never seeded). A new DEK for every key change (DEC-61); none in `PLAIN` |
| Keystore KEK | AndroidKeyStore AES-256 (`setKeySize(256)`; the default is 128), GCM, no padding, alias `doro_kek_v1`, non-exportable, no user-authentication flags, no `setUnlockedDeviceRequired`, StrongBox when available |
| Password KEK | PBKDF2-HMAC-SHA256 over UTF-8(NFC(password)), 16-byte random salt, ≥ 600,000 iterations (section 3.3), 32-byte output. Never stored |
| Biometric key | AndroidKeyStore AES-256 GCM, no padding, a fresh alias for every arming (`doro_bio_v1_` + 32 lowercase hex digits, DEC-98; the slot names its own key in `bioSlot.keyAlias`), non-exportable, user authentication required for every use with `BIOMETRIC_STRONG` only, invalidated by biometric enrollment changes, TEE-backed and never StrongBox (section 3.7.1). Normally exists only while a biometric slot exists (`PASSWORD` mode with the lock on); an unused key left by a cancelled arming or a crash wraps no committed DEK and is deleted by the next arming, startup reconciliation, removal, new-DEK commit or Erase (section 3.7.5) |
| Wrap | AES/GCM/NoPadding, 12-byte IV, 128-bit tag. AAD (ASCII), outer layer: `DEVICE` = `doro.kek.v1:DEVICE`; `PASSWORD` = `doro.kek.v1:PASSWORD:PBKDF2-HMAC-SHA256:<iterations>:<salt base64>:<innerIv base64>`. Inner layer: `doro.pw.v1`. Biometric slot: `doro.bio.v1:<slot.outerIv base64>` (section 3.7.2). The base64 parts are re-encoded from the decoded bytes with the encoder of section 3.5, never taken from the file text |
| `DEVICE` slot | `wrappedDek` = GCM(KEK, DEK): 48 bytes |
| `PASSWORD` slot | inner = GCM(password KEK, DEK): 48 bytes; `wrappedDek` = GCM(KEK, inner): 64 bytes |
| Biometric slot (`bioSlot`) | `PASSWORD` mode with the biometric lock on: `wrappedDek` = GCM(biometric key, DEK): 48 bytes, with a Keystore-chosen 12-byte IV. A second, independent wrap of the same DEK; the password slot stays unchanged and works unless its KEK fails (section 3.7.3, `kekBroken`) |
| Live database | SQLCipher 4 keyed with the raw key `x'<64 hex>'` (no per-connection KDF): AES-256-CBC, HMAC-SHA512 per page, 4096-byte pages. `PLAIN`: key `ByteArray(0)` on the same engine (DEC-08) |
| Backup file | SQLCipher 4 passphrase mode: PBKDF2-HMAC-SHA512, 256,000 iterations, per-file salt; passphrase = UTF-8(NFC(backup password)) (DEC-83) |

- No GCM tag covers the inner IV, the salt or the iteration count, so the outer AAD of a `PASSWORD` slot binds them. A damaged value then fails the outer tag and is KeyUnavailable, never a wrong password that counts toward the backoff (section 5.2).
- A copied key-state file cannot be attacked offline, because the inner layer is reachable only through this device's Keystore. The Keystore unwrap itself does not depend on the password: guesses made through the app pay the PBKDF2 cost and the backoff (section 5.3), while code running as the app could attack the inner layer offline after one unwrap (out of scope, section 1.3).
- Keystore layer: `Cipher.getInstance("AES/GCM/NoPadding")`, `init(ENCRYPT_MODE, kek)` with no parameters (the Keystore picks the IV and rejects caller IVs), store `cipher.iv`; decrypt with `GCMParameterSpec(128, iv)`. Password layer: `SecretKeySpec(passwordKek, "AES")` with a fresh 12-byte `SecureRandom` IV. Both layers call `updateAAD` before `doFinal`.
- The biometric slot bypasses the password layer by design (Q8, DEC-60): a strong biometric authentication on this device releases the DEK without the password. It does not bypass the Keystore: the biometric key never leaves the Keystore and works only after the system has verified an enrolled Class 3 biometric for that single operation. Its lifecycle is in section 3.7.5.
- KEK lifecycle: generated only when the alias is missing and the target mode is `DEVICE` or `PASSWORD`. Never deleted or regenerated while the committed slot uses it, so a failure before a commit leaves the old slot readable. One exception (DEC-78): a `prepare(PASSWORD, …)`, in practice the fingerprint password reset's (section 4.3), in a process where a biometric unlock has reported `kekBroken` for the committed generation (`brokenKekGeneration`, section 3.7.3) checks the committed slot's outer layer again and, only if it still fails, deletes and regenerates the KEK once, because the password can no longer open that slot and the commit replaces it; a KEK that works again is reused. No other operation replaces a KEK that fails its self-check: it throws `KeyVaultException(KeyUnavailable)`. Deleted after a committed switch to `PLAIN` (a key change to `PLAIN`, Start fresh, and a Restore from KeyUnavailable, section 4.1) and by Erase (section 5.4); a failed delete is ignored. While the committed mode is `PLAIN`, an unusable alias is deleted and regenerated once.
- Verified at setup (SPEC T22, 2026-11-22, Poco F7): a KEK that a failed delete left is reused. A key change from `DEVICE` to `PLAIN` whose `DELETE_KEK` step throws (fault hook) still returns `Ok` and commits `PLAIN` at the next generation, and the KEK stays; the next start keeps it (effects E1 and E2 run again in the test process, [db-lifecycle.md](db-lifecycle.md#crash-safety-matrix) CS-13). The next change to `PASSWORD` then returns `Ok` without generating a new KEK: the alias still unwraps the old `DEVICE` slot, whose GCM tag passes only with the key that wrapped it, and keeps its Keystore creation date. After a restart the password unlocks the committed slot and the content is unchanged (`MaintenanceFailureTest#aKeyChangeToPassword_overTheKekAFailedDeleteKekLeft_reusesIt_andThePasswordUnlocks`, build/t22-kek-1/-2). That KEK is TEE-backed, because the test's `FaultyKekKeystore` seam never asks for StrongBox; the reuse path does not depend on the security level, and section 3.2 records the StrongBox KEK. The same failure followed by a change to `DEVICE` passes in the after-commit sweep of `MaintenanceFailureTest#aKeyChange_failingUpToItsCommit_rollsBack_andAfterItTheCommitStands` (build/t22-full-2).
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
- `prepare` validates what it produced before returning, because a StrongBox key can generate and still misbehave on use, and the failure would otherwise appear only at the next cold start, after the commit: the outer IV is exactly 12 bytes (`check(cipher.iv.size == 12)`), `wrappedDek` has the length of section 3.1, and unwrapping it once with the same KEK reproduces the input (`MessageDigest.isEqual`). If a check fails or the cipher throws and the KEK is a StrongBox key created in this `prepare`, delete the alias, regenerate it with `setIsStrongBoxBacked(false)` and redo the wrap once. Any other failure throws `KeyVaultException(KeyUnavailable)` before any export starts. Every Keystore step of `prepare` (alias check, generation, delete, the wrap with its self-check) is first retried once after 1 s (section 5.2, DEC-76); only a failure that persists reaches the replacement rule above (or the `PLAIN` rule of section 3.1) or `KeyUnavailable`. A self-check mismatch is not an exception and is never retried. The replacement of an existing KEK while the committed mode is `PLAIN` goes through the same StrongBox-first generation, so a new StrongBox key that then fails is replaced once more without StrongBox; a replacement made without StrongBox that fails again is `KeyUnavailable`.
- Code shape: the sketch is split between the `KekKeystore` seam (section 3.6) and `DefaultKeyVault`. `AndroidKekKeystore.generate(strongBox)` builds the spec above and generates one key; `AndroidKekKeystore.forDevice(context)` reads `FEATURE_STRONGBOX_KEYSTORE` once into `hasStrongBox`. The StrongBox-first fallback with its quiet deletes lives in `DefaultKeyVault`, so the tests drive it through the seam.
- Verified at setup (SPEC T22, 2026-11-22, Poco F7): the phone reports `FEATURE_STRONGBOX_KEYSTORE`, and the KEK is generated in StrongBox (`securityLevel` STRONGBOX; the StrongBox KeyMint logs the wrap and the self-check unwrap). The self-check's `cipher.iv.size == 12` for AndroidKeyStore GCM holds there, because a failing StrongBox KEK would have been replaced by a TEE one and the test would fail. The StrongBox branch is therefore covered on the device (`KeyMaterialTest#kek_isAes256Gcm_withoutUserAuthentication_inTheBestHardware`, build/t22-full-2). API 28–29 Keystore behavior is not run on a device (DEC-15, accepted risk); an optional API 28 emulator run may add it.

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
- Known-answer test (required): `Pbkdf2KatTest` compares the first 32 bytes of the RFC 7914 §11 PBKDF2-HMAC-SHA256 vectors (P="passwd", S="salt", c=1; P="Password", S="NaCl", c=80000), and equals `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` on the second vector and for an ASCII password at 600,000 iterations ([testing.md](testing.md) L-27). It lives in `src/sharedTest`, so the same class runs on the JVM and on the device and covers both `Mac` providers.
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
fun sqlcipherRawKey(dek: ByteArray, newSecret: (Int) -> ByteArray): ByteArray {   // ASCII x'<64 hex>' = 67 bytes, no String; allocated through KeyVault's seam (section 3.6)
    require(dek.size == 32)
    val hex = "0123456789abcdef"
    val out = newSecret(67)
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
        val db = SQLiteDatabase.openOrCreateDatabase(":memory:", ByteArray(0), null, null)   // throwaway; verified (T22): this overload runs on the device (SqlcipherEngineTest)
        try { db.query("PRAGMA cipher_default_compatibility = 4").use { it.moveToFirst() } } finally { db.close() }
        done = true
    }
}
```
  - Every connection opened with a non-empty key also runs `PRAGMA cipher_compatibility = 4` (connection scope) in `CipherCompatibilityHook.postKey` ([db-lifecycle.md](db-lifecycle.md#open-sequence)).
- Pragma allowlist. Writes: `cipher_default_compatibility = 4` (above), `cipher_compatibility = 4` (the hook) and, release only and only if the phone check of section 10 (testing.md M-27) shows native cipher logs in logcat, `cipher_log_level = NONE`. Reads (verification and tests): `cipher_version`, `cipher_integrity_check`, `cipher_page_size`, `cipher_hmac_algorithm`, `cipher_kdf_algorithm`, `kdf_iter`. Every other `cipher_*` or `kdf_iter` pragma is banned.
- Never `PRAGMA rekey` (key changes re-export, DEC-61), never `cipher_plaintext_header_size`, never `temp_store = FILE` (the library is built with `SQLITE_TEMP_STORE=2`, so temporary data stays in RAM). `cipher_memory_security` keeps its default (off).
- Verified at setup (SPEC T22, 2026-11-22, Poco F7): both pragmas run without error, through `SqlcipherEngine.init`, the `postKey` hook and the backup export. A Device-mode database and a password-protected exported backup report `cipher_page_size` 4096, `cipher_hmac_algorithm` HMAC_SHA512 and `cipher_kdf_algorithm` PBKDF2_HMAC_SHA512, and the backup reports `kdf_iter` 256000, so `exportTo` needs no `target.cipher_compatibility` (`CipherSettingsTest`, `SqlcipherEngineTest`, build/t22-full-2; [testing.md](testing.md) L-28). If an exported target does not report the pinned values, [db-lifecycle.md](db-lifecycle.md#maintenance-connection) `exportTo` also runs `PRAGMA target.cipher_compatibility = 4` right after `ATTACH`. Repeat this test before any SQLCipher 5.x upgrade.

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
  },
  "bioSlot": {
    "keyAlias": "doro_bio_v1_<32 lowercase hex digits>",
    "iv": "<base64>",
    "wrappedDek": "<base64>"
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
| `bioSlot` | JSON object, top level next to `slot` | absent | absent | optional: present only while the biometric slot is armed (section 3.7); a malformed member is ignored and flagged, never fatal (below) |
| `bioSlot.keyAlias` | string | — | — | the fresh-alias form of DEC-98, `doro_bio_v1_` + 32 lowercase hex digits (`isSlotKeyAlias`; required inside `bioSlot`) |
| `bioSlot.iv` | base64, 12 bytes | — | — | required inside `bioSlot` |
| `bioSlot.wrappedDek` | base64, 48 bytes | — | — | required inside `bioSlot` |

- Path `noBackupFilesDir/db-state.json` (DEC-80); UTF-8 JSON; base64 = `java.util.Base64` basic alphabet with padding (encoder and decoder).
- Format version: the biometric slot does not change `formatVersion`, which stays 1. `bioSlot` is an optional, additive member: a reader that does not know it ignores it (unknown field names are ignored, below) and loses only fingerprint unlock, never data or the password slot. `formatVersion` increases only for a change that an older reader would misread if it ignored the new part. Reading: a file without `bioSlot` is read exactly as before and means "no biometric slot"; a file with `bioSlot` is read by the rules below. Every writer writes `formatVersion` 1.
- Parser: `android.util.JsonReader` with an explicit `peek()` check before every read, so a quoted number or a fractional number is never coerced. Do not use `org.json`: its `JSONException` messages contain the input text, which here includes the wrapped DEK, IVs and salt (section 10).
- The file is invalid if any of these holds: `formatVersion` is not the JSON integer 1 (a larger value means a newer app wrote it, section 12); `generation` is not a JSON integer ≥ 1; `mode` is not one of the three strings; `kekAlias` differs from `doro_kek_v1`; `kdf.algorithm` differs; `kdf.iterations` is outside the range of the table; a required field is missing or has the wrong JSON type; a field marked absent for the mode is present; a base64 value of the main `slot` does not decode or has the wrong length. The main-slot rules alone decide whether the file is invalid. Field names not in the table are ignored. The parser reads strictly, so that every accepted file has exactly one meaning: a JSON integer is read from its literal, which must match `-?(0|[1-9][0-9]*)` and fit a `Long` (`1.0`, `1e0` and an overflowing value are not integers); a base64 value must be canonical, that is padded and equal to the re-encoding of its decoded bytes (`java.util.Base64`'s decoder alone also accepts unpadded text); a repeated `formatVersion`, `generation` or `slot`, or a repeated known field inside `slot` or `kdf`, is invalid; content after the top-level object and malformed UTF-8 are invalid.
- `bioSlot` is validated separately and never makes the file invalid. If it is present but malformed (not a JSON object, a member missing, repeated or of the wrong JSON type, a `keyAlias` not of the fresh-alias form, the legacy constant `doro_bio_v1` included, a base64 value that does not decode or has the wrong length, a `bioSlot` member that appears twice at the top level, or present while `mode` is not `PASSWORD`), the parser reports the file as valid with `bioSlot = null` and `deadBioSlot = true`. The slot is then treated as dead: the password route with the cause `SlotMissing` (public reason `None`), every biometric key the file does not name is deleted best effort (a malformed slot names none, so all of them), and the next commit drops it. Reasons: an invalid file shows only the KeyUnavailable screen (DEC-86), which has no password entry, so a damaged optional member would lock the user out of data that the intact, GCM-authenticated password slot still opens; and trusting the main slot alone is sound, because its outer AAD binds every KDF parameter (section 3.1) and the biometric slot has its own GCM tag and an AAD bound to `slot.outerIv`, so ignoring a malformed `bioSlot` can never release a wrong key. For the read-back of [db-lifecycle.md](db-lifecycle.md#generation-commit) (`KeyState.matches`) a dead slot reads back as `null`. A flipped base64 character in `bioSlot` gives the password screen, never KeyUnavailable ([testing.md](testing.md) L-05 / L-33).
- Any parser exception is caught at the parsing boundary and replaced by `KeyVaultException(InvalidState)` with constant text and no cause.
- Write only with `androidx.core.util.AtomicFile`: `startWrite()`, write, `finishWrite()` (it syncs before renaming, and only logs a failed sync: DEC-107 (4), open question Q16), `failWrite()` on any exception. Read only through `AtomicFile.readFully()`, which reads the base file and never recovers an interrupted write: a stale `db-state.json.new` is ignored and discarded when the base file exists. When only `.new` exists (an interrupted first write) `readFully()` throws `FileNotFoundException`, which `readState()` reports as no file (`null`, Missing), not as an invalid file. Any other `IOException` while reading is an invalid file; so is a base file that exists but cannot be opened (a directory, no permission), even though `readFully()` reports it as `FileNotFoundException`: only a missing base file is Missing. Verify at setup: the `.new` behavior of the pinned `androidx.core.util.AtomicFile` ([db-lifecycle.md](db-lifecycle.md#generation-commit)). Verified at setup (SPEC T22, 2026-11-22, Poco F7): a stale `db-state.json.new` next to a valid base file is deleted by `readState()`, which reads the base (`AtomicFileSyncAndStaleNewTest#keyVaultReadState_deletesAStaleNewKeyStateFile_andReadsTheBase`, build/t22-probes-1/-2); the sync in `finishWrite()` is verified from the androidx.core 1.19.1 bytecode (it syncs the `.new` stream, only logging a failed sync, before the rename) and cannot be observed on the phone (DEC-107, [db-lifecycle.md](db-lifecycle.md#generation-commit)). The directory sync that makes the commit durable is part of `DbMaintenance.commitGeneration` ([db-lifecycle.md](db-lifecycle.md#generation-commit)); `eraseState()` runs the same best-effort `syncDirectory(noBackupFilesDir)` after its deletion. Startup cleanup and reconciliation never write `db-state.json*`; only `commit` and `eraseState` do. Neither the unlock path nor any slot failure rewrites the file (section 3.7.4).
- `AtomicFile` gives no locking: every `KeyVault` operation (read, commit, erase, and every password attempt, section 3.6) runs under one `Mutex`.
- The file exists whenever a database exists, `PLAIN` included (it carries `generation`). Only `KeyVault` writes it: through `commit` when `DbMaintenance.commitGeneration` calls it (first run, every committed swap: key change, Restore, Start fresh, and the adoption of a plaintext file at startup), through `eraseState` for Erase (section 5.4). A biometric slot commit (creation, removal) is the same `commit` called with the committed `generation` and `slot` and a different `bio`, driven by `DbMaintenance` under its operation mutex ([db-lifecycle.md](db-lifecycle.md#biometric-slot-commits)); `KeyVault` has no separate slot-only writer.
- `commit(generation, slot, bio)` writes exactly what it is given; it keeps or drops nothing by itself. Callers pass `bio` as [db-lifecycle.md](db-lifecycle.md#generation-commit) lists: `null` for every commit that installs a new DEK (first run, adoption, every key change, Start fresh, Restore from `KeyUnavailable`), the committed `bioSlot` for a Restore from `Ready` or `Corrupt` (same DEK; a backup makes no commit), the new or removed slot for a slot commit. Rules: `require(bio == null || slot.mode == KeyMode.PASSWORD)`; before `startWrite()` it parses its own serialized bytes back with the strict parser above and checks `generation`, `slot.sameAs` and the biometric slot (`BioSlot.sameAs`, `keyAlias` included, so an alias outside the fresh-alias form is refused too) against its inputs, so that a serialization bug throws `IllegalStateException` with constant text in memory (`DbMaintenance` classifies that as `NotCommitted`) and never reaches the only copy of the wrapped DEK. It deletes no biometric key: that is `DbMaintenance`'s `DELETE_BIO_KEY` step after a new-DEK commit, and its sweep after a slot creation's commit (DEC-98). Its backoff effect is in section 3.6. Generation semantics, commit order and the handling of a missing or invalid file next to existing database files (first run, adoption, KeyUnavailable): [db-lifecycle.md](db-lifecycle.md#startup-reconciliation) and [db-lifecycle.md](db-lifecycle.md#failure-classification). An invalid file is never rewritten or deleted automatically (DEC-86).
- Never in SharedPreferences or DataStore (§11 Key Management, DEC-09); never logged.

### 3.6 KeyVault surface
```kotlin
// package com.doro.organizer.model
enum class KeyMode { PLAIN, DEVICE, PASSWORD }

// package com.doro.organizer.security. Every toString() prints class name and mode only (Kdf, BioSlot: class name only;
// UnlockResult.Key: kekBroken only); no array content (DEC-70).
class Kdf(val iterations: Int, val salt: ByteArray)
class KeySlot(val mode: KeyMode, val outerIv: ByteArray?, val wrappedDek: ByteArray?, val innerIv: ByteArray?, val kdf: Kdf?) {
    fun sameAs(other: KeySlot): Boolean            // used by KeyState.matches (db-lifecycle.md § Generation commit). Equal mode; equal outerIv, wrappedDek, innerIv, kdf.iterations and kdf.salt (absent equals absent); kekAlias and kdf.algorithm are validated constants (section 3.5) and so implied; never ==
    override fun toString() = "KeySlot(mode=$mode)"
}
class BioSlot(val keyAlias: String, val iv: ByteArray, val wrappedDek: ByteArray) {   // section 3.7; keyAlias names the slot's own key, of the validated fresh-alias form (3.5, DEC-98)
    fun sameAs(other: BioSlot): Boolean            // equal keyAlias, byte-for-byte equal iv and wrappedDek; never ==
    override fun toString() = "BioSlot"
}
/** bioSlot only with slot.mode == PASSWORD. deadBioSlot = true when the file held a malformed bioSlot that the parser ignored (section 3.5);
 *  then bioSlot is null, so a read-back (db-lifecycle.md KeyState.matches) sees a dead slot as "no slot". */
class KeyState(val generation: Long, val slot: KeySlot, val bioSlot: BioSlot? = null, val deadBioSlot: Boolean = false)
class PreparedKey(val slot: KeySlot, val rawKey: ByteArray)      // rawKey = ByteArray(0) for PLAIN

/** A Cipher on the biometric key, initialized by KeyVault and authorized by exactly one BiometricPrompt (section 3.7). Single use.
 *  Only KeyVault creates one (the constructor is internal to the security package); AppLockController and the gateway pass the handle on
 *  unopened and never construct one (section 7.3). Only the security package touches the Cipher. */
enum class SlotUse { Unlock, Arm }
class SlotCipher internal constructor(
    val use: SlotUse,
    internal val cipher: javax.crypto.Cipher,
    internal val keyAlias: String,           // the key the cipher was initialized on: Unlock the committed bioSlot.keyAlias, Arm the fresh alias (DEC-98)
    internal val generation: Long,           // the committed state the cipher was prepared for
    internal val outerIv: ByteArray,         // copy of that state's slot.outerIv (the AAD binding, section 3.7.2)
    internal val bioIv: ByteArray?,          // Unlock: copy of the bioSlot.iv the cipher was initialized for, so a slot re-armed during the prompt is a race (DEC-79); Arm: null
) { override fun toString() = "SlotCipher(use=$use)" }

sealed interface SlotCipherResult {
    class Ready(val slotCipher: SlotCipher) : SlotCipherResult { override fun toString() = "Ready($slotCipher)" }
    data object NoSlot : SlotCipherResult        // mode != PASSWORD, or no bioSlot
    data object Invalidated : SlotCipherResult   // KeyPermanentlyInvalidatedException, a missing alias or a slot judged dead (3.7.4); KeyVault has deleted the slot's key, the dead bioSlot stays in the file
    data object Unavailable : SlotCipherResult   // any other Keystore failure after one retry; the slot is kept
}

/** Result of wrapForBiometric (3.7.2); nothing is persisted either way. */
sealed interface WrapResult {
    class Wrapped(val bioSlot: BioSlot) : WrapResult { override fun toString() = "Wrapped" }
    data object BiometricUnusable : WrapResult    // the cipher failed, its generation or outerIv no longer match the committed state, or the key-state file is missing or invalid (DEC-79); the cipher's key is deleted
}

/** Condition of the biometric slot in the committed state (3.7.5). */
enum class BioSlotStatus {
    None,    // no file, an invalid file, mode != PASSWORD, or no usable bioSlot member (including a malformed one, 3.5)
    Live,    // bioSlot present and its own key (bioSlot.keyAlias) present (DEC-98)
    Dead,    // bioSlot present but its key is missing (invalidated, judged dead or deleted): the password unlocks, the re-creation offer is due
    Unknown, // bioSlot present but the alias check failed twice (DEC-77): not live, so the password unlocks, but nothing is deleted, no re-creation is offered and no text calls the slot gone
}

sealed interface UnlockResult {
    class Key(val rawKey: ByteArray, val kekBroken: Boolean = false) : UnlockResult   // PLAIN: ByteArray(0). kekBroken: biometric path only (3.7.3)
    data class Verified(val kekBroken: Boolean = false) : UnlockResult                // verifyPassword / verifyBiometric: correct, nothing returned. kekBroken: verifyBiometric only
    data class PasswordRejected(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult
    data class Throttled(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult
    data class StorageFull(val failures: Int, val retryAtElapsedMs: Long) : UnlockResult   // the attempt could not be counted, so its password was not checked (DEC-75); the backoff in force, unchanged
    data object PasswordNeeded : UnlockResult                    // PASSWORD slot, password == null
    data class SlotFailed(val invalidated: Boolean) : UnlockResult   // biometric slot path only (section 3.7.4); never KeyUnavailable
    data object KeyFailed : UnlockResult                         // section 5.2 KeyUnavailable
}
enum class KeyFailure { KeyUnavailable, InvalidState }
class KeyVaultException(val failure: KeyFailure) : Exception(failure.name)   // constant message, no cause

interface KeyVault {
    suspend fun readState(): KeyState?                                       // null = no file; throws KeyVaultException(InvalidState) for an unreadable or invalid file
    suspend fun unlock(password: ByteArray?): UnlockResult                   // effect E2, password path
    suspend fun openKey(slot: KeySlot, password: ByteArray?): UnlockResult   // key-change current password, restore from Corrupt; db-lifecycle.md
    suspend fun verifyPassword(password: ByteArray): UnlockResult            // Verified | PasswordRejected | Throttled | StorageFull | KeyFailed (KeyFailed also for a non-PASSWORD slot); E4 and confirmWithPassword (sections 5.5, 7.1)
    suspend fun prepare(target: KeyMode, newPassword: ByteArray?): PreparedKey   // new DEK and slot; persists nothing but a missing (or, section 3.1, a broken) KEK
    suspend fun commit(generation: Long, slot: KeySlot, bio: BioSlot?)       // AtomicFile rewrite = the commit point; writes exactly its inputs; require(bio == null || slot.mode == PASSWORD); parses back before startWrite() (section 3.5)
    suspend fun eraseState()                                                 // AtomicFile.delete(), then deleteKek(), then every biometric key (the committed slot's, read before the delete, first)
    suspend fun deleteKek()                                                  // failures ignored

    // Biometric slot (section 3.7)
    suspend fun biometricSlotStatus(): BioSlotStatus                         // reads the committed state and asks the Keystore only whether bioSlot.keyAlias exists (containsAlias starts no operation); no authentication. A Keystore failure of that check is retried once after 1 s, then Unknown, nothing deleted (DEC-77). The single slot query of UnlockPolicy, fingerprintUnlockAvailable, availability and reconcileLock
    suspend fun biometricUnlockCipher(): SlotCipherResult                    // DECRYPT cipher on the committed bioSlot (3.7.3)
    suspend fun unlockWithBiometric(cipher: SlotCipher): UnlockResult        // Key | SlotFailed | KeyFailed (no file or an invalid one, DEC-79); cold start (E2), Restore from Corrupt and the fingerprint reset
    suspend fun verifyBiometric(cipher: SlotCipher): UnlockResult            // Verified | SlotFailed | KeyFailed; return from background, DisableLock
    suspend fun biometricArmCipher(): SlotCipherResult                       // new biometric key under a fresh alias, ENCRYPT cipher (3.7.2); never deletes the committed slot's key (DEC-98)
    suspend fun wrapForBiometric(cipher: SlotCipher, liveRawKey: ByteArray): WrapResult   // wraps the DEK of liveRawKey; persists nothing (3.7.2)
    suspend fun discardArmCipher(cipher: SlotCipher)                         // require(cipher.use == Arm); deletes the cipher's key unless the committed bioSlot names it, also over a missing or invalid file; best effort; never touches the file (DEC-98)
    suspend fun deleteBiometricKey()                                         // deletes every biometric key: the committed slot's first and directly, then every listed one (listing retried once after 1 s); best effort; never touches the file; idempotent
    suspend fun deleteUnreferencedBiometricKeys()                            // deletes every biometric key the committed bioSlot does not name: all over a missing file, none over an invalid one (DEC-86); best effort; never touches the file (DEC-98)
    suspend fun isPasswordReminderDue(): Boolean                             // section 5.6
}
```
- Removed names: `hasBiometricSlot`, `armBiometricSlot`, `removeBiometricSlot` and `openBiometricSlot` are not `KeyVault` members, and there is no `BiometricCipher` or `SlotArmer` type. Slot-only writes are `DbMaintenance.createBiometricSlot` and `DbMaintenance.removeBiometricSlot` ([db-lifecycle.md § Biometric slot commits](db-lifecycle.md#biometric-slot-commits)), which call `wrapForBiometric`, `commit`, `discardArmCipher`, `deleteUnreferencedBiometricKeys` and `deleteBiometricKey`. Whether a slot exists is asked only through `biometricSlotStatus()`.
- Locking discipline (every `KeyVault` operation runs under one `Mutex`, section 3.5). Kotlin's `Mutex` is not reentrant, so a public member that called another public member while holding the lock would suspend forever, and on the cold-start path the app would hang in `Opening` with no recovery screen. Therefore every public member takes the mutex exactly once (`mutex.withLock { … }`) and calls only private `*Locked` helpers (`readStateLocked`, `openKeyLocked`, `committedStateOrNullLocked`, `outerUnwrapLocked`, …), which never lock. No public member calls another public member; `verifyPassword` and `unlock` share `openKeyLocked`, not each other. `KeyVaultBiometricTest` (the biometric half of `KeyVaultTest`, same fixtures and the same 60 s test timeout) drives every slot failure path (3.7.4), so a re-entrant call fails as a timeout, never as a hang. In code the biometric half of `DefaultKeyVault` is the internal class `BioSlotCrypto`: it never locks, every member is a `*Locked` helper (`statusLocked`, `unlockCipherLocked`, `armCipherLocked`, `wrapLocked`, `unwrapLocked`, `deleteAllBioKeysLocked(state)`, `deleteUnreferencedBioKeysLocked(state)`, `discardArmKeyLocked(state, cipher)`; the per-alias `deleteBioKeyLocked(alias)` is private) that the vault calls under its mutex with the committed state it has just read (null for a missing or invalid file), and it owns the in-memory repeated-failure counter of 3.7.4; it shares the vault's internal `KEYSTORE_RETRY_DELAY_MS`, `isKeystoreFailure()` and `zeroAfter`, and takes the vault's `SecureRandom`, from which `newBioKeyAlias` draws every fresh alias (DEC-98). The vault itself keeps the KEK check and `brokenKekGeneration` (3.7.3). The body that holds the mutex runs in `withContext(NonCancellable)` for every public member, not only `prepare`: a cancelled caller never interrupts a derivation, a wrap or a settings write halfway, and never drops a secret array before it is zeroed (DEC-64); a cancelled caller of `unlock` still receives its `Key` and owns it (`KeyVaultTest`).
- `unlock(password)` is effect E2's entry for the password path. It reads the committed state (an invalid file returns `KeyFailed`, and so does a missing one: effect E2 runs only after startup reconciliation has committed a state, so a missing file there is never a normal case), then: `PLAIN` returns `Key(ByteArray(0))`; `DEVICE` runs `openKeyLocked(slot, null)`; `PASSWORD` with `password == null` returns `PasswordNeeded`; otherwise `openKeyLocked(slot, password)`. `null` means "no password supplied"; an empty array means "supplied but unusable" (section 6.3 returns null for malformed input and E2 passes `ByteArray(0)` then). The fingerprint path is `unlockWithBiometric`, never `unlock`.
- `openKey(slot, password)` order for a `PASSWORD` slot, all under the mutex so parallel attempts cannot bypass the throttle: (1) throttle check: while `AppTime.elapsedMillis() < retryAt` return `Throttled` without any crypto; (2) an empty password returns `PasswordRejected` with the stored values, not counted (such a password can never have been set); (3) Keystore layer (section 5.2 on failures; never counted); (4) the attempt is counted before its password is checked (DEC-75): `unlockFailures + 1` and that count's `retryAt` are persisted (the write runs in `NonCancellable`), and if that write fails the password is not checked and `StorageFull` is returned with the values in force; (5) PBKDF2 and the inner GCM: `AEADBadTagException` returns `PasswordRejected` with the counted values, its wait restarted from the end of the check (section 5.3); any other failure there is `KeyFailed` and writes the values from before the attempt back (best effort), because errors never count (DEC-76); success resets both, sets `passwordVerifiedAt` and returns `Key`. `DEVICE` and `PLAIN` slots involve no backoff.
- `verifyPassword(password)` runs the `openKeyLocked` steps on the committed `PASSWORD` slot (same throttle, same counting before the check, same reminder refresh), zeroes the raw key it built and returns `Verified()`. It is used where the database is already open and only the password has to be proven: the return-from-background password (effect E4, section 5.5) and `AppLockController.confirmWithPassword` (section 7.1). For a non-`PASSWORD` slot it returns `KeyFailed` (a caller bug made harmless). E4 maps `KeyFailed` to T50 like any failed check, unless `AppLockController.reportPasswordKekFailure()` finds a live biometric slot (section 7.3, DEC-86): then E4 raises `KekFailedWithSlot` and the gate falls back to the fingerprint route (T60); no separate result exists.
- Counters and timestamp. `KeyVault` alone reads and writes `unlockFailures`, `unlockRetryAtElapsedMs` and `passwordVerifiedAt` (through `SettingsStore`). Two rules govern a failing `SettingsStore` write (a full disk makes DataStore throw an `IOException`, which is not a `KeyVaultException`, so unhandled it would crash a correct password entry or, wrapped, send the user to KeyUnavailable):
  - The success-path write (backoff reset and `passwordVerifiedAt = AppTime.nowMillis()` in one `SettingsStore` edit, `SettingsStore.recordPasswordVerified(epochMillis)`, section 5.6) is best effort. A failure is logged by exception class only (section 10), the unlock proceeds as if it had succeeded, and the reminder simply stays due (a lost or stale timestamp counts as due). A correct password never fails because of it. Because the attempt was counted before the check, a failed success write is followed by a best-effort write of the pair from before the attempt; only if that write fails too does the counted attempt stay stored until the next correct password (its wait is 0 for counts 1–4).
  - The counting write (item 4 of `openKey`, DEC-75) must succeed before the password is checked. If it throws, `KeyVault` checks no password and returns `StorageFull(failures, retryAtElapsedMs)` with the pair in force (`failures` is the effective count before this attempt, the stored pair merged with the in-memory floor below): never a crash and never a free extra guess, and nothing is kept in memory, because no guess was made. Closing and reopening the app therefore never gives an extra guess, and while storage is full the password cannot unlock until space is freed; the fingerprint route never touches the counters and still works.
  - In-memory floor (DEC-75): the wait answered after a failing `SettingsStore` read (below) is kept in memory under the mutex and binds every later `unlock`, `openKey` and `verifyPassword` of the process: item 1 of `openKey` uses the larger count and the later retry time of the stored pair and the floor. So a parallel caller queued on the mutex, or a gate that dispatches again, still waits. The floor is cleared by a successful counting write, a correct password, a `commit` that installs a new key configuration and `eraseState`.
  - A failing `SettingsStore` read (DEC-75): a `PASSWORD` attempt fails closed before any crypto with `Throttled(count of the floor or 0, AppTime.elapsedMillis() + 30_000)`, held in the same floor (a floor still running is answered as it is); `isPasswordReminderDue()` returns true (a lost timestamp counts as due, section 5.6). No `SettingsStore` exception leaves `KeyVault`, read or write.
  - `KeyVaultTest` covers these with `FakeSettingsStore`, whose `failEdits` and `failReads` switches make every write or read throw an `IOException`, and whose `failPasswordVerified` fails only the success-path edit.
- `commit(generation, slot, bio)` is the only writer of `db-state.json` besides `eraseState` (section 3.5). Its backoff and reminder effects: the two backoff values are reset after the file write succeeded only when the commit installs a new key configuration, that is when no valid committed state existed or `slot` is not `sameAs` the committed slot (a commit follows a verified password, a fingerprint reset or an explicit recovery action). A commit that keeps the key slot (every biometric slot commit, a Restore from `Ready` or `Corrupt`) leaves them alone: a fingerprint is not a password entry (sections 5.3, 5.6). "After the file write succeeded" means: `commit` reads the base file back after `finishWrite()` (which only logs a failed rename) and applies its settings effects (this backoff reset, or the reminder refresh below together with the reset in one `SettingsStore.recordPasswordVerified` edit) only when the bytes parse back to exactly its inputs. Both effects are best effort like the success-path write above: a failure is logged by exception class and never thrown, because `DbMaintenance`'s own read-back decides the outcome of the commit.
- `prepare(PASSWORD, newPassword)` remembers the `KeySlot` it returned (in memory only, under the mutex) as the "freshly typed" slot; when `commit` later stores a slot that is `sameAs` it, `commit` also sets `passwordVerifiedAt = AppTime.nowMillis()` (best effort, as above), because the user has just typed the new password twice (set, change and fingerprint reset). Any other commit leaves `passwordVerifiedAt` unchanged. The remembered slot is cleared by the next `prepare` or `commit`.
- The biometric-slot methods are specified in section 3.7. They never touch the backoff values or `passwordVerifiedAt`, and none of them throws for a Keystore failure of the biometric key: such failures are results (`SlotCipherResult`, `SlotFailed`, `WrapResult`), because the password slot still works (unless the KEK itself fails, which `kekBroken` reports, 3.7.3). A missing or invalid key-state file makes them return `NoSlot`, `KeyFailed`, `None` or (`wrapForBiometric`, which also deletes the cipher's key) `BiometricUnusable` (DEC-79), and the main path reports it (section 5.2). Which exceptions are retried, classified or left to crash is DEC-76's biometric-key rule. None of them rewrites the key-state file: the slot-only commits are `DbMaintenance`'s, through `commit` ([db-lifecycle.md](db-lifecycle.md#biometric-slot-commits)).
- `prepare` is specified in sections 3.2 and 3.3 and never persists anything except creating a missing KEK (or replacing a broken one, section 3.1). For `PASSWORD`, `newPassword` must be non-empty (`require`); for `PLAIN` and `DEVICE` it must be null (`require`), because a password passed there would be silently ignored. It zeroes the DEK and the password KEK before returning. Cancelling the caller while `prepare()` runs does not cancel it, because `DbMaintenance` runs every operation detached in `applicationScope` (section 3.6 below, ownership of secret arrays): the slot that `commit` later stores unlocks with the typed password ([testing.md](testing.md) L-03).
- Test seam for zeroing: `DefaultKeyVault` takes `newSecret: (Int) -> ByteArray = ::ByteArray` and allocates through it every 32-byte DEK (including the DEK unwrapped through the biometric slot and the DEK decoded for wrapping), every 32-byte password-KEK array and every 67-byte raw key (`sqlcipherRawKey` takes the allocator as a parameter, section 3.4). `KeyVaultTest` injects an allocator that records every array it hands out and asserts that all of them, except the raw key returned inside `UnlockResult.Key` or `PreparedKey`, are all-zero when `unlock`, `openKey`, `verifyPassword`, `prepare`, `unlockWithBiometric`, `verifyBiometric` or `wrapForBiometric` returns ([testing.md](testing.md) L-03). The array that `Cipher.doFinal` returns inside `unlockWithBiometric` and `verifyBiometric` is the plaintext DEK, allocated by the provider, not by `newSecret`; the code copies it into the allocator's array and zeroes it in `finally` (3.7.3), and the test checks that through the `BioKeystore` seam, whose `doFinal` returns an array the fake keeps a reference to. Inside `wrapForBiometric` the `doFinal` output is the wrapped DEK, which is returned as `BioSlot.wrappedDek` and never zeroed; the secret there is the decoded DEK, allocated through `newSecret` and zeroed (3.7.2). The Keystore half of the biometric methods sits behind that second seam, `BioKeystore`, whose every key operation takes the alias of the slot or of the arming (DEC-98): `aliases(): List<String>` (every biometric alias, `isBioKeyAlias`), `containsAlias(alias): Boolean`, `generate(alias)` (replaces an existing key of that alias), `delete(alias)` (a missing alias is not an error), `initEncrypt(alias): Cipher` (the Keystore picks the IV, read back as `cipher.iv`), `initDecrypt(alias, iv): Cipher`, `updateAad(cipher, aad)`, `doFinal(cipher, input): ByteArray`; a missing alias makes `initEncrypt` and `initDecrypt` throw `UnrecoverableKeyException`; production `AndroidBioKeystore`, whose public constructor takes no argument and uses `bioKeySpec(alias)`, while an internal constructor taking the spec function `(String) -> KeyGenParameterSpec` serves only the instrumented test configuration, so the tests can drive every failure class of section 3.7.4 through a fake that wraps the real AndroidKeyStore (`FaultyBioKeystore`, testing.md); the real authentication behavior is covered by the device tests. The KEK half sits behind a third seam, `KekKeystore` (`val hasStrongBox`, `containsAlias()`, `generate(strongBox: Boolean)`, `delete()`, `initEncrypt()`, `initDecrypt(iv)`, `doFinal(cipher, input)` for `doro_kek_v1`; production `AndroidKekKeystore`, section 3.2 "Code shape"; a missing alias makes `initEncrypt` and `initDecrypt` throw `UnrecoverableKeyException`, a member of the section 5.2 Keystore family), through which tests inject the KEK failures of section 5.2 (for example a `KeyStoreException`, or an `IllegalBlockSizeException` from the outer cipher), and the password derivation is the constructor parameter `kdf: (password: ByteArray, salt: ByteArray, iterations: Int) -> ByteArray = ::pbkdf2Sha256` (section 3.3), through which tests count derivations. [testing.md](testing.md) calls `BioKeystore`, `KekKeystore` and `kdf` together the "Keystore-operations seam"; each is a constructor parameter that only the tests replace (the instrumented "test configuration" of `BioKeystore` generates the biometric key without the user-authentication parameters, testing.md § 5), and the production `AppContainer` always uses the defaults. Constructor: `DefaultKeyVault(stateFile: File, kek: KekKeystore, settings: SettingsStore, appTime: AppTime, dispatchers: AppDispatchers, newSecret: (Int) -> ByteArray = ::ByteArray, kdf: (ByteArray, ByteArray, Int) -> ByteArray = ::pbkdf2Sha256, bio: BioKeystore = AndroidBioKeystore())`; the production instance is `DefaultKeyVault.create(context, settings, appTime, dispatchers)`: `stateFile = File(context.noBackupFilesDir, DefaultKeyVault.STATE_FILE_NAME)` (`"db-state.json"`, DEC-80), `kek = AndroidKekKeystore.forDevice(context)` and the default seams. The calibration of section 3.3 coerces a measured duration of 0 to 1 ms, so `iterationsFor(0)` returns 5,000,000 without a division by zero.
- Every password check is counted before it runs (item 4 of `openKey`, and the counting rule above): `KeyVault` writes `security.unlock_failures` and `security.unlock_retry_at` before it derives anything, so they are persisted before it returns `PasswordRejected`, and resets both after a correct password and in a `commit` that installs a new key configuration. `KeyVault` returns `UnlockResult` and throws `KeyVaultException` rather than `AppResult` because its callers (effect E2 and `DbMaintenance`) map the results to gate events and to `AppError` themselves ([architecture.md](architecture.md#error-model)).
- Error mapping at the boundary ([architecture.md](architecture.md) § Error model): E2 maps `Key` to `DatabaseHolder.open`, `PasswordRejected` and `Throttled` to the gate event `PasswordRejected(failures, retryAt)` (T21), `StorageFull` to `PasswordStorageFull(failures, retryAt)` (T63; E4 likewise to T64), `PasswordNeeded` to T22 and `KeyFailed` (and every `KeyVaultException`) to `KeyFailed` (T23), with one exception on the password path of a `PASSWORD` slot: when `AppLockController.reportPasswordKekFailure()` returns true (lock on, a `Live` biometric slot, section 7.3) the Keystore-layer failure is not KeyUnavailable (DEC-86), and E2 raises `KekFailedWithSlot` (T59: `Locked(Open)` and the slot prompt, even when the reminder is due) instead. For the slot path (`unlockWithBiometric`), E2 maps `Key(rawKey, kekBroken)` to `DatabaseHolder.open` and, when `kekBroken`, calls `AppLockController.reportKekBroken()` (section 7.3); `SlotFailed(invalidated = true)` to `PasswordNeeded(failures, retryAt, FingerprintChanged)` (T22); `SlotFailed(invalidated = false)` to `SlotUnlockFailed` (T44: back to `Locked(Open)` with `authError = LockError(SLOT_FAILED, null)`, section 3.7.4); `KeyFailed` to T23. An open through a slot-released key that returns `OpenResult.Failed(Corrupt, keyRejected = true)` makes E2 call `deleteBiometricKey()` and raise `PasswordNeeded(…, FingerprintChanged)` (T22, section 3.7.3). The event names and rows are [architecture.md](architecture.md) § SessionGate's. `AppLockController.confirmWithPassword` maps `PasswordRejected` to `Err(WrongPassword)`, `Throttled` to `Err(Throttled(retryAtElapsedMs))`, `StorageFull` to `Err(StorageFull)` (not checked, not counted, DEC-75) and `KeyFailed` to `Err(KeyUnavailable)` as a message only, never touching the gate (DEC-86). `DbMaintenance` maps `PasswordRejected` to `AppError.WrongPassword`, `Throttled` to `AppError.Throttled(retryAtElapsedMs)`, `StorageFull` to `AppError.StorageFull`, `KeyFailed` and every `KeyVaultException` to `AppError.KeyUnavailable`, with one exception: a `KeyFailed` from `openKey` on the database-password question of a Restore from `Corrupt` while a live biometric slot exists asks for the fingerprint first (DEC-86, section 4.1); it zeroes `Key.rawKey` at once when it only verified a password.
- Ownership of secret arrays. A secret array changes owner when it is passed to a call that starts a detached operation. `SessionGate.dispatch(PasswordSubmitted(chars))` hands `chars` to effect E2, and every `DbMaintenance` entry point that takes a password hands it to the operation; the receiver zeroes the arrays in its own `finally` after the last use. The caller never reads, zeroes or reuses an array after handing it over, even when the caller is cancelled (the operation runs detached in `applicationScope`, [architecture.md](architecture.md) § Layering and data flow). A caller that still needs the array passes `copyOf()`. `KeyVault` never zeroes its inputs; arrays it returns belong to the receiver. `PreparedKey.rawKey` belongs to `DbMaintenance` until `commit` returns `Committed`, then to `DatabaseHolder`; on any failure before the commit `DbMaintenance` zeroes it. A `SlotCipher` changes owner the same way: whoever receives it uses it once and drops it; it is never stored in a ViewModel, `SavedStateHandle`, Bundle or navigation key.

### 3.7 Biometric slot (DEC-60, DEC-65, Q8)

In `PASSWORD` mode with the biometric lock on, a second wrap of the DEK lets a strong biometric open the database without the password. The password slot of section 3.1 is unchanged and works unless its Keystore KEK fails (3.7.3, `kekBroken`); the biometric slot is an optional extra that can be lost at any time (enrollment change, Keystore problem) and is then re-created after one password unlock. `PLAIN` and `DEVICE` mode never have a biometric slot: their lock is a prompt without a `CryptoObject` (section 7.2).

Who does what. `KeyVault` (this file) owns the Keystore half: key generation, the `CryptoObject` ciphers, wrap and unwrap, deleting the key. `DbMaintenance` owns every write of the key-state file that adds or removes a `bioSlot`: `createBiometricSlot` and `removeBiometricSlot`, which implement `BiometricSlotWriter` ([db-lifecycle.md § Biometric slot commits](db-lifecycle.md#biometric-slot-commits): `opMutex`, `KeyVault.commit`, directory sync, read-back, the one repair of DEC-80). `AppLockController` owns the prompts and hands the authorized `SlotCipher` on (section 7.3; its gate wiring is [architecture.md](architecture.md) § AppLockController and BiometricGateway). `KeyVault` never rewrites the key-state outside `commit` and `eraseState` (section 3.5).

#### 3.7.1 Biometric key generation
```kotlin
// KeyAliases.kt, next to KEK_ALIAS. One key per slot under a fresh alias (DEC-98).
internal const val BIO_ALIAS_PREFIX = "doro_bio_v1"          // every biometric alias starts with it, the legacy constant included
internal fun isBioKeyAlias(alias: String): Boolean           // alias.startsWith(BIO_ALIAS_PREFIX): a biometric key the deletions of 3.7.5 may remove
internal fun isSlotKeyAlias(alias: String): Boolean          // the only bioSlot.keyAlias the parser accepts (3.5): "doro_bio_v1_" + 32 lowercase hex digits
internal fun newBioKeyAlias(random: SecureRandom): String    // that form from 16 random bytes; every arming gets its own

/** The production spec. BiometricKeySpecTest (testing.md L-30) pins every parameter by calling this function; it generates nothing. */
internal fun bioKeySpec(alias: String): KeyGenParameterSpec = KeyGenParameterSpec.Builder(
        alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setKeySize(256)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setUserAuthenticationRequired(true)
    .apply {
        val auth = bioKeyAuth(Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)                // tested again here: lint NewApi trusts only SDK_INT
            setUserAuthenticationParameters(auth.validitySeconds, checkNotNull(auth.type))   // 0 s: every operation needs its own authentication
        else
            @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(auth.validitySeconds) // API 28–29: -1, per operation, biometric only
    }
    .setInvalidatedByBiometricEnrollment(true)    // the platform default, set explicitly (DEC-60)
    .setIsStrongBoxBacked(false)                  // TEE-backed on purpose, see below; no setUnlockedDeviceRequired, no setUserAuthenticationValidWhileOnBody
    .build()

/** The authentication parameters by API level, pure so the API 28–29 branch is pinned on the JVM (BiometricKeySpecTest, L-30, DEC-15).
 *  type null = the API 28–29 call setUserAuthenticationValidityDurationSeconds(validitySeconds). */
internal data class BioKeyAuth(val validitySeconds: Int, val type: Int?)
@SuppressLint("InlinedApi")   // AUTH_BIOMETRIC_STRONG is a compile-time constant, passed to the Keystore only on API 30+
internal fun bioKeyAuth(sdkInt: Int): BioKeyAuth =
    if (sdkInt >= Build.VERSION_CODES.R) BioKeyAuth(0, KeyProperties.AUTH_BIOMETRIC_STRONG) else BioKeyAuth(-1, null)

// Generation: AndroidBioKeystore.generate(alias) runs KeyGenerator.getInstance(KEY_ALGORITHM_AES, "AndroidKeyStore").init(spec).generateKey().
// biometricArmCipher deletes the keys no committed bioSlot names, then generates under newBioKeyAlias(random): arming always starts from a new key
// and never replaces the committed slot's (DEC-98).
```
- The biometric key is never StrongBox-backed. DEC-60 asks for StrongBox only for the KEK, and a StrongBox authentication-bound key that generates but then fails at `doFinal` (seen on some OEM StrongBox implementations) would fail on every unlock with no self-check at arming (3.7.2 step 5) and cost the user a fingerprint touch each time. The trade-off is that the key lives in the TEE, whose authentication enforcement is the platform's normal one. There is no `allowStrongBox` flag and no per-process state.
- `setRandomizedEncryptionRequired` keeps its default (true), so the Keystore chooses every encryption IV and rejects caller IVs.
- A per-operation key accepts only an authentication bound to that operation through a `BiometricPrompt.CryptoObject`; the device credential cannot authorize it on any API level used here. That is why the slot prompt allows `BIOMETRIC_STRONG` only (section 7.2).
- The key becomes permanently invalid when a new biometric is enrolled or all biometrics are removed (`setInvalidatedByBiometricEnrollment(true)`), and, like every authentication-bound key, when the secure lock screen is removed. Using it then throws `KeyPermanentlyInvalidatedException` from `Cipher.init`.
- Verify at setup: on the owner's phone (Poco F7, DEC-15; [testing.md](testing.md) `BiometricSlotDeviceTest` and the manual items M-22 to M-25), (a) a TEE-backed key with these parameters generates and works through a `CryptoObject` prompt, with `Cipher.init` never throwing `UserNotAuthenticatedException` for a per-use key; (b) enrolling an additional fingerprint and removing all fingerprints each make `Cipher.init` throw `KeyPermanentlyInvalidatedException`; (c) whether removing one of several fingerprints invalidates the key (platform-dependent; both outcomes are handled). (d) The API 28–29 branch (validity −1, androidx.biometric 1.1.0 authorizing the `CryptoObject` with `BIOMETRIC_STRONG`) cannot run on that phone: it is covered by lint `NewApi`, the JVM test of the spec branching (`BiometricKeySpecTest`) and review, an accepted risk of DEC-15; an optional API 28 emulator with an enrolled fingerprint may check it. Verified at setup for (a) (SPEC T22, 2026-11-21, Poco F7, API 36): the per-use, non-StrongBox slot key generates with the pinned parameters (authentication required, invalidated by enrollment, enforced by secure hardware, `AUTH_BIOMETRIC_STRONG`), and it arms and unlocks through a real `CryptoObject` prompt. Its `Cipher.init` succeeds without any prior authentication (`BiometricSlotDeviceTest`, 3/3, build/t22-realbio).

#### 3.7.2 Arming the slot
> **DEC-98 (owner, 2026-11-20; SPEC T24):** each arming generates its key under a fresh alias, the committed slot's key is never deleted before `COMMIT_BIO_SLOT`, `bioSlot.keyAlias` names the slot's own key, and `Live` requires that alias (section 3.6 `BioSlotStatus`). So an arming that never commits leaves the committed slot `Live` or `Dead` as it was.

Arming creates the biometric slot from the live DEK. Preconditions: the committed mode is `PASSWORD`, the database is open (`Ready`), the lock is being turned on or is on without a live slot (sections 7.1, 7.8), and a strong biometric is usable.
0. Authorization before arming (DEC-65): turning the lock on and Settings "Set up" in `PASSWORD` mode first verify the startup password with `KeyVault.verifyPassword` (`AppLockController.confirmWithPassword`, section 7.1); only the re-creation offer of section 7.8, which follows a password entry in the same foreground session, skips it. A finger enrolled by someone who only knows the device PIN therefore never arms a slot.
1. `KeyVault.biometricArmCipher()`, under the mutex: read the committed state (mode must be `PASSWORD`, else `NoSlot`); delete every biometric key the committed `bioSlot` does not name (the unused keys of earlier armings, best effort), then `alias = newBioKeyAlias(random)` and `BioKeystore.generate(alias)` (3.7.1; the committed slot's key is not touched, so that slot stays as it was until `COMMIT_BIO_SLOT` replaces it, DEC-98); `Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)` with no parameters, so the Keystore generates the 12-byte GCM IV. Any exception here deletes the fresh alias and returns `Unavailable`. Otherwise `Ready(SlotCipher(Arm, cipher, alias, state.generation, state.slot.outerIv.copyOf(), bioIv = null))`.
2. `AppLockController` shows the slot prompt with `BiometricPrompt.CryptoObject(cipher)` (purpose `EnableLock` or `SetUpSlot`, section 7.2; for `SetUpSlot` either the offer's `createSlot()` or Settings' `confirmWithPassword(SetUpSlot, …)`). A cancel or an error ends here; the unused key stays until the next arming, startup reconciliation, removal, new-DEK commit or Erase deletes it (3.7.5).
3. On success `AppLockController` (after writing `security.app_lock = true` when the lock is being turned on, section 7.1) calls the injected `BiometricSlotWriter.createBiometricSlot(cipher)` (section 7.3, [architecture.md](architecture.md) § Event wiring), implemented by `DbMaintenance.createBiometricSlot`. Its mechanics are [db-lifecycle.md](db-lifecycle.md#biometric-slot-commits)'s: `opMutex.tryLock()` (`Err(Busy)` while another operation holds it), read the committed state, `PREPARE_BIO_SLOT` (copy of the live raw key, `KeyVault.wrapForBiometric(cipher, live)`, zero the copy in `finally`), `COMMIT_BIO_SLOT` through `KeyVault.commit(generation, slot, bio)`, directory sync, read-back with the `Committed` / `NotCommitted` / `Unknown` classification and the one repair attempt. This file owns the crypto in step 4 and nothing about the file write.
4. `KeyVault.wrapForBiometric(cipher, liveRawKey)`, under the mutex (`require(cipher.use == SlotUse.Arm)` first, before any Keystore call, so a caller bug is a crash and not a state change):
   - Re-read the committed state. If there is no file or an invalid one (DEC-79), or its generation differs from `cipher.generation`, or the mode is not `PASSWORD`, or its `slot.outerIv` differs from `cipher.outerIv` (a key change committed meanwhile): delete the cipher's key (`cipher.keyAlias`), return `BiometricUnusable`.
   - `dek = dekFromRawKey(liveRawKey)` (below), allocated through `newSecret`.
   - `cipher.updateAAD("doro.bio.v1:" + base64(state.slot.outerIv))` (ASCII), then `wrapped = cipher.doFinal(dek)`, `iv = cipher.iv`. AAD is added only now, after the authentication: on an authentication-bound key every operation step after `init` needs the authorization.
   - Check `iv.size == 12` and `wrapped.size == 48`; zero `dek` in `finally`.
   - Return `Wrapped(BioSlot(cipher.keyAlias, iv, wrapped))`: the slot names the key it was wrapped with. Nothing is written: the caller commits.
   - Any exception from `updateAAD`, `doFinal` or the checks: delete the cipher's key, return `BiometricUnusable`.
5. No self-check by unwrapping: it would need a second authentication. The first unlock through the slot is the check. A slot that fails its tag (3.7.4) is dead from then on, and a slot that wraps a wrong DEK is caught when the open fails through it (3.7.3), so a bad slot costs one password entry, never data.

The AAD binds the biometric slot to the password slot it was armed next to: `slot.outerIv` is new for every wrap of every new DEK, so a stale `bioSlot` paired with a newer `slot` (which `DbMaintenance` never writes, section 3.5) would fail the tag instead of releasing a wrong key.

```kotlin
/** Inverse of sqlcipherRawKey (section 3.4) without a String. The caller zeroes the result. */
internal fun dekFromRawKey(raw: ByteArray, newSecret: (Int) -> ByteArray): ByteArray {
    require(raw.size == 67 && raw[0] == 'x'.code.toByte() && raw[1] == '\''.code.toByte() && raw[66] == '\''.code.toByte())
    fun nibble(b: Byte): Int = when (val c = b.toInt()) {
        in 0x30..0x39 -> c - 0x30
        in 0x61..0x66 -> c - 0x61 + 10
        else -> throw IllegalArgumentException("raw key")       // constant message (section 10)
    }
    val out = newSecret(32)
    try {
        for (i in 0 until 32) out[i] = ((nibble(raw[2 + 2 * i]) shl 4) or nibble(raw[3 + 2 * i])).toByte()
    } catch (e: IllegalArgumentException) {
        out.fill(0)                                               // half a DEK is still secret
        throw e
    }
    return out
}
```

#### 3.7.3 Unwrapping through the slot
Every step below runs inside one public `KeyVault` member under the single mutex and uses only `*Locked` helpers (section 3.6).
1. `KeyVault.biometricUnlockCipher()`: read the committed state. No file, an invalid file, mode ≠ `PASSWORD` or no `bioSlot` (a malformed one included, section 3.5; then every biometric key the file does not name is also deleted, best effort): `NoSlot` (an invalid file is then reported by the main path, section 5.2). The slot's alias (`bioSlot.keyAlias`) missing: return `Invalidated` (the key is already gone; the dead `bioSlot` stays in the file). Otherwise `Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bioSlot.iv))`:
   - `KeyPermanentlyInvalidatedException`: delete the slot's key (`deleteBioKeyLocked(bioSlot.keyAlias)`), return `Invalidated`. The file is not touched.
   - any other `GeneralSecurityException`, `ProviderException` or `IOException` from the Keystore: retry once after 1 s (`delay(1000)` on `dispatchers.io`); if it fails again, count it (3.7.4, repeated failures) and return `Unavailable`, keeping the slot.
   - success: `Ready(SlotCipher(Unlock, cipher, bioSlot.keyAlias, state.generation, state.slot.outerIv.copyOf(), bioSlot.iv.copyOf()))`.
2. `AppLockController` shows the slot prompt with `CryptoObject(cipher)` (purpose `Unlock`, `DisableLock`, `ResetPassword` or `RestoreKey`). On success `BiometricGateway` reports whether `result.cryptoObject?.cipher` is the very cipher it passed (identity, `PromptResult.Succeeded.cryptoMatches`); `AppLockController` passes on the `SlotCipher` that `KeyVault` returned and never constructs one. A missing or different crypto object is handled as `SlotFailed(invalidated = false)` without calling `KeyVault`, and it is not counted (DEC-79). Verified at setup (SPEC T22, 2026-11-21, Poco F7, API 36): `AuthenticationResult.cryptoObject.cipher` is the very `Cipher` passed to `authenticate`, for both the ENCRYPT (arming) and the DECRYPT (unlock) prompt, so `cryptoMatches` holds and fingerprint arming and unlock succeed (`BiometricSlotDeviceTest#enableLock_withATouch_commitsASlotOnItsOwnAuthBoundKey`, `#coldStart_inPasswordMode_opensThroughTheSlot_withATouch`, build/t22-realbio). This check gates the design: if the path re-wrapped the cipher, every arming would end with `Err(AuthFailed)` (section 7.1) and no fingerprint unlock could ever succeed. The API 28 path that androidx.biometric routes through `FingerprintManager` is not run on a device (DEC-15, accepted risk; review of the pinned androidx.biometric source, optionally an API 28 emulator).
3. `KeyVault.unlockWithBiometric(cipher)` (cold start through E2, Restore from `Corrupt`, the fingerprint reset), under the mutex. First `require(cipher.use == SlotUse.Unlock)` (an `IllegalArgumentException` with constant text, before any Keystore call: an arm cipher that reached this path through a caller bug would encrypt 48 bytes into 64, trip the length rule below and kill a valid slot, so it must crash instead). Then re-read the committed state: no file or an invalid one returns `KeyFailed` (the main path reports it, section 5.2); if its mode is not `PASSWORD`, it has no `bioSlot`, or its generation, `slot.outerIv`, `bioSlot.keyAlias` or `bioSlot.iv` no longer match the cipher's (`generation`, `outerIv`, `keyAlias`, `bioIv`), return `SlotFailed(invalidated = false)` without using the cipher (a legitimate race: a key change, a removal or a re-arming during the prompt; not counted, DEC-79). Otherwise:
   - `cipher.updateAAD("doro.bio.v1:" + base64(outerIv))`.
   - `val tmp = cipher.doFinal(bioSlot.wrappedDek); try { if (tmp.size == 32) tmp.copyInto(dek) else slotIsDead = true } finally { tmp.fill(0) }` with `dek = newSecret(32)` (the length rule below, written here as a flag so that the zeroing in `finally` runs on every path). `doFinal` returns an array that the provider allocated, outside the allocator, so it is zeroed here in `finally`; the plaintext DEK also passed through the Keystore's binder reply, whose copies cannot be cleared (section 6.5).
   - `rawKey = sqlcipherRawKey(dek, newSecret)`, zero `dek`, then the KEK check (below), return `Key(rawKey, kekBroken)`.
   - `AEADBadTagException` or a plaintext that is not 32 bytes: the slot is dead. Delete the slot's key, return `SlotFailed(invalidated = true)`. The file is not touched.
   - any other exception, including an enrollment change during the prompt, which surfaces at the next `Cipher.init` and is then handled by step 1: return `SlotFailed(invalidated = false)` and keep the slot, counted (3.7.4). Never retried: a retry needs a new authentication. `KeyPermanentlyInvalidatedException` is an `InvalidKeyException` thrown by `init`; AndroidKeyStore reports `doFinal` failures as `IllegalBlockSizeException` or `BadPaddingException` with a `KeyStoreException` cause (section 5.2), and the classification is by class, never by message, so only the tag failure is a reliable "dead" signal at `doFinal`.
   - Verify at setup: on the owner's phone (Poco F7, DEC-15), which exception `doFinal` throws when a finger is enrolled between `Cipher.init` and `doFinal` (still open, M-23, T23). Verified at setup (SPEC T22, 2026-11-21, Poco F7, API 36): the per-use slot key's `Cipher.init` succeeds before any authentication, for both the arming and the unlock cipher, and only an unauthorized `doFinal` is refused (`BiometricSlotDeviceTest#doFinal_outsideAnAuthenticatedPrompt_isRefused_asAKeptSlotFailure`, build/t22-realbio).
4. `KeyVault.verifyBiometric(cipher)`: the same steps (including the `use` check, the counting and the KEK check), but the DEK is zeroed right after the tag check and no raw key is built; returns `Verified(kekBroken)`. Used on return from background, where the database is already open, and to turn the lock off.
- The KEK check (`outerUnwrapLocked`). The slot wraps the DEK directly and never touches the Keystore KEK, so a lost or broken KEK would leave fingerprint unlock working while the password slot has silently become unusable; the failure would then surface only when the 14-day reminder or an enrollment change forces the password, and verifying it would close an open, readable database. Therefore both unwrap methods also run the Keystore layer of the committed `PASSWORD` slot, which needs no password and no authentication: decrypt `slot.wrappedDek` (64 bytes) with the KEK using `outerIv` and the outer AAD of section 3.1, require 48 bytes, zero the result. A Keystore-layer failure is retried once after 1 s as in section 5.2; if it still fails (or the length is wrong) the result carries `kekBroken = true` and `KeyVault` remembers, in memory under the mutex, the generation for which it failed (`brokenKekGeneration`). It never changes the unlock itself, and it is never `KeyUnavailable` while the slot works. The same holds when the KEK failure is found first by a password unlock: with the lock on and a `Live` slot the gate falls back to the fingerprint route instead of `KeyUnavailable` (DEC-86, section 5.2, `AppLockController.reportPasswordKekFailure()` in section 7.3), and the fingerprint unlock that follows reports `kekBroken` here. It holds as well when the KEK failure is found by the database-password question of a Restore from `Corrupt`: with a live slot the restore asks for the fingerprint instead of ending with `KeyUnavailable` (DEC-86, section 4.1). `AppLockController.reportKekBroken()` then raises the notice of section 7.3 ("Back up now"; its text points to "Forgot password?", where the fingerprint reset replaces the KEK, sections 3.1 and 4.3; [ui.md](ui.md) owns the texts).
- None of these steps touches the backoff values or `passwordVerifiedAt`: a fingerprint is not a password entry (sections 5.3, 5.6).
- The DEK that the slot releases is authentic (GCM tag) but not proven to be the database's key: nothing test-unwraps a slot when it is armed, so an authentic slot that wraps a wrong DEK is possible after a bug. An open through the slot that fails with `SQLiteNotADatabaseException`, a header/mode mismatch or a page-1 HMAC failure is therefore a slot failure, never Corrupt and never WrongPassword: `DatabaseHolder.open` reports `OpenResult.Failed(Corrupt, keyRejected = true)`, and E2, which knows it holds a slot-released key, calls `KeyVault.deleteBiometricKey()` and raises `PasswordNeeded` with the reason `FingerprintChanged` (T22; [db-lifecycle.md](db-lifecycle.md#failure-classification) owns the row). The password path then proves the key, and only a failure after a password unlock is Corrupt. A failure after a successful open (`quick_check`, a runtime `SQLiteDatabaseCorruptException`) is Corrupt either way, because a key that decrypted and authenticated page 1 is the right key. Every other consumer of a slot-released key applies the same rule at its first open of the old database: the fingerprint reset's `CHECK_SOURCE` (a NOTADB there ends the reset with `Err(AuthFailed)`, section 4.3) and the slot-key check of a Restore from `Corrupt` ([db-lifecycle.md § Origins and target key](db-lifecycle.md#origins-and-target-key)), where a missing or empty live file cannot be checked and the restore asks the password instead. In each case the biometric key is deleted and the password path remains.
- Verify at setup: on the owner's phone (Poco F7, DEC-15), `updateAAD` on an authentication-bound AndroidKeyStore GCM cipher before the prompt fails and after the prompt succeeds (the design adds AAD only after the prompt either way), and a DECRYPT cipher initialized before a prompt that stays open for 60 s still completes `doFinal`. Verified at setup for the part after the prompt (SPEC T22, 2026-11-21, Poco F7, API 36): after a successful prompt, `updateAAD` and `doFinal` on the authentication-bound DECRYPT cipher complete and release the key (`BiometricSlotDeviceTest#coldStart_inPasswordMode_opensThroughTheSlot_withATouch`, build/t22-realbio). Whether `updateAAD` itself fails before the prompt is not shown: the unauthorized path only shows that the `updateAAD` plus `doFinal` pair is refused. That part needs no further check, because the design adds AAD only after the prompt. The 60 s case did not run in T22: a `@RealBiometric` test fails when no touch comes within 60 s ([testing.md § 2.1](testing.md#21-biometric-slot-tests-what-runs-where)). It moves to T23 with M-22.

#### 3.7.4 Failure classes of the slot
Cause names (`SlotMissing`, `SlotInvalidated`, …) are the internal `PasswordCause` values of section 5.1, which maps them onto the public `PasswordReason` and `fingerprintUsable` (defined in section 5.1; the gate carries them, [architecture.md](architecture.md) § Unlock routes).

| Failure | Detected in | Result for the user | Slot and key |
|---|---|---|---|
| No `bioSlot` (mode `PASSWORD`, lock on), a malformed `bioSlot` ignored by the parser (3.5) | `UnlockPolicy.route` (section 5.1), or `NoSlot` | Password route, cause `SlotMissing`; after the password the re-creation prompt (7.8) | none to change; a malformed member is kept (dead) in the file until the next commit; every biometric key the file does not name is deleted best effort |
| `KeyPermanentlyInvalidatedException` at `init`, or alias missing | `biometricUnlockCipher` | Password route, cause `SlotInvalidated`; after the password the re-creation prompt | Kept in the file (dead); the slot's key deleted |
| Other Keystore failure at `init` (after one retry) | `biometricUnlockCipher` | Password route for this unlock, cause `SlotUnavailable` | Kept; counted (below) |
| Prompt error or cancel | `AppLockController` (section 7.4) | Per the table of section 7.4 | Kept |
| Tag failure or wrong plaintext length at `doFinal` | `unlockWithBiometric`, `verifyBiometric` | Password route, cause `SlotInvalidated`; after the password the re-creation prompt | Kept in the file (dead); the slot's key deleted |
| Open through the slot fails with `SQLiteNotADatabaseException`, a header/mode mismatch or a page-1 HMAC failure (`OpenResult.Failed(Corrupt, keyRejected = true)`, 3.7.3) | E2; the reset's `CHECK_SOURCE`; the slot-key check of a Restore from `Corrupt` | Password route, cause `SlotInvalidated`; the password path then decides between success and Corrupt | Kept in the file (dead); every biometric key deleted by `deleteBiometricKey` |
| Other exception at `doFinal`; key state changed between `init` and `doFinal`; missing or different crypto object in the result | `unlockWithBiometric`, `verifyBiometric`; the `cryptoMatches` check in `AppLockController` | Stay on the lock with the generic error `LockError(SLOT_FAILED, null)` (T44 at cold start, T11 over the open database); `DisableLock` returns `Ok(PasswordRequired)` for a `SlotFailed` and `Err(AuthFailed)` for a crypto-object mismatch (section 7.3); the "Unlock" button prepares a new cipher; "Use password" stays available | Kept; counted, except the races and the crypto-object mismatch (DEC-79) |
| Two consecutive failures of the same slot (below) | `biometricUnlockCipher`, `unlockWithBiometric`, `verifyBiometric` | As an invalidated slot: password route, cause `SlotInvalidated`, then the re-creation prompt | Judged dead: the slot's key deleted, kept in the file (dead) |
| Wrapping failure while arming | `biometricArmCipher`, `wrapForBiometric` (or the commit, which is `DbMaintenance`'s) | Enabling: `security.app_lock` is written back to false and the toggle stays off with an error (section 7.1). Settings "Set up": the lock stays on with an error. Re-creation: offered again after the next password unlock | Nothing committed; the prompt's key deleted (by `wrapForBiometric`, or by `discardArmCipher` for the commit), the committed slot's key kept (DEC-98) |
| Outer (KEK) layer of the password slot fails during a fingerprint unlock | `unlockWithBiometric`, `verifyBiometric` | The unlock succeeds with `kekBroken = true`; the kekBroken notice (sections 3.7.3, 7.3) | Slot kept; nothing deleted |
| Outer (KEK) layer of the password slot fails during a password unlock (cold start E2, return-from-background check E4) while the lock is on and the slot is `Live` | `KeyVault.unlock`, `KeyVault.verifyPassword` (`KeyFailed`), then `AppLockController.reportPasswordKekFailure()` | Not KeyUnavailable (DEC-86): the gate falls back to the fingerprint route (`KekFailedWithSlot`, architecture.md T59, T60), also when the reminder is due; after the slot unlock the kekBroken notice (section 7.3). Without a usable strong biometric at that moment the password screen returns with the reason `KeyStoreFailed` (section 5.1) | Slot kept; nothing deleted; no backoff count |
| Outer (KEK) layer of the password slot fails on the database-password question of a Restore from `Corrupt` (`PASSWORD` mode) while the slot is live (lock on, `Live`) | `KeyVault.openKey` (`KeyFailed`) in `DbMaintenance`'s `UNLOCK_DEVICE_KEY`, then `AppLockController.reportPasswordKekFailure()` from the status host | Not `Err(KeyUnavailable)` (DEC-86): the restore asks for the fingerprint (`NeedsBiometricUnlock(afterKekFailure = true)`, then `slotPrompt(RestoreKey)`), also when the reminder is due; the key it releases goes through the slot-key check of section 4.1. Only if that fingerprint does not yield an accepted key (no usable strong biometric at that moment, "Use password", cancel, any prompt error, `SlotFailed`, `KeyFailed`, a failed or impossible slot-key check) does the restore end with `Err(KeyUnavailable)` on the Corrupt screen, because the password cannot work. The kekBroken notice is set and appears in `Ready` | Slot kept (the biometric keys are deleted only by a failed slot-key check); nothing else deleted; no backoff count |
| Lock screen removed (device no longer secure) | `UnlockPolicy.route` (section 5.1) | `PASSWORD` mode: the password at the next unlock, then the auto-off writes (7.6); the platform has invalidated the key anyway | Keys deleted with the auto-off removal (`DbMaintenance.removeBiometricSlot`, after the setting write) |

- No slot failure is KeyUnavailable, Corrupt or WrongPassword; none counts toward the backoff, deletes data or changes `slot`. KeyUnavailable arises only from the main slot and the key-state file (section 5.2).
- `KeyVault` never rewrites the key-state file because of a slot failure. A dead `bioSlot` is tolerated ([db-lifecycle.md](db-lifecycle.md#key-state-semantics)): the next commit of any kind (a slot commit or a generation commit) replaces or drops it, and nothing else can change the key-state while `DbMaintenance` holds its operation mutex (so a Restore or a slot commit never races with a rewrite from the unlock path). Keeping the dead slot in the file also keeps the reason: `biometricSlotStatus()` reports `Dead` while the file has a `bioSlot` and its key (`bioSlot.keyAlias`) is missing, so the password screen keeps the reason `FingerprintChanged` until the slot is re-created.
- Repeated failures. A slot that fails persistently without being invalidated (a Keystore that keeps throwing at `init`, an OEM key that generates and then fails at `doFinal`) must not cost a fingerprint touch on every unlock forever, and the re-creation offer needs the slot to be dead. `KeyVault` keeps an in-memory counter keyed by the `bioSlot` IV: it increases on every `Unavailable` result of `biometricUnlockCipher` and on every `SlotFailed(invalidated = false)` that `unwrapLocked` returns after using the cipher (not the races of 3.7.3 step 3, not a crypto-object mismatch, not a failed alias check of `biometricSlotStatus`; DEC-77, DEC-79), and it resets on any successful unwrap and when the IV changes. At the second consecutive failure the slot is judged dead: delete the slot's key and return `Invalidated` (cipher preparation) or `SlotFailed(invalidated = true)` (unwrap). The counter is not persisted, so a restart gives the slot two more chances.
- A failed alias deletion is ignored; the next `biometricUnlockCipher` finds the key unusable and deletes it again.

#### 3.7.5 Slot lifecycle
| Event | `bioSlot` in the file | Keystore keys (`doro_bio_v1_…`, one per arming, DEC-98) |
|---|---|---|
| Lock turned on in `PASSWORD` mode, or Settings "Set up" (section 7.1) | Added by `DbMaintenance.createBiometricSlot` (after the password check and, for turning on, after `security.app_lock = true`) | Generated under a fresh alias by `biometricArmCipher`; after the commit every key the new slot does not name is deleted (`deleteUnreferencedBiometricKeys`) |
| Re-creation prompt succeeds (section 7.8) | Added by `DbMaintenance.createBiometricSlot` (a dead slot is replaced) | Generated under a fresh alias; the replaced slot's key and every other key the new slot does not name go after the commit |
| Commit with a new DEK: every key change of section 4, the fingerprint reset, Restore from `KeyUnavailable`, Start fresh | Not written (`bio = null`) | Deleted by `DbMaintenance` after the commit (`DELETE_BIO_KEY` = `KeyVault.deleteBiometricKey()`, every biometric key, unconditional) |
| Restore from `Ready` or `Corrupt` (same DEK; a backup does not touch the key-state) | The committed `bioSlot` is passed to `commit` unchanged | Kept |
| Lock turned off, by the user or by the auto-off of 7.6 | Removed by `DbMaintenance.removeBiometricSlot` after `security.app_lock = false` was written (DEC-65); it deletes the key first and rewrites the file only while it can take `opMutex`, and writes nothing for a missing or invalid file or a file without `bioSlot` | All deleted first, through `deleteBiometricKey()` |
| Invalidation, tag failure, repeated failure, open failure through the slot (3.7.4) | Kept (dead) until the next commit | The slot's key deleted (an open failure through the slot: all, by `deleteBiometricKey()`) |
| Slot creation that does not commit: an early exit, `Unexpected`, `NotCommitted`, `Unknown`, a stale prompt after a key change ([db-lifecycle.md](db-lifecycle.md#biometric-slot-commits)) | Unchanged; after an `Unknown` whose repair failed the file may name the new slot | The prompt's key deleted (`discardArmCipher`) unless the file names it; the committed slot's key kept |
| Startup reconciliation ([db-lifecycle.md](db-lifecycle.md#startup-reconciliation)) | Never written | Every key that no committed `bioSlot` names deleted (`deleteUnreferencedBiometricKeys`), except under the `KeyUnavailable` decision (DEC-86) |
| Erase (section 5.4) | Gone with the file | All deleted by `eraseState` |

- Hygiene: a `bioSlot` should exist only while the lock is on. One can be left behind with the lock off: a crash between the setting write `security.app_lock = false` and the removal (section 7.1, db-lifecycle.md CS-31), a removal that skipped its file rewrite because another operation held `opMutex`, or a `SettingsStore` reset (DEC-09); [db-lifecycle.md](db-lifecycle.md#key-state-semantics) tolerates it as "stale". `UnlockPolicy` never uses a slot while the lock is off. `AppLockController.reconcileLock()` removes it: when the lock is off and `biometricSlotStatus()` is neither `None` nor `Unknown`, it calls `BiometricSlotWriter.removeBiometricSlot()` (`DbMaintenance.removeBiometricSlot`, idempotent, accepted in every gate state, never `Busy`); failures are ignored. An orphan key without a slot holds nothing and is left to the next arming, startup reconciliation, removal, new-DEK commit or Erase (DEC-98). `reconcileLock()` runs only as the first step of `offerSlotSetup()` (effect E5 at T16 from a password open or a fingerprint-reset open, at T48, at T28a for a key change and at T57 after a fingerprint reset from `Locked(Ready)`, [architecture.md](architecture.md) § SessionGate). It also performs the `PASSWORD`-mode auto-off of section 7.6, writing the setting first:
  1. `mode = KeyVault.readState()?.slot?.mode`; an exception or no file ends it.
  2. `PASSWORD` mode, `security.app_lock` true and `!BiometricGateway.isDeviceSecure()`: one `SettingsStore` edit `app_lock = false, lock_auto_off_notice = true`, then `removeBiometricSlot()`. This runs only after a password entry, because E5 runs only after a password unlock (T16 from `Opening(viaPassword)`, T48), a committed key change from `Ready` (T28a), whose `PASSWORD`-mode source was authorized by a typed password, or a committed fingerprint password reset (T16 from `Opening(viaReset)`, T57), whose user has just typed the new password twice; so removing the screen lock never bypasses the password (DEC-65).
  3. `security.app_lock` false and `biometricSlotStatus()` neither `None` nor `Unknown`: `removeBiometricSlot()` (stale slot). A slot whose key cannot be looked up (`Unknown`, DEC-77) is left for a later reconciliation: nothing is deleted on an unknown answer.
- An unused biometric key (an arming prompt cancelled, or an orphan after a crash) holds nothing: it wraps no committed DEK. It is deleted by the next arming, removal, new-DEK commit or Erase.

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
| Reset password with fingerprint (section 4.3) | `PASSWORD` → `PASSWORD` | New password twice + checkbox, then one fingerprint through the biometric slot; never the current password | New raw key |

The current password is required for every row except the last (DEC-61). The biometric slot never authorizes any other key change: a fingerprint can replace a forgotten password (section 4.3) but cannot remove it, turn encryption off or change it while the old one is known to the user.

Before the two flows that set a first startup password (`PLAIN` → `PASSWORD`, `DEVICE` → `PASSWORD`), the UI offers a backup (DEC-73, [ui.md § 8.8](ui.md#88-encryption-and-startup-password-dec-61-dec-64)). The offer adds no check and no key handling to this section.

Preconditions (all rows except the fingerprint reset, whose preconditions are in section 4.3): `SessionGate` is `Ready` and no `DbMaintenance` operation runs. The biometric lock adds no prompt (the user is already past it). The entry point is `DbMaintenance.changeKey(target, currentPassword, newPassword)`; the arrays it receives are its own (section 3.6).
1. Collect the input in one dialog; password fields follow section 6. The dialog checks the new password with `PasswordPolicy.checkNewPassword` (section 6.3: both entries and the acknowledgement); `DbMaintenance`, which receives the single new `CharArray`, re-checks it with `PasswordPolicy.checkPassword` before anything else (DEC-93). A violation returns `AppError.InvalidInput(rule)` and stops the flow.
2. Source `PASSWORD`: verify the current password with `KeyVault.openKey(slot, passwordBytes(current))`. `PasswordRejected` maps to `AppError.WrongPassword`, shows inline, counts toward the backoff (section 5.3, except malformed or empty input, which never counts) and stops the flow. `Throttled` maps to `AppError.Throttled`: no PBKDF2 runs, and the dialog shows the countdown. Zero the returned raw key at once; the export uses the live key held by `DatabaseHolder`. A correct current password also refreshes the reminder timestamp (section 5.6), even if the flow later fails.
3. `KeyVault.prepare(target, newPassword)`: new DEK, KEK ensured, for `PASSWORD` calibration (section 3.3), derivation and both wraps, raw-key encoding, self-check (section 3.2). It zeroes the DEK and the password KEK before returning and persists nothing.
4. `DbMaintenance` exports from the live key to `rawKey`, verifies, and commits with `KeyVault.commit(newGeneration, slot, bio = null)` (through `commitGeneration`). Because the DEK is new, the file is written without `bioSlot` and `DbMaintenance` deletes every biometric key after the commit (`DELETE_BIO_KEY`, section 3.5); for a target `PASSWORD` slot built from a typed new password it also refreshes the reminder timestamp (section 3.6).
5. After the commit `DatabaseHolder` owns `rawKey` and zeroes the old live key once the old Room instance and the maintenance connection are closed. Exclusive mode itself never zeroes the live key (section 6.4), so the export source and the rollback reopen can use it. Target `PLAIN`: `KeyVault.deleteKek()`. Target `PASSWORD` with the biometric lock on: once the gate is back in `Ready` (T28a, which runs effect E5 for `KeyChange`), the re-creation prompt of section 7.8 follows (one fingerprint touch arms a slot for the new DEK). After any committed key change, `cacheDir/share/` is emptied: earlier share copies would otherwise stay readable in the form the user just replaced ([db-lifecycle.md](db-lifecycle.md#key-changes) step 14 and [Share-file lifetime](db-lifecycle.md#share-file-lifetime) list this trigger; DEC-83).
6. Any failure before the commit: zero `rawKey`, keep the old database, slot and `bioSlot`, show the error. A KEK created in step 3 stays and is reused next time.

- Turning encryption on states that existing backups and share copies are not changed; turning it off states that notes will be stored unencrypted (`enc_on_body`, `enc_off_body` in [ui.md](ui.md#1111-passwords-encryption-and-authentication-prompts)).
- A backup does not touch the key-state: it creates no generation and makes no commit ([db-lifecycle.md](db-lifecycle.md#backup)). A Restore started from `Ready` keeps the slot: the export target key is the live raw key (`DatabaseHolder.copyLiveKey()`) and the new generation commits the same key slot and the same `bioSlot`. Neither changes a setting of section 2, and a backup password never becomes the database password (DEC-83, DEC-84). Recovery-origin actions follow section 4.1.

### 4.1 Key handling of recovery actions
Restore and Start fresh are also offered on the Corrupt (DEC-85) and KeyUnavailable (DEC-86) screens, where Room is closed and the live key was zeroed (E3). Mechanics: [db-lifecycle.md](db-lifecycle.md#origins-and-target-key) and [db-lifecycle.md](db-lifecycle.md#start-fresh). Security handling:

| Action | Key configuration of the new generation | Key for the export target | Security side effects |
|---|---|---|---|
| Restore from `Corrupt`, mode `PLAIN` | Unchanged | `ByteArray(0)` | none |
| Restore from `Corrupt`, mode `DEVICE` | Unchanged slot | `KeyVault.openKey(slot, null)` again | A Keystore failure ends the restore with `AppError.KeyUnavailable`; the user stays on the Corrupt screen |
| Restore from `Corrupt`, mode `PASSWORD` | Unchanged slot; `bioSlot` kept | Fingerprint first (DEC-84, DEC-65) when the lock is on, a strong biometric is usable, a live `bioSlot` exists and the 14-day reminder is not due (the reminder counts as not due while `AppLockController`'s `kekBroken` flag is set, DEC-86, section 5.6). When the committed state has a `bioSlot`, `DbMaintenance` reports `NeedsBiometricUnlock`; `MaintenanceStatusHost` calls `AppLockController.slotPrompt(AuthPurpose.RestoreKey)`, which shows the slot prompt (DECRYPT `CryptoObject`, title `biometric_unlock_title`, negative button "Use password") only under those conditions and otherwise returns `Ok(null)` at once, and answers `DbMaintenance.answerBiometric(cipherOrNull)`; with a cipher, `KeyVault.unlockWithBiometric(cipher)` (`KeyFailed` → `AppError.KeyUnavailable`). Before the key is accepted it is checked against the old database (`SELECT count(*) FROM sqlite_master` on `doro-G.db`): `SQLiteNotADatabaseException` or a header mismatch is a slot failure (`deleteBiometricKey()`, password question); a missing or empty file cannot be checked and also leads to the password; the accepted key is the export target key and is zeroed as in section 6.4. Otherwise, or after "Use password" or any other non-success of `slotPrompt(RestoreKey)` (`Ok(null)`), or a slot failure, the database password: `openKey(slot, passwordBytes)`. A Keystore-layer failure there (`KeyFailed`) ends the restore with `AppError.KeyUnavailable` only without a live slot (section 5.1). With a live slot (mode `PASSWORD`, lock on, `Live`) it is the DEC-86 fallback: `DbMaintenance` reports `NeedsBiometricUnlock(afterKekFailure = true)`; the status host calls `AppLockController.reportPasswordKekFailure()`, which re-checks the live slot and sets the kekBroken notice and the `kekBroken` flag, then `slotPrompt(RestoreKey)`, which now prompts even when the reminder is due, and answers `answerBiometric(cipherOrNull)`. That fingerprint is taken exactly like the first one (unlock through the slot, slot-key check); this fallback runs at most once per restore, and anything other than an accepted key (`Ok(null)` for any reason, including no usable strong biometric at that moment, `SlotFailed`, `KeyFailed`, a failed or impossible slot-key check) then ends the restore with `AppError.KeyUnavailable`, because the password cannot work. A fingerprint key with `kekBroken = true` is accepted like any other; `DbMaintenance` does not report it (it never reaches `AppLockController`), and the next unlock through the slot after the restart reports it | The fingerprint path touches neither backoff nor reminder (a source that fails to open with NOTADB is a slot failure, section 3.7.3, and falls back to the password). The password path is a database-password check: counts toward the backoff, is subject to `Throttled` (section 5.3) and, when correct, refreshes the reminder timestamp (section 5.6). A KEK failure on it counts nothing (the Keystore layer runs before PBKDF2, section 3.6). A due reminder makes the restore ask the password first, like every other unlock, except while the `kekBroken` flag is set |
| Restore from `KeyUnavailable` | `PLAIN` slot (the current configuration cannot produce a key) | `ByteArray(0)` | `commit` of the `PLAIN` slot (with `bio = null`) resets the backoff values and `DbMaintenance` deletes the biometric key with `DELETE_BIO_KEY` (section 3.7.5); then `KeyVault.deleteKek()`; the confirmation states that encryption will be off |
| Start fresh from `Corrupt` or `KeyUnavailable` | Empty `PLAIN` database, `PLAIN` slot | `ByteArray(0)` | As the row above; the encryption-off notice follows (DEC-85) |

Every other setting of section 2 stays unchanged (L-12 in [testing.md](testing.md)). The biometric lock stays on after a switch to `PLAIN`; it then works as the `PLAIN`-mode prompt without a slot (section 7).

### 4.2 Biometric slot across key changes
- Every key change makes a new DEK (DEC-61), so the biometric slot is never carried over: the commit is called with `bio = null` and `DbMaintenance` deletes its key (`DELETE_BIO_KEY`, section 3.5). No key change re-wraps the new DEK with the old biometric key, because creating a slot always needs a fresh authorization through an ENCRYPT `CryptoObject`.
- A key change from `Ready` that ends in `PASSWORD` mode with the biometric lock on (`PLAIN` → `PASSWORD`, `DEVICE` → `PASSWORD`, change password) finishes with the re-creation prompt of section 7.8 as soon as the gate is back in `Ready` (T28a, E5). So does the fingerprint reset, which counts as the unlock (DEC-63): it ends in `Ready` from either origin, and the prompt follows at once (architecture.md T16 after `Opening(viaReset)`, T57; section 4.3). Only when the app went to the background while the reset ran does it end locked, and the prompt then follows the next password unlock (the new password). Until a slot is created, unlocking uses the password (cause `SlotMissing`, public reason `None`, section 5.1).
- `PASSWORD` → `DEVICE` and `PASSWORD` → `PLAIN` delete the slot and its key with the commit; the lock, if on, continues as the slot-less prompt of `DEVICE`/`PLAIN` mode with the device-credential fallback (Q7).
- A crash after the commit and before the re-creation prompt leaves `PASSWORD` mode with the lock on and no slot: the next unlock asks the password and then offers the prompt. Nothing extra is persisted for this.

### 4.3 Fingerprint password reset (DEC-63, DEC-61)
A `PASSWORD` → `PASSWORD` key change authorized by the biometric slot instead of the current password, for a user who has forgotten the password but still has fingerprint unlock.
- Offered as "Set a new password with your fingerprint" on the "Forgot password?" screen (section 5.7) only while the gate is `NeedsPassword` (the cold-start password screen) or `Locked(Ready)` with a password entry (the password screen over the open database), and only while fingerprint unlock is available: `AppLockController.fingerprintUnlockAvailable()` is `Available`, which is `UnlockPolicy.route(...)` evaluated with `reminderDue = false` returning `Slot` (`PASSWORD` mode, lock on, a usable strong biometric, `biometricSlotStatus()` = `Live`). Never over `Locked(Corrupt)`, `Locked(Maintenance)`, `Locked(KeyUnavailable)` or `Corrupt`, and never from the unlocked shell (DEC-63). The option is evaluated when the screen opens and again right before the prompt.
- Steps:
  1. The dialog ([ui.md § 8.15](ui.md#815-set-a-new-password-with-the-fingerprint-dec-63)) collects the new password twice and the acknowledgement checkbox; `PasswordPolicy.checkNewPassword` (section 6.3) runs first, so a policy violation never wastes a fingerprint. The current password is not asked.
  2. `AppLockController.slotPrompt(AuthPurpose.ResetPassword)` (section 7.3): it re-checks availability, calls `KeyVault.biometricUnlockCipher()` and shows the slot prompt with a DECRYPT `CryptoObject` (negative button: the cancel text). `Ok(null)` (cancel, not usable, cipher preparation `NoSlot`, `Invalidated` or `Unavailable`): the caller zeroes the new password, closes the dialog and re-evaluates availability, which withdraws the option when the slot is gone (the screen keeps Erase, section 5.4). `Err(AuthFailed)` (any other prompt error, or `cryptoMatches == false`): the same, plus an error message. Nothing is changed in either case.
  3. `Ok(cipher)`: the authorized `SlotCipher` and the new password go to `DbMaintenance.resetPasswordWithBiometric(origin, cipher, newPassword)` with `origin` = `NeedsPassword` (Room closed) or `Ready` (Room open), which owns both from then on (section 3.6). Its mechanics, steps and crash cases are [db-lifecycle.md](db-lifecycle.md#fingerprint-password-reset)'s; this section fixes the security rules:
     - The new password is checked again with `PasswordPolicy.checkPassword` (`Err(InvalidInput)`, DEC-93). The authorization is `KeyVault.unlockWithBiometric(cipher)` returning `Key` inside this same operation (`SlotFailed` → `Err(AuthFailed)`, `KeyFailed` → `Err(KeyUnavailable)`), all before `MaintenanceStarted`. Without it, nothing is prepared or exported, and nothing counts toward the backoff.
     - With Room closed (origin `NeedsPassword`), that raw key is the export source. Its `CHECK_SOURCE` open of the old database decides it: `SQLiteNotADatabaseException` or a header mismatch is a slot failure (section 3.7.3): `deleteBiometricKey()` runs, the operation ends with `Err(AuthFailed)` (outcome `Aborted`) and nothing is written; it does not claim the data is corrupt.
     - With Room open (origin `Ready`: the reset started from the password screen of `Locked(Ready)`, section 5.5), the export source is the live key; the slot's raw key must equal it (`constantTimeEquals` on the 67-byte encodings, DEC-93) or the operation ends with `Err(AppError.Unexpected)` and changes nothing. A GCM-verified slot DEK that differs from the live DEK breaks the invariant that the slot is armed from the live key of its generation: it is a bug, never a Keystore failure, so it is never `KeyUnavailable`. The slot's raw key is zeroed at once after the comparison.
     - If `unlockWithBiometric` reported `kekBroken = true`, the `prepare` of this operation checks the KEK again and, if it still fails, replaces it (section 3.1, DEC-78): the committed slot's outer layer is then unusable for the password, and the commit replaces that slot.
     - Then the steps 3–6 of section 4 with target `PASSWORD`: `prepare(PASSWORD, newPassword)`, export, verify, commit with `bio = null`. The commit resets the backoff values and refreshes the reminder timestamp (section 3.6); `DbMaintenance` then deletes every biometric key (`DELETE_BIO_KEY`) and the share directory is emptied. Origin `NeedsPassword` ends with the outcome `OpenWithNewKey`: `DbMaintenance` keeps the new raw key, and the gate opens the new generation with it in `Opening(viaReset)` (architecture.md T55, E2 through `DbMaintenance.takeResetKey`; the key never enters a gate state or event, DEC-64). Origin `Ready` ends with `Reopened` on the new generation (architecture.md T57). A failure before the commit ends with `Aborted` from either origin (from `Ready` only once Room has reopened on the old generation; a failed reopen or a corrupt export source is `ReopenFailed`, T14c, T14d); an unknown commit outcome with `Restart`.
  4. The reset counts as the unlock (DEC-63): the fingerprint has just authorized the slot and the user has typed the new password twice. While it runs (from `MaintenanceStarted` to `MaintenanceFinished`) the gate shows the progress screen and never prompts for the fingerprint, because the slot is about to be dropped: from `NeedsPassword` it is `Maintenance(KeyChange, NeedsPassword)`, from the password screen of `Locked(Ready)` it is `Locked(Maintenance(KeyChange, Ready))` with `resetFrom` set, for which `lockScreenOf` returns `Other`, so `AppLockController` arms no auto-prompt and raises no `PasswordRequired` (section 7.3; architecture.md § SessionGate). At the commit (T55 from `NeedsPassword`, T57 from `Locked(Ready)`, whether it then ends in `Ready` or locked) effect E6 calls `AppLockController.clearKekBroken()`: the reset replaced a broken KEK (above) or found it working, so the kekBroken notice and the `kekBroken` flag are cleared (DEC-86, section 7.3). On success the gate goes to `Ready` (T16 after `Opening(viaReset)`, T57) and E5 runs, so the re-creation prompt of section 7.8 follows at once and fingerprint unlock works again for the new DEK after one touch. If a `LockNow` arrived while the reset ran (T53, T54), the success ends locked instead (`Locked(Ready)`), the next unlock asks the new password (cause `SlotMissing`), and the re-creation prompt follows that password unlock. On failure the gate returns to the password screen the reset started from (T56, T58), with the old password and, unless the slot-released key was rejected, the old slot still in force; the failure dialog appears there (over a locked password screen in the lock mode of [ui.md § 8.12](ui.md#812-maintenance-status-host), DEC-66).
  5. While the reset runs behind the locked password screen, "Forgot password?" and with it Erase are hidden (DEC-63): the progress screen offers neither, and no second reset or Erase can start (`opMutex` would refuse it with `Busy` anyway).
- The reset is not a password attempt: it neither counts toward nor waits for the backoff, so it stays available during a countdown (section 5.3). Repeated fingerprint failures are limited by the system's own lockout (codes 7 and 9, section 7.4).
- The erase path (section 5.4) stays available on the same screen in every case.

## 5. Unlock, errors and erase

### 5.1 Cold start per mode
States and transitions: [architecture.md](architecture.md) § SessionGate. All key work happens in `Opening` (DEC-90). The unlock method is chosen by `UnlockPolicy.route` (below): effect E1 evaluates it for the cold start (through `AppLockController.unlockRoute()`), and `AppLockController` evaluates it again right before every prompt, because availability, enrollment and the clock can change in between.

"Strong biometric usable" means `canAuthenticate(BIOMETRIC_STRONG)` returns `BIOMETRIC_SUCCESS` or `BIOMETRIC_STATUS_UNKNOWN` (section 7.4) and `KeyguardManager.isDeviceSecure()` is true.

| Mode | Lock off | Lock on, strong biometric usable | Lock on, no usable strong biometric |
|---|---|---|---|
| `PLAIN` | `Opening` with key `ByteArray(0)` | `Locked`: strong prompt without `CryptoObject`, then `Opening` | `Locked`: device-credential fallback (Q7, section 7.6), then `Opening` |
| `DEVICE` | `Opening`: Keystore layer → DEK → raw key | As `PLAIN`; the Keystore layer runs in `Opening` | As `PLAIN` |
| `PASSWORD` | Startup password (`NeedsPassword`), then `Opening`: Keystore layer → PBKDF2 → inner GCM → DEK → raw key | Fingerprint first: `Locked` with the slot prompt (DECRYPT `CryptoObject`, negative button "Use password"); `Opening` unwraps the DEK from the biometric slot (section 3.7.3). The password instead when there is no slot (`SlotMissing`), the slot is dead (`SlotInvalidated`), its key could not be looked up (`SlotUnknown`, DEC-77) or it is unavailable (`SlotUnavailable`), the 14-day reminder is due (`Reminder`, section 5.6), or the user taps "Use password" (`UserChose`) | Startup password alone (`NoBiometric`); never the device credential. This includes a device without a secure lock screen: the password is asked, and the auto-off of section 7.6 runs only after it |
| Any mode, gate `Locked(KeyUnavailable)` (also `Locked(Maintenance)` with origin `KeyUnavailable` or operation `Erase`) | n/a | The strong prompt without `CryptoObject` (the key cannot be obtained, so no slot or password check can run) | Device-credential fallback (Q7); auto-off without a secure lock screen |

Over `KeyUnavailable` (`Locked(KeyUnavailable)`, and `Locked(Maintenance)` whose origin is `KeyUnavailable`; the lock screen `PromptWithoutKey`) no key can be obtained and the key-state may be invalid, so the mode is not even known. The route there is the biometric route with the Q7 fallback in every mode, evaluated by `UnlockPolicy.routeOverKeyUnavailable`. `PasswordRequired` is never raised there: a password check cannot run, and without this rule a `PASSWORD`-mode user would be locked out of the screen that offers Restore and Start fresh (the event would be dropped by T35 and every "Unlock" tap would repeat the drop). This is the only `PASSWORD`-mode exception to "never the device credential", and it is safe: the device credential unlocks no data, because none is readable, and the actions behind it (Restore, Start fresh) are the ones DEC-86 offers to anyone who holds the phone and passes the lock. The same route applies over `Locked(Maintenance(Erase, …))`, whose key-state may already be deleted ([architecture.md](architecture.md) § SessionGate, `lockScreenOf`: `PromptWithoutKey` for a `Maintenance` resume whose origin is `KeyUnavailable` or whose operation is `Erase`); unlocking there only resumes the progress screen.

```kotlin
// package com.doro.organizer.model: the public route, owned by this section; architecture.md's gate states and events carry it.
enum class PasswordReason { None, Reminder, FingerprintChanged, KeyStoreFailed }    // the one-line reason of the password screen (texts: ui.md § 10.2); KeyStoreFailed: DEC-86, below
sealed interface UnlockRoute {
    data object Biometric : UnlockRoute                              // strongPrompt, no CryptoObject; the device credential is its fallback (Q7)
    data object Slot : UnlockRoute                                   // PASSWORD: slotPrompt with a DECRYPT CryptoObject (section 3.7.3)
    data class Password(val reason: PasswordReason, val fingerprintUsable: Boolean) : UnlockRoute {
        override fun toString() = "Password(reason=$reason, fingerprintUsable=$fingerprintUsable)"   // explicit for RedactionRule (DEC-70)
    }
}

// package com.doro.organizer.security; pure, JVM-tested (UnlockPolicyTest, one case per table cell and cause)
/** The finer causes behind a Password decision. Internal: tests and the mapping below use them; the gate sees only PasswordReason. */
internal enum class PasswordCause { LockOff, NoBiometric, SlotMissing, SlotInvalidated, SlotUnknown, SlotUnavailable, Reminder, UserChose }

internal sealed interface PolicyDecision {
    data object None : PolicyDecision                // no lock: a cold start goes to Opening, a return from background shows the content
    data object AutoOff : PolicyDecision             // PLAIN/DEVICE, lock on without a secure lock screen: section 7.6 turns it off. Never returned for PASSWORD mode
    data object Biometric : PolicyDecision           // PLAIN/DEVICE: strongPrompt, no CryptoObject
    data object DeviceCredential : PolicyDecision    // PLAIN/DEVICE fallback (Q7)
    data object Slot : PolicyDecision                // PASSWORD: slotPrompt with a DECRYPT CryptoObject (section 3.7.3)
    data class Password(val cause: PasswordCause) : PolicyDecision
}

object UnlockPolicy {
    internal fun route(mode: KeyMode, coldStart: Boolean, lockOn: Boolean, deviceSecure: Boolean,
                       biometric: BiometricAvailability, bio: BioSlotStatus, reminderDue: Boolean): PolicyDecision {
        val usable = deviceSecure && (biometric == BiometricAvailability.Success || biometric == BiometricAvailability.StatusUnknown)
        if (mode != KeyMode.PASSWORD) return when {
            !lockOn -> PolicyDecision.None
            !deviceSecure -> PolicyDecision.AutoOff
            usable -> PolicyDecision.Biometric
            else -> PolicyDecision.DeviceCredential
        }
        return when {
            !lockOn -> if (coldStart) PolicyDecision.Password(PasswordCause.LockOff) else PolicyDecision.None
            !usable -> PolicyDecision.Password(PasswordCause.NoBiometric)      // includes !deviceSecure: never AutoOff here, see below
            bio == BioSlotStatus.None -> PolicyDecision.Password(PasswordCause.SlotMissing)
            bio == BioSlotStatus.Dead -> PolicyDecision.Password(PasswordCause.SlotInvalidated)
            bio == BioSlotStatus.Unknown -> PolicyDecision.Password(PasswordCause.SlotUnknown)
            reminderDue -> PolicyDecision.Password(PasswordCause.Reminder)
            else -> PolicyDecision.Slot
        }
    }

    /** Over Locked(KeyUnavailable): no key, possibly no valid key-state, so no mode. Every mode behaves like PLAIN/DEVICE. */
    internal fun routeOverKeyUnavailable(lockOn: Boolean, deviceSecure: Boolean, biometric: BiometricAvailability): PolicyDecision =
        route(KeyMode.PLAIN, coldStart = false, lockOn, deviceSecure, biometric, BioSlotStatus.None, reminderDue = false)

    /** The public route. Everything that is not Slot or Password is the Biometric route (architecture.md: the device credential and the auto-off
     *  are handled by AppLockController at prompt time, and E1 ignores the route for PLAIN/DEVICE, where it is Biometric by definition).
     *  kekFailedWithLiveSlot: AppLockController's kekBroken flag is set and biometricSlotStatus() is Live (DEC-86, below); default false. */
    internal fun toRoute(d: PolicyDecision, kekFailedWithLiveSlot: Boolean = false): UnlockRoute = when (d) {
        PolicyDecision.Slot -> UnlockRoute.Slot
        is PolicyDecision.Password -> when (d.cause) {
            PasswordCause.Reminder -> UnlockRoute.Password(PasswordReason.Reminder, fingerprintUsable = false)
            PasswordCause.SlotInvalidated -> UnlockRoute.Password(PasswordReason.FingerprintChanged, fingerprintUsable = false)
            PasswordCause.SlotUnavailable, PasswordCause.UserChose -> UnlockRoute.Password(PasswordReason.None, fingerprintUsable = true)
            PasswordCause.NoBiometric ->
                UnlockRoute.Password(if (kekFailedWithLiveSlot) PasswordReason.KeyStoreFailed else PasswordReason.None, fingerprintUsable = false)
            PasswordCause.LockOff, PasswordCause.SlotMissing, PasswordCause.SlotUnknown -> UnlockRoute.Password(PasswordReason.None, fingerprintUsable = false)
        }
        else -> UnlockRoute.Biometric
    }
}
```
- Mapping rules (the public route is what the gate and the password screen see):

| Internal cause | Public `PasswordReason` | `fingerprintUsable` | Produced by |
|---|---|---|---|
| `LockOff`, `NoBiometric`, `SlotMissing` | `None` | false | `route` |
| `NoBiometric` while `kekFailedWithLiveSlot` (the `kekBroken` flag of section 7.3 is set and the slot is `Live`, DEC-86) | `KeyStoreFailed` | false when produced; set to true by the ON_START re-check of section 7.3 once the route would be `Slot` again (DEC-86, architecture.md T61, T62) | `route` (a strong biometric not usable), and the prompt errors 1, 9, 11, 12, 15 on the slot route (section 7.4) |
| `SlotInvalidated` | `FingerprintChanged` | false | `route` (a dead slot), and the slot path (3.7.4: tag failure, repeated failure, open failure through the slot) |
| `SlotUnknown` | `None` | false: the key of the slot could not be looked up, so nothing claims that the fingerprints changed (DEC-77) | `route` (the alias check failed twice) |
| `Reminder` | `Reminder` | false: the route that is not a voluntary choice offers no "Use fingerprint" | `route` |
| `SlotUnavailable` | `None` | true: the slot is kept and may work on the next try, so "Use fingerprint" returns to the prompt | the slot path (cipher preparation failed once) |
| `UserChose` | `None` | true | "Use password" (`AppLockController.usePassword()`, the negative button), never `route` |

- `fingerprintUsable` is true only for the two causes the user or a transient failure produced while the slot is still good; it is false for `Reminder` and for every route that `route` returns, so the reminder screen never offers a way around itself. One later change exists (DEC-86): on a password screen with the reason `KeyStoreFailed`, the ON_START re-check of section 7.3 sets it to true when a strong biometric has become usable again and the route would be `Slot`, and back to false when not. `UnlockPolicy.toRoute` itself is unchanged; the re-check reaches the gate as its own event (`FingerprintRechecked`, architecture.md T61, T62).
- Live slot (DEC-86). "A live biometric slot" means exactly: the committed mode is `PASSWORD`, the lock is on (`security.app_lock` true) and `KeyVault.biometricSlotStatus()` is `Live` (a `bioSlot` in the key-state and its own key, `bioSlot.keyAlias`, present, DEC-98; no Keystore operation). A stale slot left behind with the lock off (section 3.7.5) does not count, nor does a `Dead` or `Unknown` slot or none. Whether a strong biometric is usable at that moment is not part of the definition: it decides only whether the fingerprint prompt can run. Every DEC-86 fallback uses this definition: `AppLockController.reportPasswordKekFailure()` (section 7.3), the restore fallback of section 4.1, and the `KeyStoreFailed` reason below.
- Broken KEK (DEC-86): `AppLockController.unlockRoute()` passes `reminderDue = false` to `UnlockPolicy.route` while its in-memory `kekBroken` flag is set (section 7.3: set by every `kekBroken` slot result and by a `reportPasswordKekFailure()` that returns true), so a due reminder never sends the user to a password that cannot work; with the lock on, a usable strong biometric and a `Live` slot the route is then `Slot`. The flag lives until a successful password unlock or a successful fingerprint reset clears it (`AppLockController.clearKekBroken()`, section 7.3) or the process ends, so after a KEK failure the reminder is ignored for that time (DEC-86), by `unlockRoute()` and by `slotPrompt(RestoreKey)` alike (section 7.3). `UnlockPolicy.route` itself stays pure and unchanged (`UnlockPolicyTest` covers the input, the controller test the flag).
- Key store failed line (DEC-86): with a live slot but no usable strong biometric at that moment, the password cannot work (its KEK failed) and the fingerprint cannot be asked, so the password screen explains why. While the `kekBroken` flag is set and `biometricSlotStatus()` is `Live`, `AppLockController` passes `kekFailedWithLiveSlot = true` to `UnlockPolicy.toRoute`, which maps the cause `NoBiometric` to `Password(KeyStoreFailed, fingerprintUsable = false)`; the same reason replaces `None` when the controller raises `PasswordRequired` for the prompt errors 1, 9, 11, 12 and 15 on the slot route (section 7.4). The password screen (cold start `NeedsPassword`, and the password entry of `Locked(Ready)` or `Locked(Corrupt)`) shows it as its one-line reason: the device key store failed, and the fingerprint (once it is usable again) or a restore from a backup is the way back ([ui.md](ui.md) owns the text). Every other cause keeps its reason: `SlotUnavailable` and `UserChose` keep `None` with "Use fingerprint", and a `Dead` slot keeps `FingerprintChanged` (the slot is no longer live). "Forgot password?" with Erase stays available on that screen; the fingerprint reset is offered only while `fingerprintUnlockAvailable()` is `Available`, which it is not without a usable biometric. The screen does not stay a dead end once the biometric is back (DEC-86): whenever it is shown again (process ON_START) `AppLockController` re-checks the route, and as soon as a strong biometric is usable (route `Slot`) the screen offers "Use fingerprint" (`fingerprintUsable = true`, section 7.3, architecture.md T61, T62), which leads to the slot prompt (T51, T52); "Forgot password?" then offers the fingerprint reset too, because `fingerprintUnlockAvailable()` is evaluated when that screen opens. Tests: L-52, L-53 and I-54 in [testing.md](testing.md); the restore fallback: L-51.
- A `PASSWORD`-mode device without a secure lock screen is never `AutoOff`, because that would be a password bypass: someone who knows the phone PIN could remove the screen lock in system Settings while Doro sits in the background with its database open and reopen it without any check. In `PASSWORD` mode `!deviceSecure` is `Password(NoBiometric)` on a cold start and on a return from background alike. The auto-off writes happen only after the password was verified (section 7.6).
- Gate wiring (requirements on [architecture.md](architecture.md) § SessionGate, which owns the states, events and rows): in `PASSWORD` mode a cold-start `Slot` route enters `Locked(Open)` (T40) instead of `NeedsPassword`; a successful slot prompt carries the authorized `SlotCipher` into `Opening` (`SlotUnlocked`, T42), where E2 calls `KeyVault.unlockWithBiometric(cipher)` instead of `unlock(password)`; a cold-start `Password` route enters `NeedsPassword` with the reason (T2, or T45 from the lock screen), and over the open database the password entry of `Locked(Ready)` (T46). The password screen shows the reason as one line ([ui.md](ui.md) owns the texts). In `PASSWORD` mode the password and the fingerprint are two ways through one gate; a password entry is never followed by a second, biometric gate.
- The slot path never touches the backoff values or `passwordVerifiedAt`. A transient open failure after the slot path needs a new prompt, because the cipher is single use (the state it returns to is architecture.md's).

Password path (every `Password` route and `PASSWORD` mode with the lock off):

1. On submit, the gate UI copies the field into a `CharArray`, clears the field and dispatches `PasswordSubmitted(chars)` (T7, only while no backoff runs). Effect E2 (package `session`) owns the array: it calls `passwordBytes` (section 6.3), passes the bytes (or `ByteArray(0)` when `passwordBytes` returned null) to `KeyVault.unlock`, and zeroes the chars and the bytes in its `finally`. The screen shows progress.
2. `Opening` removes the Keystore layer, derives with the stored salt and iterations, and decrypts the inner layer. An `AEADBadTagException` there is WrongPassword: `PasswordRejected` (T21), back to `NeedsPassword` with the error.
3. On success: encode the raw key, zero the DEK, the password KEK and the password bytes, reset `security.unlock_failures` and `security.unlock_retry_at` to 0 and set `security.password_verified_at` to `AppTime.nowMillis()` (all inside `KeyVault`, one `SettingsStore` edit, best effort: a failing write never fails a correct password, section 3.6); E2 calls `AppLockController.clearKekBroken()`, because the password has just passed the KEK (DEC-86, section 7.3); and open the database with `DatabaseHolder.open(generation, rawKey)`, which takes ownership of the key. After `Opened` the gate enters `Ready` (T16) and effect E5 runs (`offerSlotSetup()`: `reconcileLock()` first, section 3.7.5). If the lock is on, a strong biometric is usable and the slot is not live (`biometricSlotStatus()` is `None` or `Dead`, causes `SlotMissing` and `SlotInvalidated`), the re-creation prompt of section 7.8 follows in `Ready`.
4. The inner GCM tag proves the DEK, so no later open failure is reported as a wrong password or counted toward the backoff. A Corrupt classification goes to the Corrupt screen (T17). After a `viaPassword` open (Password mode) a transient failure returns to `NeedsPassword(couldNotOpen)` with the could-not-open message, not the wrong-password message (T18), because E3 zeroed the key and the password bytes are gone, so the password must be typed again; `unlock_failures` stays 0. In `PLAIN` and `DEVICE` mode a transient failure stays on the open-error screen with "Try again" (T19, T20).
5. A Keystore-layer (KEK) failure in step 2 is KeyUnavailable (section 5.2) except when the biometric slot is live: in `PASSWORD` mode with the lock on and `biometricSlotStatus() == Live`, E2 calls `AppLockController.reportPasswordKekFailure()`, which sets the kekBroken notice and the `kekBroken` route flag and returns true, and E2 raises `KekFailedWithSlot` instead of `KeyFailed` (DEC-86; architecture.md T59). The gate goes to `Locked(Open)` and the slot prompt follows at once, even when the 14-day reminder is due; the slot unwraps the DEK without the KEK (section 3.7.3), the data opens, and the shell shows the kekBroken notice in `Ready`. Nothing counts toward the backoff and nothing is deleted. If the fingerprint cannot be used at that moment (no usable strong biometric, the slot found invalidated when its cipher is prepared), the route raises `PasswordRequired` and the password screen returns; it never becomes KeyUnavailable while the slot is live, and "Forgot password?" with Erase stays available. With no usable strong biometric and the slot still `Live`, that password screen carries the reason `KeyStoreFailed` (the key store line above); a slot found invalidated is no longer live and carries `FingerprintChanged`.

### 5.2 Error classes
| Class | Raised when | Result |
|---|---|---|
| WrongPassword | `AEADBadTagException` on the inner (password) layer. Any other exception on the password layer is KeyUnavailable | `NeedsPassword` with an error; counts for backoff; never Corrupt; never deletes |
| Slot failure | Any failure of the biometric key or the biometric slot (section 3.7.4): invalidation, a missing alias, a Keystore error, a tag failure on `bioSlot`, a malformed `bioSlot` member, an open through the slot that fails with `SQLiteNotADatabaseException` or a header/HMAC mismatch (3.7.3) | Password route (or the lock with an error); never KeyUnavailable, never Corrupt, never counts for backoff, never deletes; the password slot stays usable (unless its KEK fails, which a fingerprint unlock reports as `kekBroken`, 3.7.3) |
| KeyUnavailable | The Keystore layer fails: a missing alias; any `java.security.GeneralSecurityException` (`KeyStoreException`, `UnrecoverableKeyException`, `InvalidKeyException` incl. `KeyPermanentlyInvalidatedException`, `IllegalBlockSizeException`, `BadPaddingException` incl. `AEADBadTagException`, `InvalidAlgorithmParameterException`, `NoSuchAlgorithmException`, `NoSuchProviderException`, `CertificateException`), `java.security.ProviderException` (incl. `StrongBoxUnavailableException`) or `java.io.IOException` raised while loading the KeyStore, fetching the alias or running the outer cipher; an outer plaintext that is not 32 bytes (`DEVICE`) or 48 bytes (`PASSWORD`). Also: the key-state file is invalid (section 3.5) or unreadable, or missing next to a database file that is not plaintext ([db-lifecycle.md](db-lifecycle.md#startup-reconciliation)) | KeyUnavailable screen (DEC-86). Exceptions: a Keystore failure while checking a password over an open database is a failed check (section 5.5, T50); and in `PASSWORD` mode with the lock on and a `Live` biometric slot, a Keystore-layer failure during a password unlock (cold start or return from background) falls back to the fingerprint route with the kekBroken notice, also when the reminder is due (DEC-86; sections 5.1 step 5, 5.5); with the same live slot, a Keystore-layer failure of the database-password question of a Restore from `Corrupt` asks for the fingerprint instead of ending the restore with `AppError.KeyUnavailable` (DEC-86, section 4.1) |
| Corrupt | Open or `quick_check` fails with a proven key (`PLAIN`: the empty key; `DEVICE` and `PASSWORD`: a DEK verified by the outer or inner GCM tag of the main slot), or `SQLiteDatabaseCorruptException` at runtime. A DEK released by the biometric slot is authentic but not proven: an open that fails through it with `SQLiteNotADatabaseException` is a slot failure (row above), and only a failure after a password unlock, or after a successful open, is Corrupt | Corrupt screen (DEC-85) |

- The classification is by exception class, never by message. AndroidKeyStore reports most operation failures from `doFinal` as `IllegalBlockSizeException` (cause `KeyStoreException`) or `BadPaddingException`, so the list is the whole `GeneralSecurityException` family, not a selection: an unlisted exception would crash-loop the app at every cold start, before any recovery screen can show ([architecture.md](architecture.md) § Error model).
- `KeyVault` retries a Keystore-layer exception once after 1 s (`delay(1000)` on `dispatchers.io`), for every item except `AEADBadTagException` and a wrong plaintext length, which are never retried. A retry is not an attempt: it never touches the backoff. The retry covers the unwrap of `unlock`, `openKey` and `verifyPassword` and every KEK step of `prepare` (alias check, generation, delete, the wrap with its self-check, section 3.2); a self-check mismatch is not an exception and is not retried (DEC-76).
- The password layer (PBKDF2 and the inner GCM, in `openKey` and in `prepare`, calibration included) is classified by layer, not by exception class (DEC-76): `AEADBadTagException` is WrongPassword, and every other `Exception` there is KeyUnavailable (`KeyFailed` from `unlock`, `openKey` and `verifyPassword`, `KeyVaultException(KeyUnavailable)` from `prepare`), never counted and logged by class only. The programming-error rule below applies outside this layer.
- A damaged inner IV, salt or iteration count of a `PASSWORD` slot fails the outer tag (section 3.1) and is therefore KeyUnavailable, never WrongPassword, and leaves `unlock_failures` unchanged.
- A programming error (`IllegalStateException`, `NullPointerException`, ...) is not classified: it crashes ([architecture.md](architecture.md) § Error model).

Full classification, including `SQLiteNotADatabaseException`, missing files and transient I/O: [db-lifecycle.md](db-lifecycle.md#failure-classification).

### 5.3 Backoff (DEC-62)
- Counts consecutive WrongPassword results (`AEADBadTagException` on the inner layer) of every database-password check: the cold-start password, the password asked on return from background (section 5.5, `KeyVault.verifyPassword`), the password of `AppLockController.confirmWithPassword` (turning the lock on, turning it off without a fingerprint, Settings "Set up", section 7.1), the current-password check of section 4, and the startup-password question of a Restore from the Corrupt screen in `PASSWORD` mode when the fingerprint is not used (section 4.1, [ui.md § 8.7](ui.md#87-restore-dec-84)).
- Fingerprints never touch the counters: a slot unlock neither counts nor resets them, and the system limits failed fingerprints itself (codes 7 and 9, section 7.4). While a countdown runs, the fingerprint route (section 5.1) and the fingerprint reset (section 4.3) stay available; a completed reset installs a new key slot and so resets both values. Malformed or empty input is answered with the wrong-password message but never counted (section 6.1). Backup passwords at restore are not throttled (the file can be attacked offline anyway).
- Schedule: after failure n ≥ 5 the delay is min(30 s × 2^(n−5), 300 s): 30, 60, 120, 240, then 300 s. Failures 1–4 have no delay. Pure function `UnlockBackoff.delayMillis(failures)` in package `model`.
- Time base: `AppTime.elapsedMillis()` (elapsed realtime), so moving the system clock changes nothing (A1). `AppError.Throttled.retryAtElapsedMs`, `UnlockResult.retryAtElapsedMs`, `NeedsPassword.retryAt` and `unlockRetryAtElapsedMs` all carry this base.
- Persist `unlockFailures` and `unlockRetryAtElapsedMs = AppTime.elapsedMillis() + delayMillis(failures)` (0 when the delay is 0) before the password is checked (DEC-75), so killing the app, even during the derivation, does not reset them. After a wrong password whose count has a delay, the delay is computed again from the end of the check and saved (best effort), so the derivation never shortens it; if that write fails, the pair saved before the check binds and is the answer. A correct password resets both; any other failure of the check (KeyUnavailable, section 5.2) writes the pair from before the attempt back, best effort, so it never counts.
- Reboot: elapsed realtime restarts at boot. Within one boot `retryAt − now` never exceeds `delayMillis(failures)`; if it does, the device has rebooted and the value is replaced by `now + delayMillis(failures)`. A reboot therefore re-applies the full delay. `UnlockBackoff.effectiveRetryAt(failures, storedRetryAt, now)` (package `model`, pure) implements this; effects E1 and E2 pass the stored pair through it before they put `retryAt` into the gate state, so the password screen shows the effective value, and `KeyVault` writes it back whenever it reads the pair and the value differs.
- While `elapsedMillis() < retryAt`: the password field and the submit button are disabled and a countdown shows ([ui.md](ui.md#102-password-screen)); `KeyVault` returns `Throttled` without PBKDF2 to every caller, including the section 4 current-password check and the Restore-from-Corrupt password question; "Forgot password?" stays available. After a restart the password screen reads the pair from `SettingsStore`, so the countdown is shown at once.
- Both values reset on a correct password (best effort when the write fails, section 3.6), on every `KeyVault.commit` that installs a new key configuration and on Erase; a commit that keeps the key slot (every biometric slot commit, a Restore that keeps the slot) leaves them alone. If the counting write itself fails, `KeyVault` checks no password and returns `StorageFull` with the backoff unchanged (section 3.6, DEC-75); the password screen says that storage is full. Attempts are never capped and never wipe data.

### 5.4 Forgotten password: Erase (DEC-63)
- "Forgot password?" opens the screen of section 5.7. When fingerprint unlock is available it first offers "Set a new password with your fingerprint" (section 4.3); when the fingerprint slot could not be checked (`Unknown`, DEC-77) it says so and to try again later; otherwise it states that there is no recovery without a backup (§8). In every case it offers the button "Erase all data and start over", which is always enabled ([ui.md](ui.md#103-forgot-password-screen-dec-63)). The button opens a typed-confirmation dialog; the dialog's confirm button enables only while the typed text matches ([ui.md](ui.md#810-erase-all-data-dec-63)). No password or biometric is required: the action equals the system "Clear storage".
- The flow runs as `DbMaintenance.erase(origin)` with origin `NeedsPassword` (T8), `Ready` (the password screen of `Locked(Ready)`, T12; Room is closed first in exclusive mode) or `Corrupt` (the password screen of `Locked(Corrupt)`, T12); a holder state that does not match the origin ends it with `Err(DatabaseUnavailable)` before anything changes. Mechanics and the crash matrix: [db-lifecycle.md](db-lifecycle.md#erase-forgotten-password).
1. Delete every generation file set except the committed one `G` (`DELETE_OLD`, [db-lifecycle.md](db-lifecycle.md#erase-forgotten-password) step 2). If any of them remains, the Erase ends with `Err(DatabaseUnavailable)` and nothing was erased (outcome `Aborted` from `NeedsPassword` or `Corrupt`, `Reopened` from `Ready`): a leftover plaintext generation would otherwise be adopted at the next start and bring the erased notes back.
2. `KeyVault.eraseState()`: delete `db-state.json`, which makes the encrypted data unreadable at once, then the KEK and every biometric key, the committed slot's first (read before the file goes) (failed deletes are ignored).
3. Delete every `doro-*` database file, sidecar and staging or share leftover, and the data-reset marker (`DbFiles.deleteEverything(G)`, `G` last, [db-lifecycle.md](db-lifecycle.md#files-on-disk)).
4. `SettingsStore.resetSecurity()` (every key of section 2 to its default, both remembered ids cleared), then `SettingsStore.onDataReplaced()` ([architecture.md](architecture.md) § Data reset), so navigation drops ids of the erased database.
5. `MaintenanceFinished(Restart)`: `SessionGate` restarts in the first-run state (T30 from `Maintenance`, T15 from `Locked(Maintenance)`): an empty `PLAIN` database, with Restore available in Settings. While it runs, an Erase shows the progress screen whatever its origin, never the content; a lock over it is the `PromptWithoutKey` route of section 5.1.
- Crash points converge. Before step 2 (`db-state.json` still there): the password screen, nothing changed (step 1 deleted only unreferenced generations). Between steps 2 and 3: encrypted files without a key-state file, so the next start shows KeyUnavailable (DEC-86), whose "Start fresh" deletes the files; the settings of step 4 are then not reset except for the two backoff values, which that commit resets (section 3.6). Between steps 3 and 4: startup reconciliation finds a first run and completes the settings reset ([db-lifecycle.md](db-lifecycle.md#startup-reconciliation) step 7).
- Erase is offered on every startup-password screen (DEC-63): `NeedsPassword`, and `Locked(Ready)` or `Locked(Corrupt)` with a password entry. It is not offered while the resume is a `Maintenance` (the field is disabled there and "Forgot password?" is hidden). The security rules above apply unchanged for every origin.

### 5.5 Return from background
On process ON_STOP with the lock on, `AppLockController` raises `LockNow` (section 7.7); `Ready` becomes `Locked(Ready)` and Room stays open. The unlock method is `UnlockPolicy.route(..., coldStart = false, ...)`, evaluated right before the prompt:

| Mode | Lock off | Lock on, strong biometric usable | Lock on, no usable strong biometric |
|---|---|---|---|
| `PLAIN`, `DEVICE` | Nothing is asked | Strong prompt without `CryptoObject` | Device-credential fallback (Q7, section 7.6) |
| `PASSWORD` | Nothing is asked (DEC-62) | Slot prompt with a DECRYPT `CryptoObject`; on success `KeyVault.verifyBiometric(cipher)` proves the slot, zeroes the DEK and `Unlocked` follows. The password instead in the cases of section 5.1 (no slot, dead slot, unavailable, reminder due, "Use password") | The startup password alone, checked with `KeyVault.verifyPassword`; this includes a device that has lost its secure lock screen (never auto-off here, 7.6) |

- The slot is used even though the database is already open, so that a fingerprint enrolled while the app was in the background (by someone who knows the device PIN) cannot unlock the running session: enrollment invalidates the key and the password is asked.
- The password check on return: the screen follows section 6.2; the gate UI hands the `CharArray` to the effect that verifies it (ownership as section 3.6); `KeyVault.verifyPassword` counts toward the backoff, honors `Throttled`, and on success resets the backoff and refreshes the reminder timestamp; on `Verified` effect E4 also calls `AppLockController.clearKekBroken()` (the password has just passed the KEK, DEC-86, section 7.3). The state, event and effect names are [architecture.md](architecture.md) § SessionGate's. The content stays out of composition until `Unlocked` (DEC-66).
- `KeyFailed` from `verifyPassword` while the database is open (the Keystore layer failed): the data was readable a moment ago, so this path never closes an open database or goes to `KeyUnavailable`. E4 first calls `AppLockController.reportPasswordKekFailure()`. With the lock on and a `Live` biometric slot it returns true (it has set the kekBroken notice and the `kekBroken` route flag), and E4 raises `KekFailedWithSlot`: the password entry is dropped (architecture.md T60) and the slot prompt follows at once, even when the 14-day reminder is due (DEC-86); `verifyBiometric` then unlocks without the KEK (section 3.7.3) and the shell shows the kekBroken notice in `Ready`. Without a live slot E4 raises `KeyFailed` and T50 keeps the gate `Locked(Ready)` with the password entry marked `checkFailed` (the screen shows the not error-styled message `gate_password_check_failed`); "Use fingerprint" (while `fingerprintUsable`) and "Forgot password?" stay available. The same applies over `Locked(Corrupt)`.
- After a successful password on return (`PasswordVerified`, T48, effect E5) with the lock on, a usable biometric and a slot that is not live, the re-creation prompt of section 7.8 follows. After a successful password with the lock on and `PASSWORD`-mode auto-off due (no secure lock screen), the auto-off of 7.6 runs (E5, `reconcileLock()`).
- "Use fingerprint" (only while `fingerprintUsable`) returns to the lock screen and re-arms the prompt (T52). "Use password" on the lock screen (`AppLockController.usePassword()`) opens this password entry with `fingerprintUsable = true`.
- "Forgot password?" is shown on this screen too and leads to section 5.7.

### 5.6 Password reminder (DEC-74)
Keeps a rarely typed startup password remembered while fingerprint unlock is in daily use.
- Scope: only where the route would otherwise be `Slot` (`PASSWORD` mode, lock on, usable strong biometric, a live `bioSlot`). Everywhere else the password is either asked anyway or does not exist. The same scope applies to the fingerprint-first choice of a Restore from `Corrupt` (section 4.1). While `AppLockController`'s `kekBroken` flag is set (a KEK failure was seen in this process with a live slot, and no successful password unlock or fingerprint reset has cleared it since, section 7.3) the reminder is ignored (DEC-86): the password could not be verified anyway. Once a password unlock succeeds or a fingerprint reset commits, `clearKekBroken()` clears the flag and the reminder applies again. For the reminder the flag reaches exactly two places (its third use, the `KeyStoreFailed` reason, is in section 5.1 and its full scope in section 7.3): `unlockRoute()` (the cold-start and return-from-background route, section 5.1), and `slotPrompt(RestoreKey)` (the fingerprint-first key of a Restore from `Corrupt`, sections 4.1 and 7.3), which then prompts on a live slot with a usable strong biometric even when the reminder is due. It never changes `PasswordReminder.isDue`, the stored timestamp or what refreshes it, and `fingerprintUnlockAvailable()` ignores the reminder anyway.
- Rule: once the startup password has not been entered successfully for 14 days, the next unlock (cold start or return from background) asks for the password instead of the fingerprint, with the reason `Reminder` shown as one line ([ui.md](ui.md) owns the text).
- Timestamp: `security.password_verified_at` in `SettingsStore`, next to the backoff values, epoch milliseconds from `AppTime.nowMillis()`. Only `KeyVault` writes it. It is not secret and holds no user data.
```kotlin
// package com.doro.organizer.model; pure, JVM-tested (PasswordReminderTest)
object PasswordReminder {
    const val INTERVAL_MS: Long = 14L * 24 * 60 * 60 * 1000          // 1,209,600,000 ms
    fun isDue(verifiedAt: Long, now: Long): Boolean =
        verifiedAt <= 0L ||                                          // lost or never written: due
        now < verifiedAt ||                                          // clock set backwards: due
        now - verifiedAt >= INTERVAL_MS
}
```
- `KeyVault.isPasswordReminderDue()` returns `PasswordReminder.isDue(settings.passwordVerifiedAt, appTime.nowMillis())`.
- Refreshed (set to `nowMillis()`) by every successful entry of the startup password: the cold-start password (section 5.1), the password on return from background (section 5.5), the password of `confirmWithPassword` that turns the lock on or off or sets up the slot from Settings (section 7.1), the current-password check of a key change (section 4 step 2), the database-password question of a Restore from Corrupt when the password path is used (section 4.1), and the commit of a slot built from a newly typed password (set, change and fingerprint reset, section 3.6). The write is best effort (section 3.6): if it fails, the unlock proceeds and the reminder simply stays due; a lost timestamp counts as due.
- Not refreshed by: a fingerprint unlock (also the fingerprint path of a Restore), a backup, a Restore from `Ready`, a backup password, a biometric slot commit. Erase and a `SettingsStore` reset set it to 0 (due).
- Clock: the interval must survive reboots, so it uses the wall clock (`AppTime.nowMillis()`), not elapsed realtime. Epoch milliseconds are zone-free, so travel and time-zone changes do not matter. A clock moved forward makes the reminder due earlier; a clock set before the stored time makes it due at once. The reminder is a memory aid, not a barrier against A1: the route it replaces already requires an enrolled fingerprint.
- During a backoff countdown the reminder screen shows the countdown like every password screen (section 5.3); the fingerprint reset stays available through "Forgot password?".
- A correct password refreshes the timestamp and opens (or unlocks) as usual. The slot is still valid, so no re-creation prompt follows.

### 5.7 Forgot password
- "Forgot password?" appears on every startup-password screen: cold start (`NeedsPassword`, including the reminder of section 5.6), and the password entry over `Locked(Ready)` (section 5.5) or `Locked(Corrupt)`. It is hidden on the password screen over a `Locked(Maintenance)` resume, where the field is disabled: while an operation runs behind a locked password screen, "Forgot password?" and with it Erase are hidden (DEC-63). A running fingerprint reset shows the progress screen instead of any password screen (section 4.3 step 4).
- It offers, in this order:
  1. "Set a new password with your fingerprint" (section 4.3), only while fingerprint unlock is available (`AppLockController.fingerprintUnlockAvailable()` is `Available`, that is `UnlockPolicy.route` with `reminderDue = false` returning `Slot`). The reminder applies only where the route would otherwise be `Slot` (section 5.6), so on the reminder screen fingerprint unlock is available and "Forgot password?" there leads to the fingerprint reset (DEC-74). The option is shown only on the cold-start password screen and on the password screen of `Locked(Ready)` (DEC-63), never over `Locked(Corrupt)` or `Locked(Maintenance)`.
  2. "Erase all data and start over" (section 5.4), always.
- Without fingerprint unlock the screen states that there is no recovery without a backup (§8, DEC-63), except where the reset would be offered and the key of the slot could not be looked up (`FingerprintAvailability.Unknown`, DEC-77): then it says the fingerprint could not be checked and to try again later, because the reset may work again; the texts are [ui.md](ui.md#103-forgot-password-screen-dec-63)'s.

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
- On submit: `val chars = CharArray(state.text.length) { state.text[it] }`, then `state.clearText()`, then hand `chars` to its owner: the gate dispatches `PasswordSubmitted(chars)`; a dialog calls the ViewModel method that starts the `DbMaintenance` operation or `AppLockController.confirmWithPassword` (section 7.1). The UI keeps no reference (ownership: section 3.6). The fingerprint reset dialog hands the new password to `ForgotPasswordViewModel`, the one gate-screen ViewModel (DEC-90, activity-scoped so that the reset survives rotation): it keeps the array only as a local of the running call (no property, flow or `SavedStateHandle`), passes it to `DbMaintenance.resetPasswordWithBiometric` after the prompt succeeded, and zeroes it itself when the prompt returns `Ok(null)` or `Err(AuthFailed)` (section 4.3).
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
            !constantTimeEquals(b1, b2) -> InputRule.PasswordMismatch
            !acknowledged -> InputRule.AcknowledgementMissing
            else -> return PasswordCheck.Ok(b1)
        }
        b1?.fill(0)
        return PasswordCheck.Rejected(rule)
    } finally { b2?.fill(0) }
}

/** The single-entry rules (malformed, too short) that DbMaintenance re-checks for a new password it receives (DEC-93). Zeroes [chars]. */
fun checkPassword(chars: CharArray): PasswordCheck {
    val n = nfc(chars)
    val length = Character.codePointCount(n, 0, n.size)
    val bytes = utf8(n) ?: return PasswordCheck.Rejected(InputRule.PasswordMalformed)
    if (length < 8) { bytes.fill(0); return PasswordCheck.Rejected(InputRule.PasswordTooShort) }
    return PasswordCheck.Ok(bytes)
}

/** Secret equality in time that depends only on the lengths; `MessageDigest.isEqual` may be imported only by `security` (DEC-93). */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
```
NFC makes the same password typed on another keyboard or device produce the same bytes, which keeps encrypted backups portable. At unlock and at the section 4 current-password check, a null result from `passwordBytes` is passed on as `ByteArray(0)`, which `KeyVault` answers with `PasswordRejected` without PBKDF2 and without counting.

### 6.4 Lifetimes
Within the database lifecycle the table of [db-lifecycle.md](db-lifecycle.md#key-custody) applies; this table is the general policy and agrees with it.

| Secret | Held by | Zeroed |
|---|---|---|
| Password `CharArray` and bytes | The component that received them: effect E2 for a cold start, effect E4 for the password over the open database, `AppLockController.confirmWithPassword` for the lock toggles and Settings "Set up", the `DbMaintenance` operation for key changes, the fingerprint reset, backups and restores | In that operation's `finally`, after the last use. The caller never zeroes them after handing them over (section 3.6) |
| Password KEK (32 bytes) | `KeyVault`, one wrap or unwrap | Right after the GCM call |
| DEK (32 bytes) | `KeyVault`, one unwrap or `prepare` | Right after raw-key encoding, before the call returns |
| Live raw key (67 bytes) | `DatabaseHolder`, from `open` until the generation it keys is replaced or closed | (a) `close()` (effect E3), after `RoomDatabase.close()`; (b) a failed open or reopen; (c) a committed key change, once the old Room instance and the maintenance connection are closed; (d) `Reopen.StayClosed`. Never earlier: an exclusive-mode close for backup, restore or a key change keeps the array, because `DbMaintenance` exports from it and the rollback reopens with it, and SQLCipher keys WAL reader connections later from the same array. Never while a connection keyed with it is open, except one left by a statement that outlived the 2 s flow drain: that connection was keyed when it opened, and the closed pool opens no further connection from the array (DEC-107 (1)) |
| New raw key (`PreparedKey.rawKey`) | `DbMaintenance` until `Committed`, then `DatabaseHolder` | On any failure before the commit, after every connection keyed with it is closed |
| Copy of the live key for a restore | `DbMaintenance` | After target verification of the restored generation, and on every failure or cancellation |
| DEK unwrapped through the biometric slot (32 bytes) | `KeyVault`, one `unlockWithBiometric` or `verifyBiometric` call | `unlockWithBiometric`: right after the raw-key encoding; `verifyBiometric`: right after the tag check. Always before the call returns. The array that `Cipher.doFinal` returned (provider-allocated) is zeroed in the same `finally` that copies it into the DEK array |
| Raw key from the biometric slot | Cold start: E2 hands it to `DatabaseHolder.open`, which owns it like any live raw key. Fingerprint reset and Restore from `Corrupt`: the `DbMaintenance` operation (sections 4.3, 4.1) | Cold start: as the live raw key above. Reset with Room closed: after the export source connection has closed, and on every failure. Restore from `Corrupt`: at once when the slot-key check rejects it or cannot run, otherwise after the export target has been verified and on every failure. Reset with Room open: right after the comparison with the live key |
| Live-key copy for arming | `DbMaintenance.createBiometricSlot` (section 3.7.2) | In its `finally`, after `KeyVault.wrapForBiometric` returns or throws; the DEK that `KeyVault` decodes from it is zeroed right after `doFinal` |
| Authorized `SlotCipher` | Whoever received it from the prompt: E2 (cold start), `AppLockController` (return from background, `DisableLock`), `DbMaintenance.createBiometricSlot` (arming), or the `DbMaintenance` operation of the reset (`resetPasswordWithBiometric`) or of a Restore from `Corrupt` (`answerBiometric`) | Single use: dropped after `doFinal`, or unused on cancel or failure. It holds no key bytes (the biometric key stays in the Keystore), but it is a one-time authorization, so it is never stored in a ViewModel, `SavedStateHandle`, Bundle or navigation key; process death simply requires a new prompt |
| Backup password bytes | `DbMaintenance`, one export or restore operation | After the last connection keyed with them has closed, including the maintenance connection and any temporary Room instance used for migrations ([db-lifecycle.md](db-lifecycle.md#key-custody)); a retry after a wrong backup password uses new bytes |

- DEC-64's "The DEK stays in memory only while the database is open and is zeroed after `close()`" refers to the raw-key encoding held by `DatabaseHolder`; the 32-byte binary DEK exists only inside one `KeyVault` call.

### 6.5 Known limitations
- Compose text buffers, IME and accessibility copies and any `String` made by text layout cannot be zeroed; a moving GC can leave stale array copies; `SecretKeySpec`, `Mac` and `Cipher` keep key copies that cannot be cleared; SQLCipher keeps its key schedule in native memory while a connection is open; AndroidKeyStore returns unwrapped key bytes (the DEK released by the biometric slot or by the KEK layer) through binder copies that cannot be cleared. Zeroing shortens lifetimes; it does not guarantee erasure (live process access is out of scope).
- Editor drafts and search text in `SavedStateHandle` (DEC-28, DEC-37) are kept by the system across process death, outside the app's encryption. They are never shown before unlock because `NavDisplay` is composed only in `Ready` and `Maintenance` (DEC-90).

## 7. Biometric app lock (DEC-65)

### 7.1 Availability and the Settings row
`ui` never imports `androidx.biometric` ([architecture.md](architecture.md) § Import rules). `SettingsViewModel` asks `AppLockController.availability()`, a `suspend` function that wraps `BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)` (through `BiometricGateway.canAuthenticate()`), `KeyguardManager.isDeviceSecure()` and, for the slot state, `KeyVault.biometricSlotStatus()`; it runs each time Settings resumes and before enabling.

```kotlin
// package com.doro.organizer.security
enum class BiometricAvailability { Success, StatusUnknown, NoneEnrolled, Unavailable }   // Unavailable = HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, NO_HARDWARE, UNSUPPORTED
enum class SlotState { NotUsed, Ready, Missing, Invalidated, Unknown }   // NotUsed: mode != PASSWORD or lock off; Ready = BioSlotStatus.Live; Missing = None; Invalidated = Dead; Unknown = Unknown (DEC-77: no "not set up" line, no Set up)
enum class FingerprintAvailability { Available, Unavailable, Unknown }   // fingerprintUnlockAvailable() (7.3): Unknown = the slot route with BioSlotStatus.Unknown (DEC-77)
data class LockAvailability(val biometric: BiometricAvailability, val deviceSecure: Boolean, val slot: SlotState)
enum class ToggleOutcome { Done, Cancelled, PasswordRequired }  // result of confirm and confirmWithPassword (section 7.3)
```

| Result | Lock off | Lock on |
|---|---|---|
| `BIOMETRIC_SUCCESS` | Toggle shown and enabled | Toggle shown |
| `BIOMETRIC_STATUS_UNKNOWN` | Toggle shown and enabled; it turns on only after the test prompt succeeds | Toggle shown |
| `BIOMETRIC_ERROR_NONE_ENROLLED` | Toggle disabled, with an enroll hint and an enroll action (button text: [ui.md § 9.4](ui.md#94-biometric-lock)) | Toggle shown; turning off uses the fallback (7.6), in `PASSWORD` mode the startup password |
| `ERROR_HW_UNAVAILABLE`, `ERROR_SECURITY_UPDATE_REQUIRED`, `ERROR_NO_HARDWARE`, `ERROR_UNSUPPORTED` | Row hidden | Toggle shown; turning off uses the fallback (7.6), in `PASSWORD` mode the startup password |

Screen layout and strings: [ui.md](ui.md#94-biometric-lock).
- While the lock is on, the row is always shown so that the user can turn it off.
- Enroll, posted by `AppLockController.enroll()` and launched through `ExternalActivities` under the re-lock exemption (7.7): API 30+ `Settings.ACTION_BIOMETRIC_ENROLL` with `EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED = BIOMETRIC_STRONG`; API 28–29 `Settings.ACTION_FINGERPRINT_ENROLL`. On `ActivityNotFoundException` (OEMs may not implement the action) `ExternalActivities` launches the request's fallback `Settings.ACTION_SECURITY_SETTINGS`; if that also throws, it ends the exemption and the screen shows only the enroll hint. Never `resolveActivity`: under package visibility its answer depends on the device and can be null although the activity exists.
- Turning on, `PLAIN` or `DEVICE` mode: `confirm(EnableLock)` checks `availability()` (`Success` or `StatusUnknown` and a secure device, else `Err(AuthFailed)`; the switch is not offered then anyway), shows one `strongPrompt` (never the fallback; also the `STATUS_UNKNOWN` test prompt) and on success writes `security.app_lock = true` and returns `Ok(Done)`; codes 5, 10, 13 and 3 return `Ok(Cancelled)`; any other error `Err(AuthFailed)`.
- Turning on, `PASSWORD` mode (DEC-65): `confirm(EnableLock)` returns `Ok(PasswordRequired)` without a prompt, and Settings opens the password-first dialog ([ui.md § 8.16](ui.md)). `confirmWithPassword(EnableLock, password)` then runs, in this order:
  1. `bytes = passwordBytes(password)` (null → `ByteArray(0)`), `KeyVault.verifyPassword(bytes)`; both arrays are zeroed in `finally`. `PasswordRejected` → `Err(WrongPassword)` (counted toward the backoff); `Throttled` → `Err(Throttled(retryAtElapsedMs))`; `StorageFull` → `Err(StorageFull)` (the attempt could not be counted, so the password was not checked, DEC-75); `KeyFailed` → `Err(KeyUnavailable)` (a message only). `Verified`: `KeyVault` has reset the backoff and refreshed the reminder.
  2. `KeyVault.biometricArmCipher()`: `Ready(c)` continues; anything else → `Err(AuthFailed)`.
  3. The slot prompt with the ENCRYPT `CryptoObject` (purpose `EnableLock`, title `biometric_enable_title`, cancel text): `Succeeded(cryptoMatches = true)` continues; codes 5, 10, 13, 3 → `Ok(Cancelled)`, nothing written (the unused key stays until the next arming, removal, new-DEK commit or Erase); any other error or `cryptoMatches == false` → `Err(AuthFailed)`.
  4. `security.app_lock = true` (setting first, DEC-65).
  5. `BiometricSlotWriter.createBiometricSlot(cipher)` (`DbMaintenance.createBiometricSlot`: wrap, commit, read-back). `Ok` → `Ok(Done)`. On any `Err` (including `Busy`) write `security.app_lock = false` again and return the error.
  A crash between (4) and (5) leaves the lock on without a slot: the next unlock asks the password and then the re-creation offer follows (db-lifecycle.md CS-29). The password step stops a finger enrolled by someone who only knows the device PIN from arming a slot while the lock is off. One touch both confirms and arms.
- Settings "Set up" (`PASSWORD` mode, lock on, `LockAvailability.slot` is `Missing` or `Invalidated`, a strong biometric usable): the password-first dialog ([ui.md § 8.16](ui.md), SetUpSlot variant) calls `confirmWithPassword(SetUpSlot, password)`: steps 1–3 above with the title `biometric_slot_title`, no setting write, then step 5 without the write-back. The lock stays on whatever the result.
- Turning off, `PLAIN` or `DEVICE` mode: `confirm(DisableLock)` authenticates on the unlock route at this moment (7.4): without a secure lock screen it writes `false` and returns `Ok(Done)` without a prompt; with a usable strong biometric the `strongPrompt` (`biometric_disable_title`, cancel text); otherwise the device-credential fallback (7.6; API 28–29 as the `CredentialConfirm` intent with `credentialPurpose = DisableLock`). Success writes `security.app_lock = false` → `Ok(Done)`; a cancel → `Ok(Cancelled)`; any other error → `Err(AuthFailed)`.
- Turning off, `PASSWORD` mode: `confirm(DisableLock)` uses the slot prompt (DECRYPT `CryptoObject`, title `biometric_disable_title`, negative button "Use password") and `KeyVault.verifyBiometric` while `fingerprintUnlockAvailable()` is `Available`; "Use password", a slot that cannot authorize and the absence of a fingerprint route return `Ok(PasswordRequired)` (the full mapping is section 7.3), and Settings opens the password dialog ([ui.md § 8.14](ui.md#814-turn-off-the-biometric-lock-with-the-startup-password-dec-65)), which calls `confirmWithPassword(DisableLock, password)` (`verifyPassword`, step 1 above: backoff and reminder). After either authentication, in this order (DEC-65): write `security.app_lock = false`, then `BiometricSlotWriter.removeBiometricSlot()` (key first, then the file; always `Ok(Unit)`), then `Ok(Done)`. A crash in between leaves a stale slot that no route uses while the lock is off and that `reconcileLock()` removes after the next password unlock (db-lifecycle.md CS-31). The device credential never turns the lock off in `PASSWORD` mode.
- Auto-off (7.6) writes the setting first, then calls the same removal.
- A cancel or an error leaves the value unchanged (a failed arming writes it back to false).
- The lock touches the database key only in `PASSWORD` mode, through the biometric slot (DEC-60, DEC-65; this supersedes §11 "app lock only" and "independent of the encryption key", spec errata). In `PLAIN` and `DEVICE` mode it remains a UI lock that never touches the DEK or the Keystore.

### 7.2 PromptInfo
```kotlin
val strongPrompt = BiometricPrompt.PromptInfo.Builder()
    .setTitle(title)                                   // texts: ui.md § 8.11 (biometric_unlock_title, biometric_enable_title, biometric_disable_title)
    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    .setNegativeButtonText(cancelText)                 // required without DEVICE_CREDENTIAL
    .setConfirmationRequired(false)                    // a hint; passive face unlock needs no extra tap
    .build()
val slotPrompt = BiometricPrompt.PromptInfo.Builder()         // PASSWORD mode only, always with a CryptoObject
    .setTitle(title)                                   // biometric_unlock_title (Unlock, RestoreKey), biometric_enable_title, biometric_disable_title, biometric_slot_title (SetUpSlot), biometric_reset_title (ResetPassword)
    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)   // the only class a per-operation key accepts (3.7.1)
    .setNegativeButtonText(negativeText)               // "Use password" for Unlock, DisableLock and RestoreKey; the cancel text for EnableLock, SetUpSlot, ResetPassword
    .setConfirmationRequired(false)
    .build()
val credentialPrompt = BiometricPrompt.PromptInfo.Builder()   // API 30+ fallback only, PLAIN and DEVICE mode only (and over Locked(KeyUnavailable), section 5.1)
    .setTitle(title)
    .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    .build()                                           // a negative button is not allowed here
```
- `PLAIN` and `DEVICE` mode: `authenticate(strongPrompt)` without a `CryptoObject`, or the credential fallback (7.6).
- `PASSWORD` mode: `authenticate(slotPrompt, BiometricPrompt.CryptoObject(slotCipher.cipher))`, always. A `CryptoObject` prompt is never combined with `DEVICE_CREDENTIAL` (androidx.biometric 1.1.0 rejects that pair below API 30, and the per-operation key could not use it anyway), so `PASSWORD` mode has no credential fallback: the password is its fallback (the one exception, the `KeyUnavailable` recovery screen, uses `strongPrompt` without a `CryptoObject`, section 5.1). The cipher is DECRYPT for `Unlock`, `DisableLock`, `ResetPassword` and `RestoreKey` (3.7.3) and ENCRYPT for `EnableLock` and `SetUpSlot` (3.7.2).
- Titles, explanations and negative-button texts: [ui.md](ui.md#811-system-authentication-prompts-dec-65). The negative button of the unlock prompt reads exactly "Use password" (DEC-65).

### 7.3 AppLockController and MainActivity
This section owns the `AppLockController`, `BiometricGateway` and `BiometricSlotWriter` APIs and the rules their members apply. [architecture.md](architecture.md) § AppLockController and BiometricGateway lists the same members with their gate wiring (which `SessionGate` event or row each one feeds), and § Event wiring shows how they are injected; where a name or signature differs, this section wins. `LockScreen`, `LockError` and `LockListener` are architecture.md's (package `security`, names fixed: `LockScreen { Content, Other, PromptToOpen, Prompt, PromptWithoutKey, Password }`, `LockError(code, message)` with `LockError.SLOT_FAILED = -1`, and `LockListener` with `onLockNow`, `onUnlocked`, `onAuthError`, `onSlotUnlocked(cipher)`, `onPasswordRequired(reason, fingerprintUsable, failures, retryAt)` and `onFingerprintRechecked(usable)`, the last for the ON_START re-check of the key-store password screen below, DEC-86).
```kotlin
// package com.doro.organizer.security (the only importer of androidx.biometric)
enum class AuthPurpose { Unlock, EnableLock, DisableLock, SetUpSlot, ResetPassword, RestoreKey }   // SetUpSlot, ResetPassword and RestoreKey exist in PASSWORD mode only

/** The platform adapter beneath AppLockController: everything that touches BiometricManager, BiometricPrompt, KeyguardManager and the system intents.
 *  Production: DefaultBiometricGateway. Tests: FakeBiometricGateway (testing.md § 7), so the re-lock rules, the exemption and the auto-prompt run for real. */
interface BiometricGateway {
    fun canAuthenticate(): BiometricAvailability                   // BiometricManager.canAuthenticate(BIOMETRIC_STRONG), mapped as in 7.1
    fun isDeviceSecure(): Boolean                                  // KeyguardManager.isDeviceSecure()
    fun attach(activity: FragmentActivity)                         // creates BiometricPrompt(activity, mainExecutor, callback); only the newest instance receives callbacks
    fun detach(activity: FragmentActivity)                         // acts only for the attached instance; a finishing one (not a configuration change) dismisses its open prompt and answers it ERROR_CANCELED
    fun authenticate(request: PromptRequest, onResult: (PromptResult) -> Unit)   // strongPrompt, slotPrompt (with request.crypto) or credentialPrompt (7.2); onResult exactly once, on main, unless cancel() withdrew it:
        // ERROR_CANCELED at once without an attached activity, with one below STARTED or whose state is saved (androidx.biometric 1.1.0 drops such a request without a callback), and for a waiting request that a newer one replaces
    fun cancel()                                                   // withdraws the last request (its caller was cancelled) and dismisses its prompt; that request is never answered
    fun credentialIntent(title: String): Intent?                   // API 28–29: createConfirmDeviceCredentialIntent(title, null); null without a secure lock screen
    fun enrollIntents(): Pair<Intent, Intent>                      // the API-dependent enrollment intent (7.1) and the ACTION_SECURITY_SETTINGS fallback
}
class PromptRequest(
    val title: String,
    val credentialOnly: Boolean,
    val negativeText: String?,                                     // only when !credentialOnly
    val crypto: SlotCipher? = null,                                // PASSWORD mode: always set; never together with credentialOnly
) { override fun toString() = "PromptRequest(credentialOnly=$credentialOnly, crypto=${crypto != null})" }
sealed interface PromptResult {
    class Succeeded(val cryptoMatches: Boolean) : PromptResult     // true when result.cryptoObject?.cipher === request.crypto?.cipher (always true without crypto)
    class Error(val code: Int, val message: String) : PromptResult
}
// On success AppLockController checks cryptoMatches and passes on the SlotCipher that KeyVault returned. It never constructs one:
// a SlotCipher built from the prompt's cipher would lack the generation and outerIv that KeyVault recorded at init (3.6), which is what makes the
// state re-check in unlockWithBiometric possible.

/** Implemented by DbMaintenance (data.maintenance), injected by AppContainer as a provider, so security never imports data.maintenance
 *  (architecture.md § Event wiring; mechanics: db-lifecycle.md § Biometric slot commits). */
interface BiometricSlotWriter {                                    // implemented by DbMaintenance; injected as a provider
    suspend fun createBiometricSlot(cipher: SlotCipher): AppResult<Unit>      // gate Ready; Err(Busy) while opMutex is held; wrap + commit + read-back
    suspend fun removeBiometricSlot(): AppResult<Unit>                        // any gate state; deletes the key first; always Ok(Unit) in v1, never Busy
}

enum class SlotOffer { Missing, Invalidated }                      // BioSlotStatus None / Dead at the moment the offer is made

// package com.doro.organizer.security. Production class DefaultAppLockController(biometric, keyVault, slotWriter: () -> BiometricSlotWriter, externalActivities, settingsStore, appTime, processLifecycle, listener: () -> LockListener,
//     texts: () -> PromptTexts, dispatchers, applicationScope, sdkInt: Int = Build.VERSION.SDK_INT)
// texts: the resolved prompt titles and button texts of ui.md § 8.11 (PromptTexts.from(context)), read at every prompt, so the controller needs no Context and follows a language change;
// dispatchers and applicationScope: prompts also start from non-suspend members (prompt(), usePassword(), the auto-prompt, ON_START); sdkInt chooses the API 30+ credential prompt or the API 28–29 intent (7.6), so tests pick the branch (DEC-15)
interface AppLockController : ExternalCallbacks {                  // ExternalCallbacks: core.external, architecture.md § Result launchers and process death
    fun attach(activity: FragmentActivity)                         // MainActivity.onCreate, every instance
    fun detach(activity: FragmentActivity)                         // MainActivity.onDestroy
    suspend fun availability(): LockAvailability                   // section 7.1; reads biometricSlotStatus() for LockAvailability.slot
    suspend fun unlockRoute(): UnlockRoute                         // UnlockPolicy.toRoute(UnlockPolicy.route(...)) with live inputs (5.1); never prompts; E1 and every prompt
    val lockRoute: StateFlow<UnlockRoute?>                         // the route of the lock screen currently shown; the lock screen shows "Use password" while it is Slot
    fun onGateScreenChanged(screen: LockScreen, showsError: Boolean)   // called by SessionGate after every state change; arms the one-shot auto-prompt (below); leaving Content clears slotOffer
    fun prompt()                                                   // the lock screen's "Unlock" button: evaluates the route again (5.1) and prompts
    fun usePassword()                                              // the lock screen's "Use password" action: on the Slot route raises onPasswordRequired(None, fingerprintUsable = true, counters), cause UserChose
    suspend fun confirm(purpose: AuthPurpose): AppResult<ToggleOutcome>                                   // EnableLock, DisableLock (7.1; results below)
    suspend fun confirmWithPassword(purpose: AuthPurpose, password: CharArray): AppResult<ToggleOutcome> // PASSWORD mode: EnableLock, DisableLock, SetUpSlot; owns and zeroes password
    fun enroll()                                                   // the Settings row's "Set up" (7.1): ExternalRequest(Enroll, intent, fallbackIntent) from BiometricGateway.enrollIntents(); ui never reaches the gateway
    suspend fun fingerprintUnlockAvailable(): FingerprintAvailability   // Available: PASSWORD mode, lock on, usable strong biometric, biometricSlotStatus() == Live; Unknown: the same with BioSlotStatus.Unknown (DEC-77); else Unavailable; the reminder does not affect it (4.3, 5.7)
    suspend fun slotPrompt(purpose: AuthPurpose): AppResult<SlotCipher?>   // ResetPassword (4.3) and RestoreKey (4.1): an authorized DECRYPT SlotCipher, or Ok(null) / Err (results below)
    val slotOffer: StateFlow<SlotOffer?>                           // non-null while the re-creation prompt is due (7.8); the shell shows it in Ready
    suspend fun offerSlotSetup()                                   // effect E5: reconcileLock() first (3.7.5), then sets slotOffer when 7.8 holds
    suspend fun createSlot(): AppResult<Boolean>                   // the offer's "Set up", purpose SetUpSlot: only while slotOffer != null, otherwise Ok(false) without a prompt
    fun dismissSlotOffer()                                         // "Not now"
    suspend fun reconcileLock()                                    // removes a stale slot while the lock is off, unless its status is Unknown (3.7.5, DEC-77), and performs the PASSWORD-mode auto-off (7.6)
    val kekNotice: StateFlow<Boolean>                              // true after reportKekBroken() or a true reportPasswordKekFailure() until dismissKekNotice() or clearKekBroken(); in memory only
    fun reportKekBroken()                                          // E2 for Key(kekBroken = true), the controller itself for Verified(kekBroken = true) (3.7.3); also sets kekBroken
    suspend fun reportPasswordKekFailure(): Boolean                // E2 (password path), E4, and MaintenanceStatusHost for NeedsBiometricUnlock(afterKekFailure = true), after a Keystore-layer failure of the password slot: true = a live slot, fall back to the fingerprint (DEC-86, below)
    fun dismissKekNotice()                                         // the notice's "OK", and "Back up now" after it opened Settings with the backup requested (ui.md § 2.4, DEC-101)
    fun clearKekBroken()                                           // DEC-86: clears kekNotice and the kekBroken route flag; E2/E4 after a successful password, E6 after a committed fingerprint reset (below)
    // from ExternalCallbacks: beforeLaunch(kind) arms the exemption (7.7), afterResult(kind) clears it, onCredentialResult(resultCode) (7.6), notLaunched(request) ends a CredentialConfirm that never launched as RESULT_CANCELED (DEC-96)
}
```
- Results of the Settings-facing members (`ToggleOutcome`, section 7.1):
  - `confirm(EnableLock)`: `PLAIN`/`DEVICE`: one `strongPrompt` (never the credential) → `Ok(Done)` after `security.app_lock = true`; codes 5, 10, 13, 3 → `Ok(Cancelled)`; others → `Err(AuthFailed)`. `PASSWORD`: `Ok(PasswordRequired)` at once, no prompt.
  - `confirm(DisableLock)`: `PLAIN`/`DEVICE`: the unlock route with the Q7 fallback → `Ok(Done)` / `Ok(Cancelled)` (codes 5, 10, 13, 3, a non-OK credential result) / `Err(AuthFailed)`. `PASSWORD`: the slot prompt when `fingerprintUnlockAvailable()` is `Available`, then `verifyBiometric` → `Ok(Done)` (a `kekBroken` result also calls `reportKekBroken()`); "Use password" (13), a slot that cannot authorize (cipher preparation `NoSlot`/`Invalidated`/`Unavailable`, `SlotFailed` from `verifyBiometric`, codes 1, 9, 11, 12, 15) or no fingerprint route at all → `Ok(PasswordRequired)`; 5, 10, 3 → `Ok(Cancelled)`; any other code, `cryptoMatches == false` or `KeyFailed` from `verifyBiometric` → `Err(AuthFailed)`.
  - `confirmWithPassword(purpose, password)`: after `verifyPassword` returned `Verified` it calls `clearKekBroken()` (DEC-86) before it continues; `PasswordRejected` → `Err(WrongPassword)`; `Throttled` → `Err(Throttled(retryAtElapsedMs))`; `StorageFull` → `Err(StorageFull)` (not checked, not counted, DEC-75); `KeyFailed` → `Err(KeyUnavailable)` (a message only; the gate is not touched, DEC-86); prompt cancel → `Ok(Cancelled)`; errors of cipher preparation and of the prompt → `Err(AuthFailed)`; errors of `createBiometricSlot` are passed through (`AuthFailed`, `Busy`, `StorageFull`, `KeyUnavailable`, `DatabaseUnavailable`, `OutcomeUnknown`); success → `Ok(Done)`. Calling it outside `PASSWORD` mode or with another purpose is a programming error (`require`).
  - `slotPrompt(ResetPassword)`: `Ok(cipher)`; `Ok(null)` for a cancel (its negative button is the cancel text, so "Use password" is not shown), for "not usable" and for cipher preparation `NoSlot`/`Invalidated`/`Unavailable`; `Err(AuthFailed)` for any other error or `cryptoMatches == false`. `slotPrompt(RestoreKey)`: `Ok(cipher)`, and `Ok(null)` for every other outcome, errors included, because every failure falls back to the password (DEC-84), or, after a KEK failure of the restore's password question, ends the restore with `Err(KeyUnavailable)` ([db-lifecycle.md § Origins and target key](db-lifecycle.md#origins-and-target-key)). It prompts only when the `PASSWORD`-mode route would be `Slot`: lock on, a strong biometric usable, slot `Live`, and the reminder not due, where the reminder counts as not due while the `kekBroken` route flag is set (DEC-86, below); otherwise `Ok(null)` without a prompt. So after a KEK failure with a live slot, whether found by the gate earlier in the process or by the restore's own password question, the restore asks for the fingerprint even when the reminder is due.
  - `createSlot()`: `Ok(true)` created; `Ok(false)` cancelled or no offer pending; `Err(e)` from cipher preparation (`AuthFailed`) or from `createBiometricSlot`.
- In `PASSWORD` mode `AppLockController` additionally raises, through `LockListener`, (a) the cold-start unlock that carries the authorized `SlotCipher` into `Opening` (`onSlotUnlocked`) and (b) the password route with its public `PasswordReason` and `fingerprintUsable` (`onPasswordRequired`, section 5.1). On return from background it calls `KeyVault.verifyBiometric` itself and raises the ordinary `Unlocked`. The event names and rows are [architecture.md](architecture.md) § SessionGate's.
- `confirm(EnableLock)` in `PASSWORD` mode returns `Ok(PasswordRequired)` without a prompt; `confirmWithPassword(EnableLock | DisableLock | SetUpSlot, password)` owns and zeroes the password and runs section 7.1's orders (SetUpSlot: steps 1–3 and 5, no setting write). `confirm`, `confirmWithPassword` and `createSlot` never commit themselves: they hand the authorized `SlotCipher` to `BiometricSlotWriter`. `ui` never reaches `KeyVault`.
- kekBroken notice: `reportKekBroken()` is called by E2 for `UnlockResult.Key(kekBroken = true)` and by the controller itself for `Verified(kekBroken = true)`; it sets `kekNotice` to true (in memory). The shell shows the notice in `Ready` ([ui.md § 2.4](ui.md#24-snackbars-and-notices)); "Back up now" opens Settings with its "Backup all data" requested (`SettingsKey(startBackup = true)`, DEC-101) and then calls `dismissKekNotice()`, "OK" calls `dismissKekNotice()`, so the notice is shown once. `clearKekBroken()` also sets it to false (below), so a notice that has not been shown yet, or is showing, is withdrawn once the KEK is known to work again. `kekNotice` drives only this notice; the password screen's DEC-86 explanation is the reason `KeyStoreFailed` (section 5.1, key store line), which does not depend on `kekNotice`. The notice does not start the fingerprint reset itself: DEC-63 offers the reset only on password screens, so its text points to "Forgot password?" the next time the password is asked.
- `kekBroken` route flag (DEC-86): an in-memory flag, set by `reportKekBroken()` and by a `reportPasswordKekFailure()` that returns true (from E2, E4 or the status host of a restore), never persisted and not cleared by `dismissKekNotice()`. It stays set until `clearKekBroken()` clears it (below) or the process ends: after a KEK failure the reminder is ignored for that time. Its scope is exactly:
  - `unlockRoute()` evaluates `UnlockPolicy.route` with `reminderDue = false` (section 5.1), so a due reminder never asks for a password whose KEK is known to be broken;
  - `slotPrompt(RestoreKey)` treats the reminder as not due in its `Slot` condition (results above), so a Restore from `Corrupt` asks for the fingerprint first even when the reminder is due (section 4.1);
  - `unlockRoute()` and the prompt-error mapping of section 7.4 pass `kekFailedWithLiveSlot = (kekBroken && biometricSlotStatus() == Live)` to `UnlockPolicy.toRoute`, so a password route with cause `NoBiometric` carries the reason `KeyStoreFailed` (section 5.1, key store line);
  - the ON_START re-check of the key-store password screen (below) runs only while the flag is set.
  It changes nothing else: not `fingerprintUnlockAvailable()` (which ignores the reminder anyway), not the reminder timestamp, the backoff or any `KeyVault` call.
- `clearKekBroken()` (DEC-86): sets `kekNotice` and the `kekBroken` flag to false; no I/O, no prompt, idempotent. A successful password unlock proves that the KEK works, and a successful fingerprint reset replaced it (section 4.3), so the notice's advice and the reminder exemption no longer apply. Callers: effect E2 when `KeyVault.unlock(password)` returned `Key` (cold-start password, before the open); effect E4 when `KeyVault.verifyPassword` returned `Verified` (the password over `Locked(Ready)` or `Locked(Corrupt)`); effect E6 at a committed fingerprint reset (architecture.md T55, T57, also when the reset ends locked); and the controller itself in `confirmWithPassword` after its `verifyPassword` returned `Verified` (the password passed the KEK there too). Nothing else clears it: a slot unlock (the slot does not touch the KEK), `dismissKekNotice()`, a backup, a restore and a key change from `Ready` leave it as it is (a key change from `Ready` and a restore from `Corrupt` check their password inside `DbMaintenance`, which never reaches the controller; the next password unlock clears it). After the clearing the reminder applies again and `KeyStoreFailed` cannot be produced until another KEK failure sets the flag.
- ON_START re-check of the key-store password screen (DEC-86): the `KeyStoreFailed` screen appears because no strong biometric was usable when the password met the KEK failure. At every process ON_START while the current `LockScreen` is `Password` and the `kekBroken` flag is set, the controller runs `unlockRoute()` again (on `dispatchers.io`; no prompt, no Keystore operation, independent of the re-lock exemption because it only offers a button) and raises `onFingerprintRechecked(usable = route is UnlockRoute.Slot)` on the main thread. `Slot` means: `PASSWORD` mode, lock on, a usable strong biometric, slot `Live` (the reminder is ignored while the flag is set). `SessionGate` applies it only to a password screen whose reason is `KeyStoreFailed` (architecture.md T61 for `NeedsPassword`, T62 for the password entry of `Locked(Ready)` or `Locked(Corrupt)` while no check runs) by setting `fingerprintUsable`, so the screen offers "Use fingerprint" as soon as a strong biometric is usable and withdraws it when the re-check finds none; every other password screen drops the event. "Use fingerprint" then follows the usual T51 and T52 to the slot prompt, which re-evaluates the route and, on errors 1, 9, 11, 12 or 15, brings the `KeyStoreFailed` screen back (section 7.4).
- `reportPasswordKekFailure()`: called by effect E2 when `KeyVault.unlock(password)` failed in the Keystore layer of a `PASSWORD` slot (`KeyFailed` or a `KeyVaultException(KeyUnavailable)`), by effect E4 when `KeyVault.verifyPassword` returned `KeyFailed`, and by `MaintenanceStatusHost` ([ui.md § 8.12](ui.md#812-maintenance-status-host)) when a Restore from `Corrupt` reports `NeedsBiometricUnlock(afterKekFailure = true)`, that is after `KeyVault.openKey` failed in the Keystore layer of the database-password question ([db-lifecycle.md § Origins and target key](db-lifecycle.md#origins-and-target-key)). It reads the committed mode, `security.app_lock` and `KeyVault.biometricSlotStatus()` (no Keystore operation) and applies the live-slot definition of section 5.1. Mode `PASSWORD`, lock on and `Live`: it sets `kekNotice` and `kekBroken` and returns true. From E2 and E4 the effect then raises `KekFailedWithSlot` (architecture.md T59 from `Opening(viaPassword)`, T60 from a checking password entry), so the gate falls back to the fingerprint route, also when the reminder is due, and the notice appears in `Ready`. From the status host the host then calls `slotPrompt(RestoreKey)`, which, with the flag set, prompts even when the reminder is due, and passes the result to `DbMaintenance.answerBiometric`; the notice waits for `Ready`. Otherwise it changes nothing and returns false: E2 and E4 raise `KeyFailed` (KeyUnavailable at cold start, T23; the retry line over an open database, T50), and the status host answers `answerBiometric(null)` without a prompt, which ends the restore with `Err(KeyUnavailable)`. A slot left behind with the lock off is stale (3.7.5) and is not a route, so it does not count as live here. It never prompts and never counts toward the backoff.
- `AppLockController` is application-scoped (`AppContainer`). It holds only the exemption (`exemptUntil`), `promptInFlight` and `unlockRunning` (DEC-96), the auto-prompt flag, `credentialPurpose` with its pending `CredentialConfirm` request, the current `LockScreen`, the last resolved route, the `slotOffer` state, the `kekNotice` flag and the `kekBroken` route flag (both cleared by `clearKekBroken()`). Whether the app is locked is `SessionGate`'s `Locked` state (DEC-90), which is never persisted (no `SavedStateHandle`, no `rememberSaveable`), so every cold start is locked when the lock is on. It observes the process `LifecycleOwner` that `AppContainer` provides (production: `ProcessLifecycleOwner.get()`; tests: the fake of [testing.md](testing.md) § 7) and reads time only from `AppTime`. It raises gate events through `LockListener` ([architecture.md](architecture.md) § Event wiring).
- `MainActivity` (a `FragmentActivity`, DEC-04) calls `appLockController.attach(this)` in every `onCreate`; `attach` calls `biometric.attach(activity)` and starts the auto-prompt collector below through `DefaultAppLockController`'s internal `observe(owner: LifecycleOwner)`, which the JVM tests call with a `TestProcessLifecycle` in place of an activity. `onDestroy` calls `detach`. The API 28–29 credential intent is an `ExternalRequest` of kind `CredentialConfirm` ([architecture.md](architecture.md) § Result launchers and process death): the launcher is registered by `ExternalActivities.bind` in `onCreate` (before STARTED), `beforeLaunch` arms the exemption, and the result callback calls `afterResult` and then `onCredentialResult(resultCode)` at once, in every gate state, because that result is what unlocks `Locked`; it never waits in the inbox.
- The prompt survives configuration changes and reports to the new instance: track `promptInFlight` (set before `authenticate`, cleared in the `finally` of the suspended prompt once the gateway answered) and never call `authenticate` while it is set; a prompt requested meanwhile is answered `ERROR_CANCELED` without reaching the gateway (DEC-96). The gateway answers every request exactly once (above), so the flag always clears: a finishing activity's `detach` answers its open prompt with `ERROR_CANCELED` (the prompt dies with its activity), while across a configuration change nothing is answered and the prompt reports to the new instance, so rotating during the prompt shows one prompt. A caller cancelled while its prompt is shown calls `cancel()`, which dismisses the prompt. A second flag, `unlockRunning`, ignores an unlock attempt while another one still prepares its cipher, before any prompt is shown.
- Auto-prompt: a one-shot flag is set by `onGateScreenChanged(screen, showsError)` when `SessionGate` shows a lock screen that prompts (`Prompt`, `PromptToOpen` or `PromptWithoutKey`; `Password`, `Content` and `Other` never prompt; a `Locked` over a running fingerprint reset is `Other`, so nothing prompts while the reset runs, DEC-63) and `showsError` is false (a lock entered because an unlock just failed, T44, waits for "Unlock" or the next ON_START), and at each process ON_START while such a screen is shown, except an ON_START that occurs while an exemption is active (it ends an app-launched trip, 7.7). `attach` collects the flag with `activity.lifecycleScope.launch { activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { … } }`: whenever the activity is RESUMED and the flag is set, `AppLockController` clears it and prompts, provided the gate still shows such a screen and `promptInFlight` is false. This covers both orders: the flag set before ON_RESUME (return from background) and after it (cold start, where `Locked` is entered after E1 finished while the activity is already RESUMED behind the splash). Never prompt from composition.
- After `USER_CANCELED`, `NEGATIVE_BUTTON`, a non-OK credential result or any error that shows the Unlock button, nothing prompts until the next ON_START without an exemption; the lock screen's "Unlock" button prompts on demand ([ui.md](ui.md#104-lock-screen)).

### 7.4 Unlock route and error mapping
At prompt time `AppLockController` evaluates `UnlockPolicy.route` (section 5.1; on a `PromptWithoutKey` lock screen, that is `Locked(KeyUnavailable)` and a `Locked(Maintenance)` whose origin is `KeyUnavailable` or whose operation is `Erase`, `routeOverKeyUnavailable`). `PLAIN`/`DEVICE` and `KeyUnavailable`: `canAuthenticate(BIOMETRIC_STRONG)` = SUCCESS or STATUS_UNKNOWN uses `strongPrompt`; anything else uses the fallback (7.6). `PASSWORD`: `Slot` prepares the cipher (`KeyVault.biometricUnlockCipher()`, section 3.7.3) and uses `slotPrompt`; `NoSlot`, `Invalidated` and `Unavailable`, and every `Password` route, raise the password route instead of prompting. `onAuthenticationFailed` (one rejected attempt, prompt still open) changes nothing. A `PASSWORD`-mode key screen whose route is `None` at prompt time (the lock was turned off meanwhile) raises the password route with cause `LockOff`, never `Unlocked`, and a key-state that cannot be read at prompt time over `Prompt` or `PromptToOpen` (a missing file included) fails closed with `AuthError(LockError(LockError.KEY_STATE_UNREADABLE, null))`, which is not silent: the lock screen says that the key file could not be read and to tap "Unlock" (DEC-96).

Each prompt carries a purpose (`AuthPurpose`). Only `Unlock` raises gate events; `EnableLock` and `DisableLock` return `AppResult<ToggleOutcome>` to `SettingsViewModel`: success `Ok(Done)`; codes 5, 10, 3 and a non-OK credential result `Ok(Cancelled)` (no change, no message); code 13 `Ok(Cancelled)` in `PLAIN`/`DEVICE` and `Ok(PasswordRequired)` on the `PASSWORD`-mode slot prompt; any other error `Err(AppError.AuthFailed)` (the full lists are in section 7.3).

`AppLockController` raises exactly these gate events ([architecture.md](architecture.md) § SessionGate):
- `Unlocked`: `onAuthenticationSucceeded`, credential `RESULT_OK`, and auto-off (7.6; never in `PASSWORD` mode, where it follows a verified password).
- `AuthError(LockError(code, message = null))` (silent): codes 5, 10, 13, 3, and credential results other than OK.
- `AuthError(LockError(code, message = errString))`: codes 2, 4, 7, 8 and any code not listed below; the lock screen shows `errString`. A `null` message is the only thing that distinguishes the silent case ([architecture.md](architecture.md) § States, events and effects).
- `AuthError(LockError(code, message = null))` with an app code, never silent: `SLOT_FAILED` (−1, section 3.7.4) and `KEY_STATE_UNREADABLE` (−2, a key-state that could not be read at prompt time, DEC-96); the lock screen shows their fixed texts.
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

- Verify at setup: what `canAuthenticate(BIOMETRIC_STRONG)` returns during a temporary LOCKOUT (code 7) and after LOCKOUT_PERMANENT. If it is not SUCCESS during LOCKOUT, use `strongPrompt` (`slotPrompt` in `PASSWORD` mode) for that case so that row 7 applies instead of the fallback. Not run in T22: no device test puts the real sensor into a lockout, which takes wrong fingers. It moves to T23 with M-01: "Unlock" tapped during the 30 s wait shows the lockout message again when the answer leads to `strongPrompt`, and the device credential when it leads to the fallback. After LOCKOUT_PERMANENT either answer ends at the device credential (the fallback at once, or through error 9), which M-01 already checks.

`PASSWORD` mode, slot prompt (`Unlock` and `DisableLock`; the other purposes follow the last bullet of this section). The table above applies with these differences:

| Outcome | `PASSWORD`-mode lock (`Unlock`) | `DisableLock` result |
|---|---|---|
| `onAuthenticationSucceeded` with the matching crypto object | Cold start: the authorized `SlotCipher` goes into `Opening` (`onSlotUnlocked`, T42; E2 → `unlockWithBiometric`). Return from background: `KeyVault.verifyBiometric`: `Verified(kekBroken)` → `Unlocked` (T10; `reportKekBroken()` when flagged); `SlotFailed(true)` → password route with `FingerprintChanged` (T46); `SlotFailed(false)` or `KeyFailed` → `AuthError(LockError(SLOT_FAILED, null))` (T11; the database stays open, "Use password" stays) | `verifyBiometric`: `Verified` → lock off, `Ok(Done)`; `SlotFailed` → `Ok(PasswordRequired)`; `KeyFailed` → `Err(AuthFailed)` |
| Succeeded without the matching crypto object | As `SlotFailed(invalidated = false)`: stay on the lock with `LockError(SLOT_FAILED, null)` (counted, 3.7.4) | `Err(AuthFailed)` |
| 13 NEGATIVE_BUTTON ("Use password") | Password route, cause `UserChose` (public `None`, `fingerprintUsable = true`; T45 or T46); not silent and no "Unlock"-button state | `Ok(PasswordRequired)` |
| 10 USER_CANCELED, 5 CANCELED, 3 TIMEOUT | As above (silent, "Unlock" button); the lock screen also offers "Use password" | `Ok(Cancelled)` |
| 7 LOCKOUT | Show `errString`; "Unlock" and "Use password" | `Err(AuthFailed)` |
| 1 HW_UNAVAILABLE, 9 LOCKOUT_PERMANENT, 11 NO_BIOMETRICS, 12 HW_NOT_PRESENT, 15 SECURITY_UPDATE_REQUIRED | Password route at once, cause `NoBiometric` (public `None`, or `KeyStoreFailed` while the `kekBroken` flag is set and the slot is `Live`, section 5.1; `fingerprintUsable = false`); never the device credential | `Ok(PasswordRequired)` |
| 2, 4, 8, any other code | Show `errString`; "Unlock" and "Use password" | `Err(AuthFailed)` |
| Cipher preparation: `Invalidated` / `NoSlot` / `Unavailable` | Password route, cause `SlotInvalidated` / `SlotMissing` / `SlotUnavailable` (public reasons: section 5.1); no prompt is shown | `Ok(PasswordRequired)`, no prompt |

- On a `PromptWithoutKey` lock screen (`Locked(KeyUnavailable)`, and a `Locked(Maintenance)` from `KeyUnavailable` or of an Erase) the route is `Biometric` in every mode (section 5.1, `routeOverKeyUnavailable`): the table above applies, with the strong prompt without `CryptoObject` and the device-credential fallback, and `PasswordRequired` is never raised.
- `EnableLock`, `SetUpSlot`, `ResetPassword` and `RestoreKey` prompts map success to their own result (sections 7.1, 7.8, 4.3, 4.1), codes 5, 10, 13 and 3 to "cancelled" (`Ok(Cancelled)`, `Ok(false)` for `createSlot`, `Ok(null)` for `slotPrompt`) and every other code to `Err(AppError.AuthFailed)`; `RestoreKey` maps every non-success to `Ok(null)` (DEC-84: every failure falls back to the password; when the prompt is the DEC-86 fallback after a KEK failure of the restore's password question, `DbMaintenance` ends the restore with `Err(KeyUnavailable)` on `Ok(null)` instead, section 4.1). They never raise gate events. The negative button of `EnableLock`, `SetUpSlot` and `ResetPassword` is the cancel text; that of `RestoreKey` is "Use password", which answers `Ok(null)` so that the restore continues with the database-password question.

### 7.5 Password mode (Q8)
Fingerprint first (DEC-65, Q8). The startup password and the fingerprint are two ways through a single gate; which one is offered follows `UnlockPolicy.route`, and a password entry is never followed by a biometric prompt.
- Cold start, lock on, usable strong biometric, live slot, reminder not due (or ignored while the `kekBroken` flag of a KEK failure in this process is set, DEC-86, section 5.6): the slot prompt; the fingerprint alone opens the database (section 5.1). "Use password" leads to the startup password.
- Cold start otherwise: the startup password alone (with the reason line when the lock is on).
- Return from background, lock on: the slot prompt under the same conditions, otherwise the startup password alone; the database stays open either way (section 5.5). Without a secure lock screen the password is asked too: the auto-off of 7.6 never raises `Unlocked` in `PASSWORD` mode.
- Lock off: the startup password at every cold start, nothing on return from background (DEC-62).
- The device credential never unlocks a `PASSWORD`-mode database: without a usable fingerprint the password is the only way in (Q7 applies to the modes without a startup password). The single exception is a `PromptWithoutKey` lock screen (the `KeyUnavailable` recovery screen and a running Erase, section 5.1), where no data is readable and the credential only gates access to Restore and Start fresh or to the progress screen.
- After a password unlock with the lock on, a usable biometric and a slot that is not live: the re-creation prompt (7.8).

### 7.6 Device-credential fallback (Q7)
- `PLAIN` and `DEVICE` mode only (Q7), and on a `PromptWithoutKey` lock screen (the `KeyUnavailable` recovery screen and a running Erase) in every mode (section 5.1). Used when strong biometrics are unavailable or permanently locked out (7.4), for unlocking and for turning the lock off. In `PASSWORD` mode the startup password takes this role (7.5).
- API 30+: `credentialPrompt` on the same `BiometricPrompt`.
- API 28–29: `BiometricGateway.credentialIntent(title)` (`KeyguardManager.createConfirmDeviceCredentialIntent(title, null)`) posted as an `ExternalRequest` of kind `CredentialConfirm`, under the re-lock exemption (it starts a system activity, so `MainActivity` stops); `credentialPurpose` records `Unlock` or `DisableLock`. `RESULT_OK` unlocks (`Unlock`) or sets `security.app_lock = false` (`DisableLock`); any other result code is handled like USER_CANCELED (7.4), a silent `AuthError(LockError(10, null))`; a request that is never launched or that a newer credential request supersedes ends as `RESULT_CANCELED`, and a result whose purpose was lost with the process is handled as `Unlock` (DEC-96). Returning from the credential screen produces a process ON_START, which does not set the auto-prompt flag because the exemption is still active (the result callback clears it afterwards); this is what prevents a loop after Back. Below API 30, BiometricPrompt supports neither `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` nor `DEVICE_CREDENTIAL` alone.
- Auto-off: if `KeyguardManager.isDeviceSecure()` is false (or the intent is null), set `security.app_lock = false` and `security.lock_auto_off_notice = true`, raise `Unlocked`, and continue as with the lock off. The notice shows once and clears the flag ([ui.md](ui.md#94-biometric-lock)). This applies to `PLAIN` and `DEVICE` mode (and to the `KeyUnavailable` recovery screen, where no data is readable). In `PASSWORD` mode auto-off never raises `Unlocked` and never replaces a password check: the device credential is excluded there, so someone who knows the phone PIN could otherwise remove the screen lock in system Settings while Doro sits in the background with its database open, reopen it and pass without a password or fingerprint (the policy returns `Password(NoBiometric)`, section 5.1). Instead, on a cold start the gate asks the startup password (`NeedsPassword`), and on return from background it shows the startup-password screen (`Locked(Ready)` with a `PasswordEntry`, T46). The auto-off writes run afterwards, after the password was verified (T48) or after the cold-start open (T16): `AppLockController.reconcileLock()` (called by `offerSlotSetup()` in effect E5, 3.7.5) sees the lock on without a secure lock screen, writes `security.app_lock = false` and `security.lock_auto_off_notice = true` in one `SettingsStore` edit, then calls `BiometricSlotWriter.removeBiometricSlot()` (the platform has invalidated the key anyway), in the order of DEC-65.
- The API 28–29 branch (`createConfirmDeviceCredentialIntent`) is not run on a device: DEC-15 covers it with lint `NewApi`, JVM tests of the branching and review (accepted risk). Verify at setup (optional): on an API 28 emulator, if one is set up, the deprecated intent confirms and returns `RESULT_OK`.

### 7.7 Re-lock rules
- `AppLockController` observes the process lifecycle from process start. `ProcessLifecycleOwner` posts its delayed pause 700 ms after the last activity pauses and dispatches ON_STOP at that moment, or when the activity stops if that is later; it never fires for configuration changes. A background trip shorter than 700 ms therefore does not lock. Screen-off counts as background.
- On ON_STOP with the lock on and no active exemption, `AppLockController` raises `LockNow` whatever the gate state. `SessionGate` applies it per [architecture.md](architecture.md): `Opening` → `Opening(lockPending)` and the operation ends in `Locked(...)` (T24, T16, T17, T23); `Ready` → `Locked(Ready)`, content leaves composition and Room stays open (T25); `Maintenance` from `Ready`, `Corrupt` or `KeyUnavailable` → `Locked(this Maintenance)` at once, the operation continues in `applicationScope` and its outcome is applied by T14a–e and T15 (T32); a `Maintenance` from `NeedsPassword` ignores it when it is an Erase (no database open, ending in `Restart` or `Aborted`; T32's guard); a fingerprint reset counts as the unlock (DEC-63), so a `LockNow` while it runs is recorded in `lockPending` (T53 from `NeedsPassword`, T54 behind the password screen of `Locked(Ready)`) and its success then ends in `Locked(Ready)` instead of `Ready`; `Corrupt` and `KeyUnavailable` → `Locked(this state)` (T34); `NeedsPassword` and `Locked` otherwise ignore it (T35; a password entry survives). There is no grace period, so the first frame after ON_START is the gate.
- A `LockNow` in `Starting(false)` needs no row of its own: T35 drops it, because `StartupLoaded` and `StartupKeyFailed` read the lock setting and enter `Locked` or `NeedsPassword` (T2, T3, T5, T40) whenever the lock is on. In `Starting(true)` (the user authenticated before a recovery screen) T36 withdraws that exemption and returns to `Starting(false)` ([architecture.md](architecture.md) § Transition table, "Locking while starting").
- Exemption: `ExternalActivities` calls `beforeLaunch(kind)` immediately before launching SAF `CreateDocument`/`OpenDocument`, the share chooser (launched for a result), the enrollment intent or the API 28–29 credential intent. It sets `exemptUntil = appTime.elapsedMillis() + 600_000`; a launch while an exemption is active replaces it and restarts `exemptUntil`.
- Every launch runs inside `try` in `ExternalActivities`; on `ActivityNotFoundException` or `SecurityException` it calls `afterResult(kind)` before reporting the failure (otherwise a launch that never paused the activity would leave the exemption armed for 10 minutes and a real background trip would not lock). SAF and share launch failures become `AppError.FileAccess` after the exemption is cleared; enrollment failures follow 7.1 (the request carries the `ACTION_SECURITY_SETTINGS` fallback intent).
- While the exemption is active, ON_STOP does not lock. The activity-result callback (`afterResult`) clears it; results arrive after the process ON_START and before `onResume`, so the exemption is still set at ON_START. The next activity ON_RESUME also clears it. At an ON_START after `exemptUntil` the exemption is cleared and `LockNow` is raised: the exempted stop was a real background trip.
- Results that arrive while `Locked`: `CredentialConfirm` is delivered at once, in every gate state (7.3). `Backup` and `Restore` results wait in the `ExternalActivities` inbox and are processed after unlock (DEC-90). `Share` and `Enroll` results are discarded after `afterResult`. A process killed in background restarts locked.
- Verify at setup: with the real `ProcessLifecycleOwner` on the owner's phone (Poco F7, DEC-15), the `CreateDocument` result callback runs after the process ON_START and before `MainActivity.onResume`; the whole exemption rule depends on that order. Not run in T22: the device tests answer `CreateDocument` through Espresso-Intents (`BackupUiTest`, `ShellDialogsTest`), so the activity never stops and no real result meets the real `ProcessLifecycleOwner`. It moves to T23 with M-12, which saves a backup after more than 10 minutes in the picker: the app must return locked, and it does only when the result comes after the process ON_START, which then still sees the expired exemption.

### 7.8 Slot re-creation prompt (DEC-65)
After a password unlock, one fingerprint touch re-arms fingerprint unlock for the current DEK.
- Due when all hold: mode `PASSWORD`; lock on; a strong biometric is usable; `biometricSlotStatus()` is `None` or `Dead` (no slot, or one whose key is gone; never `Unknown`, whose key may still be good, DEC-77); and the gate has just reached `Ready` through a successful startup-password entry (T16 after a `viaPassword` open, section 5.1; T48 over the open database, section 5.5), through a committed key change from `Ready` that ends in `PASSWORD` mode (T28a), or through a successful fingerprint password reset, which counts as the unlock and has just committed a newly typed password (T16 after a `viaReset` open, T57; DEC-63, section 4.3; a reset that ended locked because the app was left meanwhile offers it after the next password unlock instead). These are exactly the rows that run effect E5.
- Effect E5 calls `AppLockController.offerSlotSetup()`, which runs `reconcileLock()` first (3.7.5) and then sets `slotOffer` to `Missing` (status `None`) or `Invalidated` (status `Dead`); the shell shows the prompt ([ui.md](ui.md) owns whether a short explanation precedes it and its texts). `createSlot()` runs section 3.7.2 with purpose `SetUpSlot`: `biometricArmCipher`, the slot prompt with the ENCRYPT `CryptoObject`, `BiometricSlotWriter.createBiometricSlot` (`DbMaintenance`, [db-lifecycle.md](db-lifecycle.md#biometric-slot-commits)). It clears the offer in every outcome.
- `createSlot()` prompts only while `slotOffer` is non-null; otherwise it returns `Ok(false)` without a prompt, so the offer's password-free arming exists only in the foreground session that entered the password (DEC-65). Leaving `Content` (`onGateScreenChanged` with any other screen, for example after a `LockNow`) clears the offer.
- Cancelled, failed, dismissed or cleared: nothing is persisted. It is offered again after the next successful password unlock, because the same conditions hold then; Settings "Set up" (section 7.1) is available meanwhile. It is never offered after a fingerprint unlock (a live slot exists) or a reminder unlock (the slot is still live).
- E5 does not run after a Restore (DEC-84 keeps the slot), so no offer follows one.
- Not shown while the gate is not `Ready`, during maintenance, or when the user turns the lock off meanwhile.

## 8. Screen protection (DEC-66, DEC-67)

- In `MainActivity.onCreate`, before `setContent`: `window.addFlags(FLAG_SECURE)` and, on API 33+, `setRecentsScreenshotEnabled(false)`. Starting closed guarantees protection before the first frame. Then collect the window policy ([architecture.md](architecture.md#mainactivity-startup-order) § MainActivity startup order) and apply it live, without recreation:
  - `windowPolicy = combine(settingsStore.settings, dbMaintenance.keyMode, sessionGate.state)` ([architecture.md](architecture.md) § Dependency injection owns the code). While the settings value or the key mode is null (the first `SettingsStore` read has not finished, or the key-state is not valid), the policy stays closed: `FLAG_SECURE` on, the recents screenshot off and overlays hidden. A closed policy is still emitted, so the splash is released.
  - `FLAG_SECURE` ⇔ `security.screenshot_protection` (`addFlags`/`clearFlags`), or the policy is closed.
  - API 33+: `setRecentsScreenshotEnabled(!(biometricLock || keyMode != PLAIN))`.
  - API 31+: `setHideOverlayWindows(hideOverlays)` (section 9.4).
  - The window policy carries no keyboard privacy: the no-learning flag is unconditional (section 11, DEC-71).
- The flag covers the whole window: lists, calendar, editor, pickers, Settings and gate screens (spec errata, DEC-67).
- Compose `Dialog`, `Popup`, `ModalBottomSheet`, `DropdownMenu`, `AlertDialog` and `DatePickerDialog` keep `securePolicy = SecureFlagPolicy.Inherit` (the default). `SecureFlagPolicy.SecureOff` is banned (`ArchitectureTest`, [architecture.md § Boundary test](architecture.md#boundary-test)).
- No View-based windows (`android.app.Dialog`, `PopupWindow`, framework pickers). If one becomes unavoidable, it sets `window.setFlags(FLAG_SECURE, FLAG_SECURE)` itself while protection is on. The toggle sits on the Settings screen with no dialog open, so no window keeps a stale flag.
- No user data in Toasts (drawn by the system outside the window on API 30+) or notifications (the app posts none and never requests `POST_NOTIFICATIONS`). Snackbars carry counts only (DEC-36).
- While `Locked`, and on `Starting`, `NeedsPassword`, `Opening` and the Erase screen, content UI is not composed and no screen shows previews, names, counts or dates (DEC-66). The Corrupt and KeyUnavailable screens may show DEC-84's restore confirmation, whose counts are read from the chosen backup file, never from the live database.
- System content capture: on API 29+ the platform can stream the text of an app's views to an OEM intelligence service unless the app opts out. `MainActivity.onCreate` calls `getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)` (the setting is not persisted across reboots, so it runs on every `onCreate`). Verify at setup: `isContentCaptureEnabled()` returns false afterwards on the owner's phone (Poco F7, DEC-15). `WindowSecurityTest$SettingsRead#contentCapture_isDisabledForMainActivity_onceReady` (API 29+, added at the T22 close-out) asserts it once `MainActivity` is Ready: the activity's own `ContentCaptureManager` reports content capture as disabled, or the system gives no manager, which it does when it captures nothing for the app (no service, or the app not allowlisted); its `DoroSetupProbe` line records which of the two the phone shows. Outcome pending: the test has not run on the device yet. The full run build/t22-full-4 (at `1aee456`) predates the test, so it does not cover it. The first device run of the test records the outcome here: a run of the class alone once it is on main (`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.doro.organizer.ui.WindowSecurityTest`), or, if T22 closes before that, the first `device-tests` run of T23.
- To decide before targetSdk moves to 37 (DEC-01) or the phone first runs API 37, whichever comes first: the API database of the compileSdk platform (`platforms/android-37.2/data/api-versions.xml`) marks `ContentCaptureManager.setContentCaptureEnabled` deprecated since API 37.0, and the call in `MainActivity.onCreate` compiles with a deprecation warning. It is the only implementation of the content-capture opt-out (adversary A5, section 1.2). The phone runs API 36, where the call is not deprecated; whether it still takes effect on API 37 and later, and what replaces it, is decided by then, keeping the test above. An OS update can bring API 37 while targetSdk stays 35, so after such an update `WindowSecurityTest` runs on the phone and its `DoroSetupProbe` line is recorded here.

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
- DEC-69: the owner answered Q9 (transfer stays blocked; a new phone imports a backup). If this is ever revisited and device-to-device transfer is allowed, only the `<device-transfer>` section changes, and only `PLAIN` databases would arrive usable: `noBackupFilesDir` (the key-state file) and Keystore keys never transfer, so `DEVICE` and `PASSWORD` databases would arrive as KeyUnavailable (Verify at setup).
- There is no `<cross-platform-transfer>` section: it requires `<platform-specific-params>` naming an iOS counterpart app, and none exists. Verify at setup (optional, DEC-15: no emulator is required): on an API 36.1 image or on the owner's phone once it runs API 36.1, nothing is offered for cross-platform transfer.

### 9.3 CI checks
Wiring: [testing.md](testing.md#9-ci-pipeline) (`manifest` and `dependencies` jobs), implemented as the Gradle tasks `:app:checkReleaseManifestPolicy` and `:app:checkBannedDependencies` of the `build-logic` included build and run by `./gradlew :app:verificationGates` (DEC-14, DEC-03). The manifest gate reads AGP's `MERGED_MANIFEST` artifact of the release variant, the `applicationId` of every variant, the release variant's resource source directories and the zip entries of the release APK; it does not use `apkanalyzer` or `aapt2` (they read the same values, but `apkanalyzer` is a `.bat` on Windows and path-shortened resources cannot be read back from the APK).
- Permission allowlist on the merged release manifest (`<uses-permission>`, `<uses-permission-sdk-23>` and `<permission>`), the content of `config/permission-allowlist.txt`: `android.permission.USE_BIOMETRIC`, `android.permission.USE_FINGERPRINT`, `android.permission.HIDE_OVERLAY_WINDOWS`, `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (stored with the literal placeholder; the gate substitutes the `applicationId` of the release variant it checks, `com.doro.organizer`, DEC-03). Anything else fails, and `android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE` and `android.permission.ACCESS_WIFI_STATE` fail even if someone adds them to the file (DEC-68). Verified at setup (T2 build, 2026-11-19): with the pinned T1 libraries the merged release manifest holds no permission outside this list; re-check whenever a dependency changes.
- Exported components: only `.MainActivity` and `androidx.profileinstaller.ProfileInstallReceiver` (guarded by `android.permission.DUMP`; a receiver of that name without the DUMP permission fails). DEC-68's "only the launcher activity is exported" refers to the app's own components; the library's DUMP-protected receiver is accepted and keeps its own intent filters, while every other component with an intent filter fails and `MainActivity`'s filters must each be exactly action MAIN plus category LAUNCHER. Also asserted on the merged release manifest: `allowBackup="false"`, both rule resources referenced, no `android:debuggable="true"`, no `android:testOnly="true"`, and for every provider with `android.support.FILE_PROVIDER_PATHS` meta-data (section 9.1): the class is `com.doro.organizer.BackupFileProvider`, the authority is `<release applicationId>.fileprovider`, `grantUriPermissions` is true, the provider is not exported and the meta-data names `@xml/file_paths`. The release resource sources must then hold exactly one unqualified `res/xml/file_paths.xml` with the section 9.1 content; any qualified copy (`xml-v31/file_paths.xml` and the like, which Android would prefer at runtime) fails. Until the T12 FileProvider exists no provider names the resource and this part passes trivially; it is enforced from then on. The gate also fails when a build type other than `release`, `debug`, `qa` and `benchmark` exists or one of them is missing (DEC-12, DEC-15). The full rule list is the `manifest` row of [testing.md § 9](testing.md#9-ci-pipeline).
- Banned dependencies, matched by group prefix or artifact on `releaseRuntimeClasspath`, `debugRuntimeClasspath`, `debugUnitTestRuntimeClasspath` and `debugAndroidTestRuntimeClasspath` ([testing.md](testing.md#9-ci-pipeline) `dependencies` job; the list is this file's contribution to `config/banned-dependencies.txt`, and "any analytics or ad SDK" means exactly these groups, so a new SDK is banned by adding its group here): `com.google.firebase`, `com.google.android.gms`, `com.google.android.play`, `com.google.android.datatransport`, `com.crashlytics`, `io.sentry`, `com.bugsnag`, `com.squareup.okhttp3`, `com.squareup.retrofit2`, `io.ktor`, `com.facebook.android`, `com.appsflyer`, `com.adjust.sdk`, `com.amplitude`, `com.mixpanel.android`, `com.segment.analytics.kotlin`, `com.datadoghq`, `com.newrelic.agent.android`, `io.embrace`, `com.instabug.library`, `com.onesignal`, `com.applovin`, `com.unity3d.ads`, `com.ironsource.sdk`, `io.branch.sdk.android`, `com.huawei.hms`, `com.jakewharton.timber` (logging policy, section 10), `androidx.security:security-crypto` (DEC-09), `net.zetetic:android-database-sqlcipher` (legacy). Verify at setup: the group ids against Maven Central.
- Classpaths the `dependencies` gate checks (`:app:checkBannedDependencies`, DEC-14): the four configurations the bullet above names are the minimum, not the whole scope. The gate checks the runtime classpath of every component of every variant, that is of the `release`, `debug`, `qa` and `benchmark` variants and of each unit-test and androidTest component they have (for example `qaRuntimeClasspath`, `benchmarkRuntimeClasspath`, `debugAndroidTestRuntimeClasspath`), because `qa` and `benchmark` also install on the owner's phone (DEC-12, DEC-15). It walks each resolution graph from its root (constraint-only edges add nothing), matches resolved and unresolved modules against `config/banned-dependencies.txt`, and fails on any module that could not be resolved, so it never passes on a graph it could not see completely. Each failure names the module and the configuration. The coordinates of the bullet above are compared by `BannedDependenciesTest` (in `build-logic`, run by `:app:verificationGates`) with the committed `config/banned-dependencies.txt`: a change to that list changes the file in the same change ([testing.md § 9](testing.md#9-ci-pipeline)).
- Manual QA per release: [testing.md](testing.md#10-manual-qa-checklist-before-release) M-06 (backup exclusion).

### 9.4 Task hijacking and overlays
- StrandHogg: Google's mitigation is minSdk 30; version 2 is prevented only by that OS patch. At minSdk 28, unpatched API 28–29 devices stay exposed; accepted. `android:taskAffinity=""` partially mitigates version 1.
- On API 31+ (normal permission `HIDE_OVERLAY_WINDOWS`), `MainActivity` applies `window.setHideOverlayWindows(hideOverlays)` from the window policy (section 8), with `hideOverlays = gate ∉ {Ready, Maintenance with origin Ready}` (DEC-66; code in [architecture.md](architecture.md) § Dependency injection): the password, lock and recovery screens, and the progress screen of every operation that did not start from `Ready`, are never overlaid. An Erase started from `Ready` is a `Maintenance` with origin `Ready`, so its progress screen keeps overlays allowed as DEC-66 states; it shows no data, and its typed confirmation already ran in a dialog that hid them. Dialog windows are separate windows, so every security dialog also sets it on its own window through `SecureDialogWindow(hideOverlays = true)` (section 11). Security dialogs (the list of [ui.md § 8.1](ui.md#81-common-rules)): the section 4 key changes, the turn-off-with-password dialog, the fingerprint password reset dialog, the password-first dialog of the lock toggle and Settings "Set up", the backup and restore password dialogs, the restore confirmation, Start fresh and Erase. No other dialog, sheet or menu window requests it, the Forgot screen's `key_change_failed` error dialog included (DEC-102).
- On API 28–30 overlay tapjacking is accepted; `filterTouchesWhenObscured` is not used because it blocks input under screen-dimmer apps.

## 10. Logging and leak hygiene (DEC-70)

- Never log, and never put into an exception message, in any build type: note content, person/project fields, search text, passwords, key material, key-state fields, backup file names, URIs. Allowed: IDs, counts, durations, enum and state names, exception class names, SQLite result codes.
- All app logging goes through `AppLog` (`core.log`): the `src/debug` implementation forwards to `android.util.Log`, the `src/release` one is empty. No other class references `android.util.Log`. `println`, `printStackTrace`, `System.out`, `System.err` and Timber are banned.
- SQL is always parameterized ([data-layer.md](data-layer.md)), so SQLite messages never contain user text. Room `setQueryCallback` exists only in `src/debug` (`DebugRoomHooks`, [db-lifecycle.md](db-lifecycle.md#room-builder)) and logs the SQL text and the bind-argument count, never the values.
- Exception messages are constant text plus IDs; wrap with `cause` only when the cause's message cannot contain processed input. The default uncaught-exception handler prints the whole cause chain to logcat in release too, so this rule is the only protection there. No custom crash handler, crash file or crash SDK (DEC-68).
- Never log, rethrow or pass as `cause` an exception whose message can contain processed input: `org.json.JSONException` (its tokenizer errors append the entire input), `NumberFormatException`, `java.time.DateTimeException`, and `IllegalArgumentException` from Base64, `Normalizer2` or `CharsetEncoder`. Catch it at the parsing boundary and throw a new exception whose message holds only constant text, ids and `e.javaClass.simpleName`, with no cause. Known sites: `KeyVault`'s key-state parser (section 3.5) and `DraftInserts.decode`, the codec of `SettingsStore`'s `nav.draft_inserts` value, which reads malformed text as no records and neither logs nor rethrows the `JSONException`. The list rows' link-id columns `person_ids`/`project_ids` are parsed without such an exception: `NoteRowMapping` reads each part with `toLongOrNull` and throws `IllegalStateException` with constant text, the column name and the note id only, never with a `NumberFormatException` as cause ([data-layer.md § Rows](data-layer.md#rows), DEC-106).
- Release configuration:
```
# app/proguard-rules.pro (release, and through initWith the qa and benchmark builds; on top of proguard-android-optimize.txt, DEC-13)
-maximumremovedandroidloglevel 7
# Required as well (DEC-70): on AGP 9.5.1 the level rule alone leaves Log calls in the dex (see "Verified at setup" below)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
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
- Verified at setup (T1 build, 2026-11-19): R8 accepts `-maximumremovedandroidloglevel 7` in the release `proguard-rules.pro`; the release `SqlcipherLogging.configure()` silences SQLCipher's Java logging through `net.zetetic.database.Logger.setTarget(net.zetetic.database.NoopTarget())`, so the package names above are correct.
- Verified at setup, with a negative result (T2 build, 2026-11-19, AGP 9.5.1): `-maximumremovedandroidloglevel 7` **alone does not** remove every `Log` call. With only that rule, the `release-logs` gate ([testing.md § 9](testing.md#9-ci-pipeline), `:app:checkReleaseLogs`) found 28 `Log.v`/`d`/`i`/`w`/`e`/`wtf` calls left in the release dex, in AndroidX core and Compose classes, Kotlin's `TypeIntrinsics` and SQLCipher's `LogcatTarget` and `CloseGuard`. Therefore `-assumenosideeffects class android.util.Log { v d i w e wtf println }` (the block above) is required as well, and DEC-70 records both rules. With both, the only `android.util.Log` reference left in the release dex is `isLoggable`, which is allowed (it writes nothing). This also answers testing.md's former open check whether the level rule removes `Log.wtf` and `Log.println`: the gate now proves the combined effect on every build instead. Keep both rules: the gate checks that the configuration R8 applied contains `-maximumremovedandroidloglevel 7` and scans the dex for every banned call, so dropping `-assumenosideeffects` fails the gate again, and dropping the level rule fails its configuration check.
- Still open, a phone check: whether SQLCipher's **native** cipher log (written by the library's C code, not through `net.zetetic.database.Logger`) reaches logcat in a release-like build. [testing.md](testing.md) M-27 runs it on the `qa` build on the owner's phone. If any such line appears, the release `SqlcipherLogging.configure()` must also run `PRAGMA cipher_log_level = NONE` on its own throwaway connection (the one allowed exception of the pragma allowlist of section 3.4), and M-27 is repeated until it passes. Until M-27 passes, the "SQLCipher logger off" part of DEC-70 (SPEC.md V34) is proven for the Java logger only, not for the native log. Observed in the debug build on the Poco F7 (T22, 2026-11-19): native lines do reach logcat there, under the tag `sqlcipher` (the cipher log, for example `ERROR CORE sqlcipher_page_cipher: hmac check failed for pgno=7`) and under the tag `SQLiteLog` (SQLite's own log: result code, message, the failing statement's SQL with `?` placeholders, and paths of app-internal files); no bound value and no user text appeared. M-27 on the `qa` build stays open (not run at T22: M-27 needs the Device, Password, backup and restore flows driven by hand on the `qa` build, and adb input injection is blocked on HyperOS, so it runs with the owner in T23. The `qa` release-smoke logcat, build/t22-smoke3, shows `libsqlcipher.so` loaded and no SQLCipher or SQLiteLog line from the app's processes, but its flows open only a Plain database).
- `toString()` redaction is required on every data class, and on every class with a property of type `String`, `CharSequence`, `CharArray`, `ByteArray`, `Uri` or `File` (or a collection of them), in any package under `com.doro.organizer`. It prints the class name, ids, enum values and booleans only; never text, lengths, bytes, URIs or file names. Known members: `NoteEntity`, `PersonEntity`, `ProjectEntity`, `NoteRow`, `NoteRowRaw`, `EntityRef`, `NoteDraft`, `NewEntity`, `NoteDetail`, `EntityOption`, `NoteFilter`, `NoteQuery`, the editor, settings and gate UI states, `GateState`, `GateEvent` (incl. `PasswordSubmitted`), `SaveResult`, `GateEffect`, `LockError`, `AppError`, `ExternalRequest`, `ExternalResult`, `MaintenanceResult.BackupShared`, `UserMessage`, `Kdf`, `KeySlot`, `BioSlot`, `KeyState`, `PreparedKey`, `UnlockResult`, `SlotCipher`, `PromptRequest`, `PasswordCheck`:
```kotlin
class KeySlot(val mode: KeyMode, val outerIv: ByteArray?, val wrappedDek: ByteArray? /* , … */) {
    override fun toString() = "KeySlot(mode=$mode)"
}
// data-layer.md's entities follow the same rule, e.g. NoteEntity(id=…) and NewEntity(key=…)
```
- Enforcement (owned by [testing.md](testing.md)): I-39 `RedactionTest` constructs each class above with `SENTINEL` ([testing.md](testing.md) § 7 Fixtures and fakes) in every text field and asserts that `toString()` lacks it; I-40 `LogPrivacyTest` (including a malformed `person_ids` containing `SENTINEL`, which must leave no sentinel in logcat; DEC-106); I-41 `ArchitectureTest` enforces the banned tokens and the `toString` rule of [architecture.md § Boundary test](architecture.md#boundary-test), the single list of rules (this section does not repeat the patterns); the `release-logs` CI job; manual QA M-17 (no line containing user data; framework lines under the app's pid are expected).

## 11. Keyboard and autofill (DEC-71, Q11)

Per DEC-71 (Q11: the keyboard must never learn) the covered fields always ask for no personalized learning, whatever the screenshot setting or the window policy, and autofill is excluded for every window of the app, password fields included, so password managers cannot fill or save the database or backup password (accepted for v1).
- Password fields: section 6.2.
- Fields covered below: note content, person name, project name, and every search field (perspective search and `EntityPicker` search, which carry the same words; person and project names are typed in the picker's search field).
- Autofill: always excluded. Compose has no per-field exclusion (`ContentType` has no "not important" value, and fields without one are still offered to service heuristics), so `MainActivity` sets `window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` after `setContent`. Compose `Dialog`, `AlertDialog`, `DatePickerDialog`, `ModalBottomSheet` and `DropdownMenu` create their own windows with their own root view, which that flag does not reach, so every one of them wraps its content in `SecureDialogWindow` (`ui/common`, DEC-102). The wrapper sits inside the window it guards, in its content lambda (for an `AlertDialog`, the `text` slot), where `LocalView` is that window's own view; wrapped around the dialog it would set both flags on the activity's window only. A `DropdownMenu` popup's root is no `DialogWindowProvider`, and menus never pass `hideOverlays`:
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
  Verify at setup: `view.parent` is the `DialogWindowProvider` for `AlertDialog`, `Dialog` and `ModalBottomSheet` in the pinned Compose BOM (settled offline for `ModalBottomSheet` in T20: material3 1.4.0's `ModalBottomSheetDialogLayout` implements `DialogWindowProvider`; L-25 and L-29 assert it on the device for every dialog and sheet they show); with a test autofill service enabled, typing in the editor, an `EntityPicker` search field, the change-password dialog and the backup-password dialog produces no fill request containing app text and no save request. The autofill-service check needs the phone's secure `autofill_service` setting, which no instrumented test changes, so it is checked by hand at the phone run (SPEC T22). Verified at setup (SPEC T22, 2026-11-22, Poco F7): for every dialog and sheet that L-25 and L-29 show, including the `ModalBottomSheet` of the date filter and the date pickers, the Compose view's `parent` is the `DialogWindowProvider` (`InputPolicyTest`, `WindowOverlayPolicyTest`, `ListInputUiTest`, build/t22-full-2). The check by hand with a test autofill service is still open (M-14, T23).
- Keyboard learning: every covered field sits inside `NoPersonalizedLearning` (`ui/common`, Compose UI 1.7+), always. There is no `enabled` parameter, no `CompositionLocal` and no dependency on `SettingsStore` or the window policy: the flag is set on every input session of these fields from their first frame. Password fields need no wrapper (the password input type already stops learning):
```kotlin
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
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
  Verify at setup: whether `InterceptPlatformTextInput` and `PlatformTextInputInterceptor` still need `@OptIn(ExperimentalComposeUiApi::class)`, and the members of `PlatformTextInputMethodRequest`, in the pinned Compose BOM. Settled from the library classes (T16): both are still `@ExperimentalComposeUiApi`, and the sketch above compiles as written.
- There is no `LocalKeyboardPrivacy`: `MainActivity` provides no keyboard-related `CompositionLocal` ([architecture.md](architecture.md#mainactivity-startup-order)). The input-privacy test of [testing.md](testing.md) asserts `IME_FLAG_NO_PERSONALIZED_LEARNING` on each covered field with screenshot protection both off and on.
- The flag is a request that keyboards may ignore; it does not limit what a malicious keyboard sees (section 1.3).

## 12. Residual risks and documented limitations

| Risk | Why it remains | What the app does or says |
|---|---|---|
| Unencrypted backups | `PLAIN` mode with "Encrypt backups" off exports plaintext SQLite; share copies stay in `cacheDir/share/` until the next backup, the next cold start or the in-app one-hour sweep, and while the process is dead until the next launch (DEC-83) | Warning before every plaintext export (DEC-83, [ui.md](ui.md#85-unencrypted-backup-warning-dec-83)) |
| Flash remnants after turning encryption on | The old plaintext `doro-<n>.db` is deleted, but flash blocks can survive until reused; so can plaintext staging copies (a restore of a plain backup into an encrypted database, an export to `PLAIN`); earlier backups are unchanged | Relies on file-based encryption (§11 Device Assumptions); the confirmation says earlier backups are unchanged (section 4) |
| Keystore loss → KeyUnavailable | OS or OEM defects can lose the KEK; the wrapped DEK is then useless, by design without recovery | KeyUnavailable screen (DEC-86); backups are the only recovery. In `PASSWORD` mode with a live biometric slot the loss is noticed early and is not KeyUnavailable (DEC-86): a fingerprint unlock still works but reports `kekBroken` (section 3.7.3), a password unlock that hits the broken KEK falls back to the fingerprint route (sections 5.1, 5.5), and the kekBroken notice (section 7.3) offers a backup and points to "Forgot password?", whose fingerprint password reset replaces the KEK (section 4.3) |
| Newer key-state format after an app downgrade | A key-state with a larger `formatVersion` reads as invalid, so an older app shows KeyUnavailable, whose "Start fresh" would delete data the newer app could open | Install the newer version before choosing Start fresh |
| Recents thumbnail on API 28–32 | No public API hides only the thumbnail; with the lock or encryption on and screenshot protection off, Recents shows the last screen, and in `PASSWORD` mode after process death while the password is asked; the system keeps that snapshot outside the app's encryption | The lock's help text recommends screenshot protection (`biometric_recents_hint`, [ui.md](ui.md#94-biometric-lock)), and so does the encryption row when encryption is on and the lock is off (`encryption_recents_hint`, [ui.md](ui.md#93-database-encryption-and-startup-password)); API 33+ is covered (DEC-66) |
| Device without a screen lock | No biometric can be enrolled, storage keys are not bound to a user secret, and `PLAIN`/`DEVICE` open for anyone holding the phone | DEC-72 hint; `PASSWORD` mode still requires the password |
| Offline brute force of exported encrypted backups | No backoff off the device; each guess costs one SQLCipher 4 PBKDF2-HMAC-SHA512 at 256,000 iterations | Minimum 8 characters (DEC-64); the export dialog recommends a long passphrase (`backup_password_body`) |
| Same password for database and backups | An offline attack on a backup (A2) then yields the database password | Backup passwords are independent by design (DEC-83); the export dialog does not forbid reuse |
| Guessing on a held phone | Attempts are unlimited (DEC-62) | Backoff up to 5 min plus the PBKDF2 cost per guess; the delay uses elapsed realtime, so a changed system clock does not skip it, and a reboot re-applies it |
| The biometric lock is UI-only in `PLAIN`/`DEVICE` mode | The data opens without it (§11 App Lock); a fingerprint enrolled later, by anyone who knows the device PIN, also passes it | `PASSWORD` mode gives cryptographic protection, and there the biometric slot is invalidated by enrollment changes (section 3.7.1) |
| A fingerprint opens an encrypted database (`PASSWORD` mode with the lock on) | By the owner's choice (Q8), the password is no longer needed day to day: the protection equals that of the device's Class 3 biometric plus the Keystore. Anyone whose finger is enrolled on the device (for example a family member the owner enrolled) can open the app and use the fingerprint reset (section 4.3), and so can lock the owner out by replacing the password and declining the re-creation prompt: the owner then has no slot and does not know the new password, so only Erase and a backup remain; a backup is the recovery | Off by default; Settings explains it ([ui.md](ui.md#94-biometric-lock)); enrollment changes invalidate the slot; the startup password remains for every other path |
| Coercion | A person holding the owner can force a finger onto the sensor, which is easier than forcing out a password | Not prevented. The system lockdown option only protects while the phone stays locked; once it is unlocked, the fingerprint opens Doro again. The remedy is turning the lock off (then the password is asked at every cold start) |
| Enrollment changes | Adding or removing fingerprints invalidates the biometric key, so the next unlock asks the password once | Re-creation prompt after the password (section 7.8); the 14-day reminder keeps the password known |
| Forgotten password despite the reminder | If fingerprint unlock is lost (enrollment change, sensor failure) and the password is forgotten, only Erase and a backup remain | Reminder every 14 days (DEC-74), fingerprint reset while fingerprint unlock works (DEC-63), backup offer before setting a password (DEC-73) |
| Reminder timestamp and the clock | The reminder uses the wall clock; moving the clock can make it due early, and a user who sets the clock to just after the last password entry can postpone it | It is a memory aid, not a barrier: postponing it needs the fingerprint anyway; a clock set before the stored time makes it due at once (section 5.6) |
| `PASSWORD` mode without the biometric lock | The password is asked only at cold start (DEC-62); the open session stays readable on every return from background | Accepted; the lock is the remedy |
| Slot arming by someone who only knows the device PIN | In `PASSWORD` mode with the lock off, a return from background asks nothing (DEC-62), so a person holding the unlocked phone could enroll a finger and try to turn the lock on | Turning the lock on and Settings "Set up" ask the startup password before the fingerprint (DEC-65, section 7.1); the password-free re-creation offer exists only right after a password entry in the same foreground session (section 7.8). Residual: such a person can still read the open session (row "`PASSWORD` mode without the biometric lock") |
| Re-lock exemption window | Up to 10 min without a lock after an app-launched SAF, share, enrollment or credential trip (DEC-65) | One exemption at a time, cleared on result, on the next resume and on a failed launch |
| `SettingsStore` reset by corruption | A corrupt preferences file is replaced with defaults through `ReplaceFileCorruptionHandler` ([architecture.md](architecture.md) § SettingsStore), which turns the biometric lock and screenshot protection off and resets the backoff, and loses the reminder timestamp; a leftover slot is removed by `reconcileLock()` after the next password unlock (DEC-09); pinned by [testing.md](testing.md) I-44 | Accepted; a fail-closed handler needs a new decision first |
| Keyboards, accessibility services, clipboard | Software the user enabled can read typed or displayed text; copied text leaves the app; a screen read started by an assistant is out of scope | DEC-71 opt-out (best effort); system content capture is disabled by the app (section 8) |
| Task hijacking and overlays on old APIs, secrets in memory | See sections 9.4 and 6.5 | Accepted |
