# Database lifecycle

Purpose: how the database files are laid out, created, opened, verified, observed, exported, swapped, backed up, restored, re-keyed and recovered, and what is on disk after a crash at any step.

Related: [decisions.md](decisions.md) · [architecture.md](architecture.md) · [data-layer.md](data-layer.md) · [security.md](security.md) · [ui.md](ui.md) · [testing.md](testing.md) · [spec](../stakeholder-notepad-spec.md)

Conventions: `G` is the committed generation, `n` a new one. "Key" means the raw SQLCipher key bytes: `ByteArray(0)` in Plain mode, the 67-byte `x'<64 hex>'` form of the DEK otherwise ([security.md](security.md) § 3.4). Mode names `PLAIN`, `DEVICE`, `PASSWORD` are `KeyMode` values from security.md. Names in capitals (`EXPORT`, `COMMIT`, …) are `PipelineStep` values ([Pipeline steps and fault hook](#pipeline-steps-and-fault-hook)). Gate states, events and transition numbers (T1–T39) are those of [architecture.md](architecture.md) § SessionGate. "Origin" is the state an operation started from: `Ready`, `Corrupt`, `KeyUnavailable` or `NeedsPassword` ([`MaintenanceOrigin`](#interface-and-status)).

## Files on disk

`databases/` is the parent of `Context.getDatabasePath(…)`; `noBackupFilesDir` and `cacheDir` are the `Context` properties. All three are subdirectories of the app's data directory (`Context.dataDir`) on one filesystem, so `android.system.Os.rename` between them is an atomic `rename(2)`. The app is never `directBootAware`, so all of them live in credential-encrypted storage ([security.md](security.md) § 1.4).

| Path | Content | Created | Deleted | Why it never leaves the device |
|---|---|---|---|---|
| `databases/doro-<gen>.db` | The live database of the committed generation: a SQLCipher 4 file keyed with the raw DEK (`DEVICE`, `PASSWORD`) or a plaintext SQLite file (`PLAIN`), always opened through SQLCipher (DEC-08). Room schema `AppDatabase.VERSION`, `application_id` 0x444F524F ([data-layer.md](data-layer.md) INV-12) | Renamed in from `noBackupFilesDir/staging/` by the [generation commit](#generation-commit) (first run, key change, restore, Start fresh), or adopted by [startup reconciliation](#startup-reconciliation) | Only as an unreferenced generation after a newer one is committed: by DbMaintenance right after the commit (recovery origins) or once the reopen of the new generation succeeded (origin `Ready`), otherwise by the next startup reconciliation. Erase deletes unreferenced generations first and the committed one after the key-state file is gone ([Erase](#erase-forgotten-password)) | `android:allowBackup="false"` and the exclude-everything rules for cloud backup and device transfer (DEC-69; XML in [security.md](security.md) § 9.2) |
| `databases/doro-<gen>.db-wal`, `-shm` | WAL sidecars while a connection in WAL mode has the file open (DEC-89) | SQLite | SQLite, when the last connection closes; with the main file by `deleteDatabase` | Same rules. Never copied anywhere (DEC-89) |
| `databases/doro-<gen>.db-journal`, `-mj*` | Rollback journal: Room on devices where its `AUTOMATIC` journal mode is not WAL, and every [raw connection](#open-sequence) (journal mode DELETE). `-mj*` is SQLite's super-journal; this design never creates one | SQLite | In journal mode DELETE, by SQLite at commit. In journal mode TRUNCATE (Room's `AUTOMATIC` on low-RAM devices, and possibly the temporary Room instances) SQLite keeps the file at zero length after every commit and after `close()`: a zero-length journal is never hot and is harmless; it goes with the main file by `deleteDatabase`, or with the staging directory. A hot (non-empty) `-journal` of the live generation is never deleted by app code: SQLite needs it to roll back | Same rules |
| `noBackupFilesDir/db-state.json` (and `AtomicFile`'s siblings `db-state.json.new`, legacy `db-state.json.bak`) | Key-state file: format version, committed generation, key slot. Fields and encoding: [security.md](security.md) § 3.5 | First [commit](#generation-commit) (first run) | Rewritten whole by every commit; deleted only by Erase (`KeyVault.eraseState()`). Lifecycle code never renames it and never writes or deletes it except through `KeyVault`. `AtomicFile` itself deletes a stale `.new` on `failWrite()` and when a read finds the base file | `noBackupFilesDir` is always excluded from Auto Backup and device transfer by the platform; the rules also exclude domain `root` |
| `noBackupFilesDir/data-reset.pending` (and its `AtomicFile` siblings) | [Data-reset marker](#data-reset-marker): the decimal generation number of a Restore or Start fresh commit whose data reset has not completed | `MARK_RESET`, right before the commit | After `SettingsStore.onDataReplaced()` (`DATA_RESET`), on rollback, and by every startup reconciliation | As above |
| `noBackupFilesDir/staging/doro-<n>.db` (and `-journal`) | A generation under construction: key-change export, restore export, or empty database for first run or Start fresh | `EXPORT` (target pre-created empty) or `CREATE_EMPTY` | Renamed into `databases/` by the commit; otherwise deleted at the end of the operation, by the frame of the next operation (step 3) and at every cold start | As above |
| `noBackupFilesDir/staging/backup.db` (and `-journal`) | The verified [backup](#backup) export: plaintext SQLite or a SQLCipher 4 passphrase file | Backup `EXPORT` | After the SAF write, whatever its outcome; moved to `cacheDir/share/` by `PUBLISH_SHARE`; by the frame of the next operation (step 3) and at every cold start | As above |
| `noBackupFilesDir/restore/incoming.db` (and `-journal`) | Private copy of the document picked for [restore](#restore); validated and migrated in place | `COPY_INPUT` | At the end of the restore, whatever its outcome; by the frame of the next operation (step 3) and at every cold start | As above |
| `noBackupFilesDir/restore/reference.db` (and `-journal`) | Empty database created by this build: the expected schema for restore validation | `VALIDATE_INPUT` | Right after the schema facts are read; at the end of the restore; by the frame of the next operation (step 3) and at every cold start | As above |
| `cacheDir/share/<random UUID>.db` | Share-sheet copy of a verified backup, served by `BackupFileProvider` under the display name `doro-organizer-backup-YYYY-MM-DD.db` | `PUBLISH_SHARE` | At the start of the next backup, at the first startup reconciliation of a process (a cold start; not a re-run after `Restart`), after a committed key change ([security.md](security.md) § 4 step 5) and once one hour old ([Share-file lifetime](#share-file-lifetime), DEC-83) | `cacheDir` is always excluded from Auto Backup and device transfer |

Rules:

- No other file the app writes contains note data. `databases/` holds nothing but generation file sets.
- Generation file names match `^doro-(\d+)\.db(-wal|-shm|-journal|-mj[0-9A-Fa-f]+)?$`. A *generation file set* is every existing name with the same number, with or without its main file. Lifecycle code never touches other names in `databases/`.
- A generation file set is deleted only through `DbFiles.deleteGeneration(gen)`, which calls `net.zetetic.database.sqlcipher.SQLiteDatabase.deleteDatabase(File(databasesDir, "doro-$gen.db"))` (it deletes the main file, `-journal`, `-shm`, `-wal` and `-mj*`). Its return value is true when *any* file was deleted, so `deleteGeneration` reports success only when none of the main, `-wal`, `-shm` and `-journal` files exists afterwards. It is never called for a generation that has an open connection, and never for the committed generation: a generation is deleted only after a commit has made another one live (key change, confirmed Restore, confirmed Start fresh, DEC-85, DEC-86), or by Erase, which deletes the unreferenced generations first and the committed one after the key-state file is gone ([security.md](security.md) § 5.4).
- The temporary directories (`staging/`, `restore/`, `cacheDir/share/`) are emptied by deleting their contents (`File.deleteRecursively()` on each child). `staging/` and `restore/` are emptied at every cold start and by the frame of every operation except Erase, while it holds the operation mutex: nothing under them is ever committed data, and no other operation can be using them. `cacheDir/share/` follows its own [lifetime rules](#share-file-lifetime). A failed delete is logged by exception class only (DEC-70) and retried at the next cold start; in the frame it fails the operation with `Err(DatabaseUnavailable)` before `MaintenanceStarted` (a Transient failure).
- Every path stays out of logs and exception messages (DEC-70).

```kotlin
// package com.doro.organizer.data — used only by DatabaseHolder and DbMaintenance
class DbFiles(context: Context) {
    val databasesDir: File = context.getDatabasePath("doro-0.db").parentFile!!   // <dataDir>/databases
    val stagingDir = File(context.noBackupFilesDir, "staging")
    val restoreDir = File(context.noBackupFilesDir, "restore")
    val shareDir = File(context.cacheDir, "share")
    val resetMarker = File(context.noBackupFilesDir, "data-reset.pending")

    fun ensureDirs()                                  // mkdirs() for databasesDir, stagingDir, restoreDir, shareDir
    fun live(gen: Long) = File(databasesDir, "doro-$gen.db")
    fun generationsOnDisk(): Map<Long, Set<File>>     // number -> existing files of that set (main and sidecars)
    fun hasMainFile(gen: Long): Boolean               // live(gen).isFile
    fun deleteGeneration(gen: Long): Boolean          // see the rules above
    fun clearDir(dir: File)                           // deletes the children of stagingDir, restoreDir or shareDir
    fun deleteEverything()                            // Erase: every generation set, the three temp dirs, the marker
}
```

## Generations and commit

### Key-state semantics

- `db-state.json` is the single source of truth for which file is live and how it is keyed (DEC-80). It holds the `generation` (a long ≥ 1) and one key slot whose mode is `PLAIN`, `DEVICE` or `PASSWORD`. Field names, encodings, strict parsing and the `AtomicFile` write rules are owned by [security.md](security.md) § 3.5; only `KeyVault` reads or writes the file.
- The live database is `databases/doro-<generation>.db`. Every other generation on disk is unreferenced: it is never opened as the live database and is deleted by the next operation or startup that may delete it.
- The mode is readable without the Keystore or a password, so `SessionGate` can choose `NeedsPassword`, `Locked` or `Opening` while `Starting` (T2–T4).
- When a valid key-state exists, the mode is never inferred from a file header. The header is only checked for consistency with the mode ([Open sequence](#open-sequence) step 3). Headers decide only when the key-state file is missing ([Startup reconciliation](#startup-reconciliation)).
- Generation and slot change together, in one `AtomicFile` rewrite, which is the only commit point of every swap (DEC-80, DEC-87).

### Generation numbers

- A new generation is `n = 1 + max(G, every number among the generation file names in databases/ and noBackupFilesDir/staging/)`, with `G = 0` when no valid key-state exists, computed by DbMaintenance while it holds the operation mutex. A new file therefore never shares its name, or a stale `-wal`, `-shm` or `-journal`, with an older file, and no database file is ever overwritten in place.
- After Erase every generation file is gone, so numbering restarts at 1; there is nothing left to collide with.
- Numbers order generations by creation: the highest-numbered file was created last.

### Generation commit

`DbMaintenance.commitGeneration` is the only way a file becomes live (DEC-80, DEC-87). It is used by first run, key changes, restore and Start fresh; the in-place variant (`staged = null`) is used only to adopt an existing plaintext file at startup.

Preconditions: the operation mutex is held; `staged` lies in `noBackupFilesDir/staging/`, is closed, has passed [target verification](#target-verification) and has no non-empty `-journal` or `-wal` next to it (`verifyTarget` removes empty ones, and any `-shm`); no connection is open on `staged` or on `databases/doro-<gen>.db`; Room is closed or open on a different generation; `gen` was computed as above; DbMaintenance has set its `commitStarted` flag, which the [operation frame](#operation-frame) uses to classify a failure that escapes this function.

```kotlin
enum class CommitOutcome { Committed, NotCommitted, Unknown }
class CommitResult(val outcome: CommitOutcome, val cause: Throwable? = null)

// DbMaintenance; dispatchers.io. Nothing in here can be cancelled. Throwable, not Exception, is caught on purpose.
suspend fun commitGeneration(staged: File?, gen: Long, slot: KeySlot): CommitResult = withContext(NonCancellable) {
    val live = files.live(gen)
    var renamed = false
    try {
        if (staged != null) {
            fault(RENAME)
            RandomAccessFile(staged, "r").use { it.fd.sync() }      // 1. staged bytes are on disk
            check(files.deleteGeneration(gen))                      // 2. nothing stale under the new name
            Os.rename(staged.path, live.path)                       // 3. atomic: same filesystem
            renamed = true
            syncDirectory(files.databasesDir)                       // 4. the rename is durable before the commit
        }
        fault(COMMIT)
    } catch (t: Throwable) {                                        // nothing was committed yet, an Error included
        runCatching { if (renamed) files.deleteGeneration(gen) }    // never referenced
        return@withContext CommitResult(CommitOutcome.NotCommitted, t)
    }
    // From here on AtomicFile may already have renamed the key-state: no failure is "NotCommitted" until the read-back says so.
    val writeError: Throwable? = try { keyVault.commit(gen, slot); null } catch (t: Throwable) { t }   // 5. THE COMMIT POINT
    val committed = try {
        syncDirectory(noBackupFilesDir)                             // 6. the commit is durable before anything is deleted
        val state = keyVault.readState()                            // 7. throws KeyVaultException when the file is invalid
        state != null && state.generation == gen && state.slot.sameAs(slot)
    } catch (t: Throwable) {
        return@withContext CommitResult(CommitOutcome.Unknown, t)   // state unknown: delete nothing
    }
    if (committed) return@withContext CommitResult(CommitOutcome.Committed)
    runCatching { if (renamed) files.deleteGeneration(gen) }        // the previous state is still in force
    CommitResult(CommitOutcome.NotCommitted, writeError)
}

/** Best effort: makes renames inside dir durable. Never throws. */
fun syncDirectory(dir: File) {
    try {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    } catch (e: ErrnoException) { AppLog.w("syncDirectory failed: ${e.javaClass.simpleName} errno=${e.errno}")
    } catch (e: Exception) { AppLog.w("syncDirectory failed: ${e.javaClass.simpleName}") }
}
```

- `slot.sameAs(other)`: equal `mode`; byte-for-byte equal `outerIv`, `wrappedDek` and `innerIv`; equal `kdf.iterations`; byte-for-byte equal `kdf.salt`. A field that is absent must be absent on both sides (everything but `mode` is absent in `PLAIN`). `kekAlias` and `kdf.algorithm` are not members of `KeySlot` ([security.md](security.md) § 3.6): the key-state parser accepts only their constant values (§ 3.5), so a mismatch there reads back as `Unknown`, never as a different slot. The read-back is the only proof that the key-state on disk can unlock the new generation, so a serialization bug in any field (a wrong iteration count or salt reads back as a failed inner GCM tag, forever) must fail here, not at the next password prompt. `KeySlot` holds `ByteArray`s, so `==` must not be used.
- The read-back (step 7) decides the outcome, even when `KeyVault.commit` threw: an exception after `AtomicFile`'s rename would otherwise be mistaken for a failed commit. For the same reason steps 5 to 7 catch `Throwable`, an `OutOfMemoryError` during the JSON work or an `AssertionError` from a test fake included: any such failure ends in the read-back or in `Unknown`, never in `NotCommitted` without a read-back. Steps 1 to 4 and the `COMMIT` hook run before the key-state can have changed, so any `Throwable` there is `NotCommitted`.
- Step order is the durability argument: the staged file is synced before it is renamed, the rename is synced before the key-state names it, and the key-state is synced before any older generation is deleted. A power cut at any point therefore leaves either the old key-state with its file, or the new key-state with its file. Nothing before step 5 changes what is live, and nothing after it deletes until the outcome is known.
- `Os.rename` throws `ErrnoException`. `EXDEV` would mean the layout assumption above is broken (a programming error); any errno is reported as `NotCommitted` and classified by its cause ([Failure classification](#failure-classification)).
- Verify at setup: `androidx.core.util.AtomicFile` in core 1.19.1 writes `<name>.new`, syncs it in `finishWrite()` and then renames it over the base file, and only logs (does not throw) when that rename fails; the read-back covers the logging-only failure either way. `readFully()` discards (deletes) a stale `<name>.new` when the base file exists and never recovers it; when only `.new` exists it throws `FileNotFoundException`, which `KeyVault.readState()` must report as Missing (`null`), not as Invalid (the first-run `COMMIT` crash row of the [matrix](#crash-safety-matrix) depends on it; [security.md](security.md) § 3.5 words it the same way).
- Verify at setup: `Os.open(dir, O_RDONLY)` plus `Os.fsync` on a directory succeeds for `databases/` and `noBackupFilesDir` on the API 28 and API 35 emulators and on ext4 and f2fs devices. If it fails with `EINVAL` or `EACCES`, keep the call best-effort as above.

| Outcome | Meaning | Caller does |
|---|---|---|
| `Committed` | The read-back shows `(gen, slot)` | Continue with `DATA_RESET` (Restore, Start fresh), `REOPEN`, `DELETE_OLD`, `DELETE_KEK` as the operation requires. Failures after this point never undo the commit |
| `NotCommitted` | The previous state is in force; the new file was deleted (or never renamed) | Roll back: keep and reopen the old generation, delete the marker, zero the new key, report the error classified from `cause` |
| `Unknown` | The key-state file could not be read back, or a `Throwable` escaped steps 5 to 7 | Delete nothing, zero every key the operation holds, leave the data-reset marker, end with outcome `Restart` so that startup reconciliation decides from the file on disk; report `Err(OutcomeUnknown)`, never `Unexpected` ([User-facing messages](#user-facing-messages)) |

### Data-reset marker

A Restore or Start fresh commit replaces the data, so navigation, remembered selections and filters must be reset ([architecture.md](architecture.md) § Data reset). `SettingsStore.onDataReplaced()` runs right after the commit (`DATA_RESET`); the marker closes the window between the two, so the reset happens if and only if the new data was committed.

1. `MARK_RESET`: before `RENAME`, DbMaintenance writes `noBackupFilesDir/data-reset.pending` through `androidx.core.util.AtomicFile`, containing `n` in decimal ASCII, and then calls `syncDirectory(noBackupFilesDir)`. `AtomicFile` syncs the file but not the directory entry its rename creates; without the directory sync a power cut after the key-state rename could keep the commit and lose the marker, and the replaced data would then keep the old navigation ids (an id can name a different row after a restore, [data-layer.md](data-layer.md)). The marker is therefore durable before `RENAME` and `COMMIT`.
2. `DATA_RESET`: after `Committed`, DbMaintenance calls `SettingsStore.onDataReplaced()` and then deletes the marker with `AtomicFile.delete()`. The delete needs no directory sync: a lost delete only repeats a harmless reset.
3. On `NotCommitted` the marker is deleted without a reset. On `Unknown` it is left for startup.
4. Every startup reconciliation (step 5 below): if the marker exists, call `SettingsStore.onDataReplaced()` when the key-state is Valid and its `generation` equals the marker's number, or when the marker cannot be parsed (it cannot happen through `AtomicFile`; a reset is the safe side); then delete the marker. A Missing or Invalid key-state means that nothing was committed: every commit leaves a Valid key-state naming `n` (`AtomicFile` leaves either the old or the new file), so a restore or Start fresh from `KeyUnavailable` that died before its commit must not reset. If the process died after `onDataReplaced()` but before the marker was deleted, startup resets once more; that only increments `dataEpoch` again, which is harmless.

Key changes and backups never write the marker: they keep the data, so navigation and remembered selections stay valid.

### Startup reconciliation

`DbMaintenance.reconcileAtStartup(): StartupDecision` is the reconciliation part of effect E1 ([architecture.md](architecture.md) § SessionGate). It runs on `dispatchers.io` while the gate is `Starting`, after the first `SettingsStore` value is available, holding the operation mutex. It opens no SQLite connection, needs no key, and reads `db-state.json` only through `KeyVault.readState()`; it never writes, renames or deletes `db-state.json*` itself ([security.md](security.md) § 3.5). It runs in every E1, which is also the E1 after `MaintenanceFinished(Restart)` and `Aborted` from `NeedsPassword` (T15, T30, T31d) in the same process.

```kotlin
sealed interface StartupDecision {
    data class Proceed(val generation: Long, val mode: KeyMode) : StartupDecision   // key-state valid
    data object FirstRun : StartupDecision                                          // no key-state, no database file
    data class AdoptPlain(val generation: Long) : StartupDecision                   // no key-state, plaintext files only
    data object KeyUnavailable : StartupDecision                                    // DEC-86
}
```

1. `files.ensureDirs()`.
2. Empty `noBackupFilesDir/staging/` and `noBackupFilesDir/restore/` (DEC-80). Empty `cacheDir/share/` only on the first reconciliation of the process, a cold start (DEC-83; DbMaintenance keeps an in-memory flag `shareSwept`). A re-run after `Restart` happens in the same process, and a receiver (a mail app that reads the attachment when it sends) may still be reading a share file that is younger than one hour: those files are left to their timers and `sweepShareFiles()`. By the commit protocol none of the emptied directories ever holds the only copy of committed data.
3. Read the key-state with `KeyVault.readState()`: a `KeyState` is **Valid**; `null` (no base file, whether or not a stale `.new` remains) is **Missing**; a `KeyVaultException(InvalidState)` (unparseable, unknown `formatVersion`, field missing or of the wrong length, unreadable; [security.md](security.md) § 3.5, § 5.2) is **Invalid**.
4. List the generation file sets in `databases/` (`files.generationsOnDisk()`). When the key-state is Missing, read the first 16 bytes of every main file with the conservative `RawConnections.isPlaintext`: a file is *plaintext* if and only if they equal `SQLite format 3\0`. A file shorter than 16 bytes, or one that cannot be read, is not plaintext (the safe side: such a file never gets adopted).
5. Apply the [data-reset marker](#data-reset-marker) rule, then delete the marker.
6. Decide and delete:

| Key-state | Generation files in `databases/` | Decision | Deleted now | E1 raises |
|---|---|---|---|---|
| Valid `(G, mode)` | `doro-G.db` exists and is not empty | `Proceed(G, mode)` | Every other generation file set (`deleteGeneration`) | `StartupLoaded(mode, lock)` |
| Valid `(G, mode)` | `doro-G.db` missing or 0 bytes | `Proceed(G, mode)` | Nothing | `StartupLoaded(mode, lock)`; the open reports Corrupt after authentication ([Open sequence](#open-sequence) step 2) |
| Missing | No main file (nothing, or orphan sidecars only) | `FirstRun` | Nothing | `StartupLoaded(PLAIN, lock)`, after the first-run settings reset below |
| Missing | At least one main file, and every main file is plaintext | `AdoptPlain(highest number)` | Nothing | `StartupLoaded(PLAIN, lock)` |
| Missing | At least one main file that is not plaintext (encrypted, damaged, short, unreadable) | `KeyUnavailable` | Nothing (DEC-86) | `StartupKeyFailed(lock)` |
| Invalid | Anything, including nothing or plaintext files only | `KeyUnavailable` | Nothing (DEC-86; see the third note) | `StartupKeyFailed(lock)` |

7. First-run settings reset: for `FirstRun` only, before E1 raises its event, apply step 4 of the Erase flow ([security.md](security.md) § 5.4: every security setting back to its default, remembered selections cleared) and call `SettingsStore.onDataReplaced()`. On a fresh install, and after "Clear storage", this changes nothing. After an Erase interrupted between deleting the files and resetting the settings, it completes the Erase. `lock` in the event is read after this reset.
8. Remember the decision in memory for [Completing startup in Opening](#completing-startup-in-opening) and release the mutex.

Notes:

- Generation files are deleted at startup only when the key-state is valid and names a file that exists. With a missing or invalid key-state nothing in `databases/` is deleted (DEC-86); a missing live file deletes nothing either, because the cause is unknown.
- Deleting unreferenced generations needs no authentication: they are either superseded by a committed generation or were never committed, so they are not the user's database (DEC-85 concerns the committed one). This also removes the plaintext file left behind when a crash interrupted the deletion after encryption was turned on.
- An invalid key-state means `KeyUnavailable` whatever files exist, plaintext-only or none at all (DEC-86), because a plaintext file next to an invalid key-state may be a superseded pre-encryption generation while the encrypted live one is the unreadable one, and because an invalid key-state file is never rewritten automatically ([security.md](security.md) § 3.5): the user's explicit Restore or Start fresh is what rewrites it.
- A missing key-state with only plaintext files follows a crash between the first-run `RENAME` and its `COMMIT`, or between the `RENAME` and `COMMIT` of an adoption (or outside interference). Restore and Start fresh started from `KeyUnavailable` never produce it: the unreadable files they replace stay on disk until their commit, so a crash leaves the `KeyUnavailable` rows in force. Adopting the highest-numbered file keeps the newest data, because a file reaches `databases/` only after it was verified and, for Restore, confirmed.

### Completing startup in Opening

`DbMaintenance.completeStartup()` is the first call of effect E2, before `KeyVault` produces a key. It takes the operation mutex and executes the remembered decision; after success, or for `Proceed`, it is a no-op, so `Retry` (T20) simply calls it again. It returns `StartupCompletion { Done, Transient, KeyUnavailable }`, which E2 maps to continuing, `OpenFailed(Transient)` and `KeyFailed`.

| Decision | Work | On success |
|---|---|---|
| `Proceed` | None | — |
| `FirstRun` | `PRECHECK` (`MARGIN`, [Free-space precheck](#free-space-precheck); a failure is `StorageFull`); `n` per [Generation numbers](#generation-numbers) (1 on a clean install); [create an empty database](#empty-databases) at `staging/doro-<n>.db`; [verify](#target-verification) it; `commitGeneration(staged, n, keyVault.prepare(PLAIN, null).slot)` | Delete every other generation file set (orphan sidecars). Forget the decision |
| `AdoptPlain(g)` | `commitGeneration(null, g, keyVault.prepare(PLAIN, null).slot)` | Delete every other generation file set. Forget the decision |
| `KeyUnavailable` | Not reached: E1 raised `StartupKeyFailed`. After the re-run below it returns `StartupCompletion.KeyUnavailable` | — |

- The live Room instance is built only in `Opening` (DEC-90), and the first-run database needs a temporary Room instance (`onCreate`), so it is created here rather than in E1. Creating it in E2 also gives a full disk the `Retry` path: a failure classified `StorageFull` or `Transient` returns `StartupCompletion.Transient` (E2 raises `OpenFailed(Transient)`, T19; `completeStartup` works only in `PLAIN`, so never with `viaPassword`, never T18) and the decision stays remembered; any other failure is a programming error.
- `NotCommitted` is handled like a failure (`Transient`, decision kept). `Unknown` also returns `Transient`, but the remembered decision is discarded: the next call (after `Retry`) first repeats steps 2–6 of [Startup reconciliation](#startup-reconciliation), without the settings reset, and executes the new decision. A key-state that was in fact written then reads as `Proceed`; a `KeyUnavailable` result is `StartupCompletion.KeyUnavailable`, which E2 reports as `KeyFailed` (T23).
- After this call E2 continues as [architecture.md](architecture.md) § States, events and effects describes (the owner of the E2 steps): it reads the now committed state with `KeyVault.readState()` for the generation, obtains the key with `KeyVault.unlock(passwordBytes)` ([security.md](security.md) § 3.6, § 5.1) and calls [`DatabaseHolder.open(state.generation, rawKey)`](#open-sequence).

## DatabaseHolder

`DatabaseHolder` (package `data`) alone owns the live Room instance and its key (DEC-81). Nothing else stores an `AppDatabase` or a DAO; repositories reach DAOs per call through `withDb` or per subscription through `observe` ([data-layer.md](data-layer.md) § Model overview, I-41 in [testing.md](testing.md)).

### Interface

```kotlin
// package com.doro.organizer.data
interface DatabaseHolder {
    val state: StateFlow<HolderState>
    /** Effect E2 only. Takes ownership of rawKey: kept while Room is open, zeroed by the holder on failure. */
    suspend fun open(generation: Long, rawKey: ByteArray): OpenResult
    /** Effect E3. Drains, closes Room, zeroes the key. Never deletes a file (DEC-85). */
    suspend fun close()
    fun <T> observe(query: (AppDatabase) -> Flow<T>): Flow<T>
    suspend fun <T> withDb(block: suspend (AppDatabase) -> T): AppResult<T>
    /** DbMaintenance only. Returns null when the block asks to stay closed. */
    suspend fun exclusive(block: suspend (ExclusiveScope) -> Reopen): OpenResult?
    /**
     * DbMaintenance only (restore from Ready). Takes the transition Mutex; returns liveKey.copyOf() only in state Open, else null.
     * The caller zeroes the copy. close(), a failed open or reopen, Reopen.StayClosed and Reopen.Generation(newKey != null)
     * zero the live key only while holding the same Mutex, so a copy is never taken from a half-zeroed array.
     */
    suspend fun copyLiveKey(): ByteArray?
}

sealed interface HolderState {
    data object Closed : HolderState
    data object Opening : HolderState
    class Open(val db: AppDatabase, val generation: Long, val dataset: Long) : HolderState {   // identity equality on purpose
        internal val activeFlows = AtomicInteger()                                              // observe collections running on db
    }
    data object Draining : HolderState
    data object Exclusive : HolderState
}
sealed interface OpenResult {
    data object Opened : OpenResult
    data class Failed(val kind: OpenFailureKind) : OpenResult
}
enum class OpenFailureKind { Corrupt, Transient }   // the kind of GateEvent.OpenFailed and of MaintenanceOutcome.ReopenFailed

class ExclusiveScope(val generation: Long, val liveKey: ByteArray)   // liveKey is a loan: bind it, never zero or keep it
sealed interface Reopen {
    data object Same : Reopen                                          // same generation, same key
    class Generation(val generation: Long, val newKey: ByteArray?, val newData: Boolean) : Reopen   // newKey null: same key
    data object StayClosed : Reopen
}
fun interface CorruptionListener { fun onRuntimeCorruption() }       // implemented by SessionGate
fun interface FlowProblemListener { fun onFlowProblem(error: AppError) }   // implemented by AppContainer: posts to AppMessages (thread-safe)
```

- The holder needs no `KeyMode`: an empty key means plaintext, any other key means encrypted.
- Construction ([architecture.md](architecture.md) § Event wiring): `DefaultDatabaseHolder(files, dispatchers, corruptionListener: () -> CorruptionListener, flowProblemListener: FlowProblemListener)`. The `CorruptionListener` is a provider, because SessionGate depends on the holder; it is resolved at the first corruption event and is never cached before. `FlowProblemListener` is a plain value (AppContainer's lambda posts to `AppMessages`). `DbMaintenance` takes its `MaintenanceListener` as a `() -> MaintenanceListener` provider for the same reason.
- E2 maps `OpenResult.Opened` to the gate event `Opened` and `Failed(kind)` to `OpenFailed(kind)` (T16–T19). E2 calls `open` with the generation of the key-state that `KeyVault` just read.
- `toString()` of `HolderState.Open` and `ExclusiveScope` prints the generation only, never a key (DEC-70).

### States

| State | Meaning | `observe` emits | `withDb` |
|---|---|---|---|
| `Closed` | No Room instance. Initial state; after `close()`; after a failed open or reopen; after `Reopen.StayClosed` | Nothing | Returns `Err(DatabaseUnavailable)` at once |
| `Opening` | The [open sequence](#open-sequence) runs: in E2, or as the reopen at the end of exclusive mode | Nothing | Waits |
| `Open(db, generation, dataset)` | Room is usable | The DAO flows of `db` | Runs the block |
| `Draining` | `exclusive` or `close` was called; calls already running finish | Nothing | New calls wait |
| `Exclusive` | Room is closed; DbMaintenance owns the files | Nothing | Waits |

- Transitions: `open`: Closed → Opening → Open or Closed. `exclusive`: Open → Draining → Exclusive → Opening → Open or Closed (or Exclusive → Closed for `StayClosed`). `close`: Open → Draining → Closed. One internal transition `Mutex` serializes these transitions and `copyLiveKey`; `exclusive`, `close` and `copyLiveKey` wait for it. Calling `open` in any state but `Closed` is a programming error (`IllegalStateException`). `exclusive` in `Closed` returns `Failed(Transient)` without running the block ([Exclusive mode](#exclusive-mode) step 1): the gate may have gone `Ready` → `Corrupt` and run E3 between the user's action and the call. Because transitions are serialized, `exclusive` never observes `Opening`, `Draining` or `Exclusive`; doing so is a programming error.
- `dataset` is an in-memory counter, incremented when an exclusive block reopens with `newData = true` (a Restore committed from `Ready`). A `withDb` call that waited across that reopen fails with `DatabaseUnavailable` instead of running against the replaced data.

### Open sequence

`open(generation, rawKey)` runs on `dispatchers.io`; state Closed → Opening. In E2 the key was produced by `KeyVault` moments earlier: in `DEVICE` and `PASSWORD` mode it is proven by the GCM tags, in `PLAIN` mode it is the empty key, so no failure below is ever a wrong password ([security.md](security.md) § 5.1, DEC-62). That is also why the file checks come after the key: the Corrupt screen appears only after authentication (DEC-85).

1. Precondition: `SqlcipherEngine.init()` has run (E1: `System.loadLibrary("sqlcipher")`, the process-wide SQLCipher settings of [security.md](security.md) § 3.4, release logger off; [architecture.md](architecture.md) § SessionGate). `DbFiles.deleteGeneration`, used by reconciliation, calls the same SQLCipher class after that initialization.
2. Presence: `f = files.live(generation)`. If `!f.isFile` or `f.length() == 0L`: **Corrupt**. Neither SQLite nor Room may ever create the live file: the raw connection never passes `CREATE_IF_NECESSARY`, and Room only ever sees a file that passed step 4.
3. Header: `RawConnections.readsAsPlaintext(f)` must equal `rawKey.isEmpty()`. A mismatch is **Corrupt**: the committed key-state is authoritative and the commit protocol only commits verified pairs. An `IOException` other than a header shorter than 16 bytes (`EIO`, `EACCES`, `EMFILE`) propagates and is **Transient** ([Failure classification](#failure-classification), context Opening): a read error on a plaintext file must never become the Corrupt screen, whose Start fresh deletes the data.
4. Raw verification on a dedicated connection, closed in `finally`:
   1. `SELECT count(*) FROM sqlite_master WHERE name <> 'android_metadata'` must be > 0. A file that is not a database for this key fails here with `SQLiteNotADatabaseException`; an empty database returns 0 (**Corrupt**).
   2. `PRAGMA user_version`: greater than `AppDatabase.VERSION` is **NewerVersion**; less than 1 is **Corrupt**.
   3. `PRAGMA application_id` must be 0x444F524F, else **Corrupt**.
   4. `PRAGMA quick_check` must return exactly one row, `ok`, else **Corrupt** (DEC-85).
5. Build Room on `f` with the same `rawKey` ([Room builder](#room-builder)) and force the open with `db.openHelper.writableDatabase`. This runs Room's migrations when an app update raised the schema version (DEC-88) and Room's identity check; `onCreate` never runs because `user_version` ≥ 1.
6. Success: state `Open(db, generation, dataset)`, return `Opened`. Any failure: close whatever is open, zero `rawKey`, state `Closed`, then return `Failed(kind)` or rethrow, as the [Failure classification](#failure-classification) says for the context "Opening".

```kotlin
// package com.doro.organizer.data
internal object RawConnections {
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)   // 16 bytes

    /** Strict: only a file shorter than 16 bytes counts as "not plaintext"; every other IOException propagates (Transient). Used by the open sequence. */
    fun readsAsPlaintext(file: File): Boolean = RandomAccessFile(file, "r").use { f ->
        try { ByteArray(16).also { f.readFully(it) }.contentEquals(MAGIC) } catch (e: EOFException) { false }
    }

    /** Conservative: unreadable counts as not plaintext. Used by reconciliation step 4 and by verifyTarget, where "not plaintext" is the safe answer. */
    fun isPlaintext(file: File): Boolean = try { readsAsPlaintext(file) } catch (e: IOException) { false }

    /** Opens an existing file read-write. Never creates it, never enables WAL, never deletes on corruption, never creates android_metadata. */
    fun open(file: File, key: ByteArray): SQLiteDatabase =   // net.zetetic.database.sqlcipher.SQLiteDatabase
        SQLiteDatabase.openDatabase(file.path, key, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            NoDeleteErrorHandler, if (key.isEmpty()) null else CipherCompatibilityHook)
}

object NoDeleteErrorHandler : DatabaseErrorHandler {          // net.zetetic.database.DatabaseErrorHandler
    override fun onCorruption(dbObj: SQLiteDatabase, exception: SQLiteException) = Unit
}

internal fun SQLiteDatabase.longOf(sql: String): Long = query(sql).use { c -> check(c.moveToFirst()); c.getLong(0) }
internal fun SQLiteDatabase.column0(sql: String): List<String?> =
    query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

internal class CheckFailed(val failure: DbFailure) : Exception("open check failed")   // constant message (DEC-70)

internal fun verifyLive(file: File, key: ByteArray) = RawConnections.open(file, key).use { db ->
    if (db.longOf("SELECT count(*) FROM sqlite_master WHERE name <> 'android_metadata'") == 0L) throw CheckFailed(DbFailure.Corrupt)
    val version = db.longOf("PRAGMA user_version")
    if (version > AppDatabase.VERSION) throw CheckFailed(DbFailure.NewerVersion)
    if (version < 1) throw CheckFailed(DbFailure.Corrupt)
    if (db.longOf("PRAGMA application_id") != AppDatabase.APPLICATION_ID.toLong()) throw CheckFailed(DbFailure.Corrupt)
    if (db.column0("PRAGMA quick_check") != listOf("ok")) throw CheckFailed(DbFailure.Corrupt)
}
```

- `CipherCompatibilityHook` is the `SQLiteDatabaseHook` whose `postKey` applies the per-connection setting of [security.md](security.md) § 3.4; it is passed for every connection with a non-empty key, raw or Room. `AppDatabase.VERSION` is the `const val` next to `APPLICATION_ID` in the companion of `AppDatabase` ([data-layer.md](data-layer.md) § Entities).
- Why a raw connection first: on a 0-byte or schema-less file Room would run `onCreate` and silently produce an empty database; on a damaged file Room's migration or identity code would fail with an `IllegalStateException` that cannot be told apart from a programming error. The raw checks classify those cases before Room sees the file.
- sqlcipher-android keys a connection and then reads `sqlite_schema` itself only when the key is non-empty; for an empty key step 4.1 is the first read, which is why it is explicit.
- A raw connection opened read-write without the WAL flag switches the file to journal mode DELETE when it opens (sqlcipher-android applies its default journal mode), which checkpoints any WAL left by an unclean exit; Room switches the file back to WAL when it opens. Both switches are ordinary journaled SQLite transactions on page 1, so the live file is never byte-identical across an open, but its content is.
- `NO_LOCALIZED_COLLATORS` keeps the AOSP-derived connection code from running `CREATE TABLE IF NOT EXISTS android_metadata`, which would otherwise write a table into the live file, into an untrusted restore candidate before it is validated, and into export targets, and would make a schema-less file look non-empty. Whatever creates `android_metadata` anyway (Room's open helper, an older backup) is ignored on both sides of every schema comparison.
- `NoDeleteErrorHandler` makes the intent explicit: sqlcipher-android's `DefaultDatabaseErrorHandler.onCorruption` already returns without deleting when `SQLiteDatabase.hasCodec()` is true (always, in this build), and Room's `SupportHelper` passes no handler, so the default applies to Room's connections.
- Step 4.4 reads every page; its cost is part of the cold-start budget ([architecture.md](architecture.md) § Performance budgets).
- Verify at setup (sqlcipher-android 4.20.0, API 28 and 35): (1) a raw `OPEN_READWRITE` connection reports `PRAGMA journal_mode` = `delete`, and the `-wal` of a file left by an unclean exit is gone after it closes; (2) the `net.zetetic.database.DatabaseErrorHandler.onCorruption` signature; (3) whether `SQLiteDatabaseHook.postKey` is called for an empty key; (4) the exception classes for a tampered page 1 and a tampered page 2 of an encrypted database, at open (`quick_check`) and at runtime (a query that reads the page): the Opening and Runtime rows of the [classification tables](#classification-table) must match the observed classes, and L-12 ([testing.md](testing.md)) covers the encrypted case; (5) that `SQLiteDatabase.NO_LOCALIZED_COLLATORS` exists in sqlcipher-android and that, with it, a raw connection creates no `android_metadata` table, while Room's open helper may.

### Room builder

```kotlin
// DatabaseHolder; also used for temporary instances by DbMaintenance (see Empty databases, Restore)
fun buildRoom(context: Context, file: File, rawKey: ByteArray, temporary: Boolean): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
        .openHelperFactory(SupportOpenHelperFactory(rawKey, if (rawKey.isEmpty()) null else CipherCompatibilityHook, false))
        .addCallback(AppDatabase.CALLBACK)
        .addMigrations(*AppDatabase.MIGRATIONS)
        .apply { if (temporary) setJournalMode(RoomDatabase.JournalMode.TRUNCATE) }
        .apply { DebugRoomHooks.install(this) }      // src/debug: query callback per security.md § 10; no-op in release
        .build()
```

- `SupportOpenHelperFactory(password, hook, enableWriteAheadLogging = false)`: the third argument only seeds the helper. Room calls `setWriteAheadLoggingEnabled(…)` for its journal mode before the first open; the live instance keeps Room's default `JournalMode.AUTOMATIC` (DEC-89: WAL, or a rollback journal on low-RAM devices). Temporary instances use `TRUNCATE`, so the helper opens a single non-WAL connection; this keeps one PBKDF2 run for a passphrase-keyed restore candidate. If `TRUNCATE` is applied as SQLite's `journal_mode=TRUNCATE`, a zero-length `-journal` stays next to the file after `close()`; [target verification](#target-verification) tolerates and removes it, and nothing else may assume that no `-journal` exists.
- Never on any builder: `fallbackToDestructiveMigration*` (DEC-88), the four-argument factory constructor with `minimumSupportedVersion` (DEC-08), `setAutoCloseTimeout` (Room would close the database behind the holder), `createFromAsset`/`createFromFile`/`createFromInputStream`, `enableMultiInstanceInvalidation`, `allowMainThreadQueries`, `setDriver`, `FrameworkSQLiteOpenHelperFactory`. Room 3's `allowDataLossOnRecovery` does not exist in Room 2.8 and stays banned by the source rules (DEC-88).
- The factory, the helper and SQLCipher's connection pool keep a reference to `rawKey` and key reader connections opened later from it, so the array is zeroed only after `RoomDatabase.close()` ([security.md](security.md) § 6.4).
- The database name is the absolute path (`file.absolutePath`); `Context.getDatabasePath` accepts absolute paths, which is what places temporary instances under `noBackupFilesDir`.
- Per-connection settings belong in a `SQLiteDatabaseHook.postKey`: sqlcipher-android calls the hook for every connection its pool opens, whatever the key, while Room's callbacks run on one connection only. Today the hook carries only the setting of [security.md](security.md) § 3.4 and is passed for non-empty keys. A larger `PRAGMA cache_size`, if the performance budget ever needs one ([data-layer.md](data-layer.md) § Size and performance), goes into the same hook, which is then passed for every key.
- Verify at setup: Room 2.8.5 accepts an absolute path as the name; `RoomDatabase.openHelper` is available in open-helper-factory mode and `openHelper.writableDatabase` runs migrations and the identity check; `JournalMode.TRUNCATE` makes sqlcipher-android open a single non-WAL connection. Also: which `PRAGMA journal_mode` a temporary instance reports, and which sidecar files exist after `close()` (a zero-length `-journal` is expected and tolerated; a non-empty one or a `-wal` would be a defect); whether `createEmptyDatabase` leaves an `android_metadata` table (it is ignored either way).

### Observation

```kotlin
override fun <T> observe(query: (AppDatabase) -> Flow<T>): Flow<T> =
    state.flatMapLatest { s ->
        if (s !is HolderState.Open) emptyFlow()
        else query(s.db)
            .onStart { s.activeFlows.incrementAndGet() }
            .onCompletion { s.activeFlows.decrementAndGet() }            // also on cancellation: exclusive mode waits for it
            .retryWhen { e, attempt -> e.isEnvironmental() && attempt < 3 && state.value === s && run { delay(2_000); true } }
            .catch { e -> onFlowError(e, s) }                            // completes silently or rethrows
    }
```

- The outer flow never completes (`state` is a `StateFlow`). While the holder is not `Open` it emits nothing, so a downstream `stateIn` keeps showing the last list; when a new `Open` arrives the query subscribes to the new instance and emits again. Flows therefore survive every swap without ViewModel code (DEC-81). `HolderState.Open` uses identity equality, so a reopen of the same generation is still a new state.
- `isEnvironmental()` is true for the Transient classes and storage full of the base mapping ([Classes](#classes)); `CancellationException` is never environmental. `retryWhen` re-subscribes to the same instance after 2 s, at most 3 times, as long as the instance is still current.
- `onFlowError(e, s)`: `CancellationException` is rethrown. If `s` is no longer the current state, the instance was retired by `exclusive` or `close`; the error (typically Room's `IllegalStateException` after close) is swallowed and the inner flow completes. Corruption (`SQLiteDatabaseCorruptException`, `SQLiteNotADatabaseException`) from the current instance is [reported](#runtime-corruption-routing) and the inner flow completes. An environmental error that survived the retries completes the inner flow and calls `FlowProblemListener.onFlowProblem(StorageFull or DatabaseUnavailable)`, so the shell shows a message; the list keeps its last value and re-subscribes at the next `Open`. A transient disk error or a full disk must not crash the app: a crash would lose the editor draft typed since the last `ON_STOP` (DEC-37) and could repeat on every start. Anything else is rethrown and crashes the app ([Failure classification](#failure-classification), context "Runtime"); the next start re-verifies the file.
- Repositories add `distinctUntilChanged`, mapping and `flowOn` after `observe` ([data-layer.md](data-layer.md) § Reactivity). Never collect a DAO flow obtained outside `observe`; never keep `s.db`.

### One-shot access: withDb

```kotlin
override suspend fun <T> withDb(block: suspend (AppDatabase) -> T): AppResult<T> {
    coroutineContext[Lease]?.let { lease -> return runClassified(lease.open) { block(lease.open.db) } }  // nested call
    val open = acquire() ?: return AppResult.Err(AppError.DatabaseUnavailable)
    try { return withContext(Lease(open)) { runClassified(open) { block(open.db) } } }
    finally { release() }
}
```

- `acquire()`: under the state lock (a short-held lock that guards the in-flight count; it is not the transition `Mutex`), if the state is `Open`, increment the in-flight count and return it. `Closed`: return null at once. `Opening`, `Draining`, `Exclusive`: remember the `dataset` of the last `Open`, suspend until the state is `Open` or `Closed`, then return null if it is `Closed` or its `dataset` changed, otherwise try again. The increment happens only under the lock and only in `Open`, so once `Draining` is set no new call can enter.
- `release()` decrements the count in `finally`, also on cancellation; exclusive mode and `close` wait for it to reach 0.
- `Lease` is a coroutine-context element carrying the instance. A nested call (a helper that is itself a `withDb` caller; the repositories use none today, because [data-layer.md § Transactions and results](data-layer.md#transactions-and-results) routes nested work through `internal` helpers such as `insertNew(db, …)` that take the open `db`) reuses the outer instance instead of acquiring again; acquiring again could wait for a drain that is itself waiting for the outer call. The `Lease` reaches only calls made in the same coroutine, so nested work must be an internal (non-public) suspend function called directly inside the outer `withDb` and its `withTransaction`. It never detaches into `applicationScope` and never calls the public repository method: that would start a coroutine without the `Lease`, which would acquire again and could deadlock against a drain, and its write would run outside the outer transaction (DEC-33).
- `runClassified` maps exceptions per [Failure classification](#failure-classification), context "Runtime": corruption is reported and becomes `Err(DatabaseUnavailable)`; `SQLiteFullException` becomes `Err(StorageFull)`; the transient I/O classes become `Err(DatabaseUnavailable)`; `CancellationException` and everything else is rethrown.
- Repositories call `withDb` once per public method and run their transaction inside it; writes run detached in `applicationScope` ([architecture.md](architecture.md) § Layering and data flow, rule 6). DbMaintenance never calls `withDb` and never runs inside it.

### Exclusive mode

`exclusive(block)` is called only by DbMaintenance, holding the operation mutex, with the gate in `Maintenance(op, Ready)`.

1. Precondition, checked under the transition `Mutex`: state `Open`. Otherwise (state `Closed`) it returns `Failed(Transient)` without running the block, and DbMaintenance ends the operation with `DatabaseUnavailable` and outcome `ReopenFailed(Transient)`. This only happens when the gate left `Ready` (for example to `Corrupt`) between the user's action and this call; SessionGate then ignores the maintenance events of a Backup or key change (T35), and a Restore continues as a restore from `Corrupt` ([Operation frame](#operation-frame) step 6). Were the gate still in `Maintenance(op, Ready)`, T29 would lead to `Opening(transientError)`, whose Retry reopens the database.
2. State `Draining`: `flatMapLatest` cancels every inner flow; new `withDb` calls wait.
3. Wait until the in-flight count is 0: every started transaction has committed or rolled back.
4. In `NonCancellable`: wait until the instance's `activeFlows` count reaches 0, polling every 10 ms for at most 2 s (journal mode does not matter: on a rollback-journal device there is no `-wal` to watch); on timeout log the constant `exclusive: flow drain timeout` and continue. Then `db.close()`; Room closes its idle connections at once. Verify at setup: whether Room 2.8.5's `close()` waits for a running query (a cancelled flow query) to finish. No correctness property depends on the wait: maintenance reads go through SQLite's locking, nothing ever byte-copies a live file (DEC-89), and older generation files are removed by unlink, which is safe while a descriptor is open. It makes "no `-wal` after close" deterministic for tests (L-17).
5. State `Exclusive`; run `block(ExclusiveScope(generation, liveKey))`. The block uses `liveKey` as the export source key and returns a `Reopen` directive. By contract the block never throws: DbMaintenance's block catches every `Throwable` itself and converts it into a directive, `Same` when its `commitStarted` flag is still false (nothing was committed, so reopening `G` is right) and `StayClosed` once the flag is set, with the operation ending `Restart` ([Operation frame](#operation-frame) step 6). After `commitGeneration` was called the holder must never reopen `G` with the old key: the key-state on disk may already name another generation, and every write made to `G` would be deleted silently at the next start.
6. In `NonCancellable`, apply the directive. If the block throws anyway (a bug), the holder cannot know whether a commit happened: it handles the throw as `StayClosed`, then rethrows; DbMaintenance's frame ends the operation with outcome `Restart`, and startup reconciliation decides from the key-state on disk.
   - `Same`: run the [open sequence](#open-sequence) on the same generation with the retained key.
   - `Generation(gen, newKey, newData)`: if `newKey` is non-null, zero the old key (the old instance and every maintenance connection are closed) and take ownership of `newKey`; increment `dataset` if `newData`; run the open sequence on `gen`.
   - `StayClosed`: zero the key; state `Closed`; return null.
7. A failed reopen zeroes the key and leaves the state `Closed`; `exclusive` returns `Failed(kind)` and DbMaintenance reports `ReopenFailed(kind)`. Every zeroing happens while the transition `Mutex` is held.

### Close

`close()` (effect E3: every transition of [architecture.md](architecture.md) § Transition table whose effect column says E3): state `Draining`; wait for the in-flight count to reach 0; `db.close()` in `NonCancellable`; zero the key (under the transition `Mutex`); state `Closed`. Waiting `withDb` calls get `DatabaseUnavailable`. No file is deleted (DEC-85); a `Closed` holder is reopened only by E2. When the state is already `Closed`, `close()` is a no-op.

### Runtime corruption routing

- Detection: `SQLiteDatabaseCorruptException` or `SQLiteNotADatabaseException` thrown by the current instance inside `withDb` or `observe` (DEC-85).
- Action: on every detection the holder calls `CorruptionListener.onRuntimeCorruption()` on the failing thread; SessionGate's implementation launches `dispatch(CorruptAtRuntime)` on `dispatchers.main` in `applicationScope` and returns at once. The failing call returns `DatabaseUnavailable`; the failing flow completes.
- The holder never closes Room on its own. SessionGate does it through E3 (T13, T26, T37) and shows the Corrupt screen. While the gate is `Maintenance` the event only sets `corruptPending` (T38, T39) and the gate applies it when the operation finishes (T28b, T14b, T31a); `Opening` handles it as T37. Where the gate has no row for the event (T35), it is ignored, and because detection reports every time, the next failing query reports again once the gate can act.
- Corruption that DbMaintenance finds on its own connections is reported through the operation's outcome (`ReopenFailed(Corrupt)`), not through the listener.
- The file stays on disk until the user chooses Restore or Start fresh on the Corrupt screen ([Corruption and KeyUnavailable recovery](#corruption-and-keyunavailable-recovery)).

### Key custody

Lifetimes of secrets in general are owned by [security.md](security.md) § 6.4; within the lifecycle:

| Key | Held by | Zeroed |
|---|---|---|
| Live key of generation `G` | DatabaseHolder, from `open` | After the Room instance using it is closed and no maintenance connection uses it: in `close()`; on a failed open or reopen; when an exclusive block reopens with a new key; on `StayClosed`. Always while holding the transition `Mutex`, so `copyLiveKey` never sees a half-zeroed array |
| Live key lent through `ExclusiveScope.liveKey` | Still DatabaseHolder | Never by DbMaintenance |
| New key of a key change (`PreparedKey.rawKey`) | DbMaintenance until `Committed`, then DatabaseHolder through `Reopen.Generation` | `NotCommitted`, `Unknown` or any failure before the commit: by DbMaintenance, after every connection keyed with it is closed |
| Device key copy for a restore (from the holder in `Ready`, from `KeyVault` on the recovery screens) | DbMaintenance | After [target verification](#target-verification) of the restored generation, and on every failure or cancellation. Origin `Ready` keeps a SHA-256 digest of the copy, which the exclusive block compares with `scope.liveKey` ([Restore](#steps) step 13) |
| Backup password bytes | DbMaintenance | Backup: after the staged backup passed verification (or failed). Restore: after the export from the candidate (or failure, cancellation, or a wrong-password retry) |

## Failure classification

Every failure is classified **by exception class**, never by message text; file-system failures are told apart by `ErrnoException.errno` (found on the exception or its cause chain), which is a number, not text. SQLite and SQLCipher messages are never shown or logged (DEC-70). `CancellationException` is never classified: cleanup and rollback run in `NonCancellable`, then it is rethrown. Keystore and crypto failures are classified by `KeyVault` ([security.md](security.md) § 5.2); this section maps their classes to lifecycle effects.

### Classes

| Class | Meaning | Data |
|---|---|---|
| WrongPassword | The inner GCM tag failed for a database password, or SQLCipher rejected a backup password (`SQLiteNotADatabaseException` on the candidate) | Nothing touched |
| KeyUnavailable | The key cannot be obtained: Keystore failure, or key-state invalid, or missing while a database file is not recognizably plaintext (DEC-86 and the note on invalid key-states in [Startup reconciliation](#startup-reconciliation)) | Nothing touched, nothing deleted |
| Corrupt | The live file is missing, empty or unreadable, or fails a check, with a key known to be correct (DEC-85) | Kept until the user chooses |
| NewerVersion | `user_version` greater than `AppDatabase.VERSION` | Nothing touched |
| InvalidBackup(reason) | A restore candidate failed validation; `reason` is a [`BackupRejection`](#restore-validation) | Live data untouched |
| StorageFull | `SQLiteFullException`, errno `ENOSPC` or `EDQUOT`, or a failed free-space precheck | Rolled back |
| Transient | An environmental I/O or locking failure that a retry may cure | Nothing touched |
| Programming | Anything else: a bug | App crashes; inside DbMaintenance: rollback, then `Unexpected` ([architecture.md](architecture.md) § Error model) |

`DbFailure` is the internal enum of the classes DatabaseHolder and DbMaintenance decide themselves; WrongPassword and KeyUnavailable come from `KeyVault`, InvalidBackup from [restore validation](#restore-validation):

```kotlin
// package com.doro.organizer.data
internal enum class DbFailure { Corrupt, NewerVersion, StorageFull, Transient, Programming }

internal fun Throwable.isStorageFull(): Boolean = this is SQLiteFullException ||
    generateSequence(this) { it.cause }.filterIsInstance<ErrnoException>()
        .any { it.errno == OsConstants.ENOSPC || it.errno == OsConstants.EDQUOT }

/** Base mapping. Context rules below override it where a row says so. */
internal fun classify(e: Throwable): DbFailure = when {
    e is CancellationException -> throw e
    e is CheckFailed -> e.failure
    e is SQLiteNotADatabaseException || e is SQLiteDatabaseCorruptException -> DbFailure.Corrupt
    e.isStorageFull() -> DbFailure.StorageFull
    e is SQLiteDiskIOException || e is SQLiteCantOpenDatabaseException || e is SQLiteReadOnlyDatabaseException ||
        e is SQLiteAccessPermException || e is SQLiteDatabaseLockedException || e is SQLiteTableLockedException ||
        e is SQLiteOutOfMemoryException || e is IOException -> DbFailure.Transient
    else -> DbFailure.Programming
}
```

The classes are the ones sqlcipher-android throws for the SQLite result codes: `SQLITE_CORRUPT` → `SQLiteDatabaseCorruptException`; `SQLITE_NOTADB` → `net.zetetic.database.sqlcipher.SQLiteNotADatabaseException` (a wrong key, an encrypted file opened as plaintext and a non-database file all look like this); `SQLITE_FULL` → `SQLiteFullException`; `SQLITE_IOERR` → `SQLiteDiskIOException`; `SQLITE_CANTOPEN`, `SQLITE_READONLY`, `SQLITE_PERM`, `SQLITE_BUSY`, `SQLITE_LOCKED`, `SQLITE_NOMEM` → the matching `android.database.sqlite` subclasses; `SQLITE_CONSTRAINT` → `SQLiteConstraintException`; plain `SQLITE_ERROR` and unlisted codes → `SQLiteException` itself. A page whose HMAC fails is returned to SQLite zeroed, so it surfaces as `SQLITE_CORRUPT`, or as `SQLITE_NOTADB` for page 1.

Library behavior claims in this section (journal mode of raw connections, the error-handler signature, hook invocation for an empty key, the exception class for an HMAC failure on a page other than page 1) are listed under "Verify at setup" in the [Open sequence](#open-sequence); where the observed class differs, the tables below follow the observed class.

### Classification table

In each table the first matching row applies; a row that names a class overrides the base mapping above for its context.

Context **Opening** (effect E2: `completeStartup`, `KeyVault`, `DatabaseHolder.open`; also the reopen at the end of exclusive mode, which reports through `ReopenFailed(kind)` instead):

| Symptom | Class | Gate event |
|---|---|---|
| `completeStartup` fails with storage full, or its commit is `NotCommitted` or `Unknown` | StorageFull / Transient | `OpenFailed(Transient)` (T19; `completeStartup` works only in `PLAIN`, so never `viaPassword`, never T18) |
| `completeStartup` fails with a Transient class | Transient | `OpenFailed(Transient)` |
| `KeyVault`: inner GCM tag fails | WrongPassword | `PasswordRejected` (T21) |
| `KeyVault`: Keystore failure or unusable slot | KeyUnavailable | `KeyFailed` (T23) |
| Live file missing or 0 bytes | Corrupt | `OpenFailed(Corrupt)` (T17) |
| `IOException` other than a short header while reading the header (`readsAsPlaintext`) | Transient | `OpenFailed(Transient)` |
| Header plaintext ≠ key empty | Corrupt | `OpenFailed(Corrupt)` |
| Raw verification: `SQLiteNotADatabaseException`, `SQLiteDatabaseCorruptException`, or an exception whose class is exactly `SQLiteException` (the checks are fixed statements, so `SQLITE_ERROR` can only come from the file) | Corrupt | `OpenFailed(Corrupt)` |
| Raw verification: empty `sqlite_master`, `user_version` < 1, wrong `application_id`, `quick_check` ≠ `ok` | Corrupt | `OpenFailed(Corrupt)` |
| Raw verification: `user_version` > `AppDatabase.VERSION` (the app was downgraded) | NewerVersion | `OpenFailed(Transient)`: architecture.md defines no dedicated kind; nothing destructive is offered. The case is reachable only through a debuggable downgrade (`adb install -r -d`): Android refuses a release downgrade unless the app is uninstalled first, which deletes the data. The generic open-error screen is accepted. Should it ever matter, add `OpenFailureKind.NewerVersion` to architecture.md and a screen to ui.md § 10.5 |
| Raw verification or Room open: storage full | StorageFull | `OpenFailed(Transient)` |
| Raw verification or Room open: `SQLiteDiskIOException`, `SQLiteCantOpenDatabaseException`, `SQLiteReadOnlyDatabaseException`, `SQLiteAccessPermException`, `SQLiteDatabaseLockedException`, `SQLiteTableLockedException`, `SQLiteOutOfMemoryException` | Transient | `OpenFailed(Transient)` |
| Room open: `SQLiteNotADatabaseException`, `SQLiteDatabaseCorruptException` | Corrupt | `OpenFailed(Corrupt)` |
| Room open: `IllegalStateException` from Room (identity hash mismatch at equal version, missing migration, "Migration didn't properly handle"), or any other `SQLiteException` raised by a migration | Programming | Rethrown: the app crashes. The file is unchanged, because the open helper runs `onUpgrade` inside one transaction that rolls back. CI prevents it (DEC-14, DEC-88) |
| Anything else | Programming | Rethrown |

Context **Runtime** (`withDb`, `observe` on the current instance):

| Symptom | Class | Effect |
|---|---|---|
| `SQLiteDatabaseCorruptException`, `SQLiteNotADatabaseException` | Corrupt | `CorruptAtRuntime` (T13, T26). `withDb` returns `DatabaseUnavailable`; the flow completes |
| Storage full in `withDb` | StorageFull | `Err(StorageFull)`; the transaction rolled back |
| `SQLiteDiskIOException`, `SQLiteCantOpenDatabaseException`, `SQLiteReadOnlyDatabaseException`, `SQLiteAccessPermException`, `SQLiteOutOfMemoryException` in `withDb` | Transient | `Err(DatabaseUnavailable)`; the caller keeps its input (an editor draft stays in the ViewModel) |
| The same Transient classes, or storage full, in a flow | Transient / StorageFull | The inner flow is retried after 2 s, at most 3 times; if it still fails, it completes and `FlowProblemListener` posts `DatabaseUnavailable` or `StorageFull`; it re-subscribes at the next `Open` ([Observation](#observation)). No crash |
| Holder `Closed`, or the `dataset` changed while `withDb` waited | — | `Err(DatabaseUnavailable)` |
| Any exception from a retired instance in a flow | — | Swallowed; the flow resumes on the next `Open` |
| `SQLiteConstraintException`, `SQLiteDatabaseLockedException`, `SQLiteTableLockedException`, `SQLiteBlobTooBigException`, `SQLiteMisuseException`, a plain `SQLiteException`, any other exception | Programming | Rethrown (crash): writes are pre-checked and the live file has a single owner, so none of these is expected |

Context **DbMaintenance** (any operation; restore-candidate rows are in [Restore validation](#restore-validation)):

| Symptom | Class | Effect |
|---|---|---|
| Operation mutex already held | — | `Err(Busy)`; nothing started |
| Free-space precheck fails | StorageFull | `Err(StorageFull)`; nothing started, no `MaintenanceStarted` |
| The frame cannot empty `staging/` or `restore/` (step 3) | Transient | `Err(DatabaseUnavailable)`; nothing started |
| Storage full at any step before `COMMIT` | StorageFull | Rollback; `Err(StorageFull)` |
| A Transient class of the base mapping (a lingering Room connection after the 2 s drain, a locking exception, an `IOException` on a staging file) at any step before `COMMIT` returned `Committed`, whichever connection threw it | Transient | Rollback; `Err(DatabaseUnavailable)`: nothing was changed, trying again may work |
| Live file as export source (backup, key change) fails `quick_check`, or reading it throws `SQLiteNotADatabaseException` or `SQLiteDatabaseCorruptException` | Corrupt | Nothing exported. `Reopen.StayClosed`, result `Err(DatabaseUnavailable)`, outcome `ReopenFailed(Corrupt)` (T29 → Corrupt screen). A backup is never written from such a source (DEC-83) |
| A target verification check fails (`IllegalStateException` from `check`), or opening the target throws a non-Transient exception | Programming | Rollback; `Err(Unexpected)` |
| `RENAME` fails | StorageFull if the errno says so, else Programming | Rollback (`NotCommitted`); `Err(StorageFull)` or `Err(Unexpected)` |
| `CommitOutcome.NotCommitted` | StorageFull if its cause says so, else Programming | Rollback; `Err(StorageFull)` or `Err(Unexpected)` |
| `CommitOutcome.Unknown` | Programming | Nothing deleted; marker left; outcome `Restart`; `Err(OutcomeUnknown)` |
| A `Throwable` (an `Error` included) escapes an operation after `commitStarted` was set and no `CommitResult` was produced | Programming | Treated as `Unknown`: nothing deleted; outcome `Restart`; `Err(OutcomeUnknown)` |
| `KeyVault` Keystore failure (`prepare` in a key change, `openKey` in a restore from `Corrupt`) | KeyUnavailable | Rollback; `Err(KeyUnavailable)`; old generation and slot unchanged |
| `KeyVault` inner GCM tag fails (current password of a key change; database password of a restore from `Corrupt`) | WrongPassword | Inline error, counted by the backoff of [security.md](security.md) § 5.3; `Throttled` while the delay runs |
| Reopen after the operation fails | Corrupt / Transient as in context Opening | Outcome `ReopenFailed(kind)` |
| Any other exception before `COMMIT` returned `Committed` | Programming | Rollback; `Err(Unexpected)` |
| Any exception after `Committed` (`DATA_RESET`, `REOPEN`, `DELETE_OLD`, `DELETE_KEK`, `CLEANUP`) | — | The commit stands and the result stays `Ok`. `DATA_RESET`: the marker remains and startup applies it. `REOPEN`: outcome `ReopenFailed(kind)`. Deletions: logged, finished by startup reconciliation |

Context **Documents** (SAF and `BackupFileProvider`):

| Symptom | Class | Effect |
|---|---|---|
| The picker returns a null `Uri` | — | The user cancelled; not an error ([architecture.md](architecture.md) § Error model) |
| `openOutputStream(uri, "wt")` or `openInputStream(uri)` / `openFileDescriptor(uri, "r")` returns null; `FileNotFoundException`, `SecurityException` (revoked grant), `IOException`, `IllegalArgumentException` or `UnsupportedOperationException` from the provider (for example a provider that rejects mode `"wt"`) | FileAccess, or StorageFull for `ENOSPC`/`EDQUOT` | Backup: the document is deleted per the [partial-document rule](#to-a-document-saf) (best effort) and the result is `Err(FileAccess)` or `Err(StorageFull)`. Restore: `Err(FileAccess)`, nothing staged remains |

### User-facing messages

The UI maps each result to a string resource; the wording lives in [ui.md § 11 Strings](ui.md#11-strings). Meaning each message must carry:

| Result | Shown | Meaning |
|---|---|---|
| `Err(StorageFull)` | Snackbar or dialog of the screen that started the operation | Not enough free storage on the device; nothing was changed. Exception: from a backup to a document, after the output stream was opened on a document that already existed, the previous content of the chosen file is lost |
| `Err(FileAccess)` from a backup | Settings | The backup file could not be written; an incomplete file at the chosen location has been removed or should be deleted. When `Finished.previousContentLost` is true (the chosen document already existed and the stream had been opened), the message is the variant that says the previous content of the file was overwritten and is lost; it applies to every `Err` of that backup, `StorageFull` included |
| `Err(FileAccess)` from a restore | Settings, Corrupt or KeyUnavailable screen | The selected file could not be read |
| `Err(InvalidBackup(NotABackup))` | Same | The file is not a Doro Organizer backup |
| `Err(InvalidBackup(NewerVersion))` | Same | The backup was made by a newer version of the app; update the app, then try again |
| `Err(InvalidBackup(Incompatible))` | Same | The backup does not match this version of the app |
| `Err(InvalidBackup(Damaged))` | Same | The backup is damaged or incomplete |
| `WrongPassword` for a backup password | Inline in the restore password dialog, which stays open | Wrong password; the file may also not be a Doro Organizer backup at all (SQLCipher cannot tell these apart), so the dialog stays cancellable |
| `Err(WrongPassword)`, `Err(Throttled)` for the database password | Inline in the dialog that asked for it | As for the startup password ([security.md](security.md) § 5.3) |
| `Err(KeyUnavailable)` | Dialog | The encryption key could not be accessed; nothing was changed |
| `Err(Unexpected)` | Dialog | The operation failed after a rollback; the data was not changed |
| `Err(OutcomeUnknown)` | Dialog shown after the restart that follows | The app could not confirm whether the change was applied, and reloaded the data. It must not say that nothing changed. After a password change, if the app asks for a password at the restart, try the new password first and then the old one; the user must not erase anything because of this message |
| `Err(DatabaseUnavailable)` from a repository | Snackbar of the calling screen | The data could not be read or saved right now |
| `Err(DatabaseUnavailable)` from Backup or key change | Dialog, on the Corrupt screen when the gate went there | The database was found damaged (or was no longer open), so nothing was exported or changed; earlier backups are unaffected |
| `Err(DatabaseUnavailable)` from another maintenance operation | Dialog | The operation could not run right now; nothing was changed; try again |
| `Err(Busy)` | Nothing: the maintenance overlay already blocks the actions that could cause it | — |

`AppError.OutcomeUnknown` is a member of the error model owned by [architecture.md](architecture.md) § Error model; the wording is ui.md's, which exempts it from the "data was not changed" strings of its restore and key-change dialogs ([ui.md § 9.6](ui.md#96-outcome-messages)).

## DbMaintenance pipeline

`DbMaintenance` (package `data.maintenance`) runs every operation that exports, swaps or deletes database files: backup, restore, key changes, Start fresh and Erase, plus startup reconciliation and the share-file sweep. One export pipeline serves all of them (DEC-82).

### Interface and status

```kotlin
// package com.doro.organizer.data.maintenance
interface DbMaintenance {
    val status: StateFlow<MaintenanceStatus>
    /** Mode of the committed key-state; null before startup has read it or while it is missing or invalid. */
    val keyMode: StateFlow<KeyMode?>

    // SessionGate effects only
    suspend fun reconcileAtStartup(): StartupDecision                  // E1
    suspend fun completeStartup(): StartupCompletion                   // E2, before KeyVault: Done, Transient, KeyUnavailable

    // Settings, gate Ready
    fun backupRequiresPassword(): Boolean                              // keyMode != PLAIN || SettingsStore encryptBackups
    suspend fun discardDocument(target: Uri)                           // deletes the SAF document the picker created, only when it is known to be empty
    suspend fun backupToDocument(target: Uri, backupPassword: CharArray?): AppResult<MaintenanceResult>
    suspend fun backupForShare(backupPassword: CharArray?): AppResult<MaintenanceResult>
    suspend fun changeKey(target: KeyMode, currentPassword: CharArray?, newPassword: CharArray?): AppResult<MaintenanceResult>

    // Settings (Ready) and the Corrupt / KeyUnavailable screens: the caller passes the screen's own origin
    suspend fun restore(source: Uri, origin: MaintenanceOrigin): AppResult<MaintenanceResult>   // asks its questions through status
    fun answerPassword(password: CharArray?)                           // NeedsBackupPassword / NeedsDatabasePassword; null cancels
    fun answerConfirmation(confirmed: Boolean)                         // NeedsConfirmation
    fun cancelRestore()                                                // the Cancel button of the checking phase
    suspend fun startFresh(origin: MaintenanceOrigin): AppResult<MaintenanceResult>   // Corrupt / KeyUnavailable only

    // Forgot-password screen, gate NeedsPassword
    suspend fun erase(): AppResult<MaintenanceResult>

    fun acknowledge()                                                  // Finished -> Idle once the UI has shown the result
    suspend fun sweepShareFiles()                                      // every ProcessLifecycleOwner ON_START
}

sealed interface MaintenanceStatus {
    data object Idle : MaintenanceStatus
    /** fraction: 0..1 while a copy of known length runs (COPY_INPUT, WRITE_DOCUMENT), else null. */
    data class Running(val op: MaintenanceOp, val step: PipelineStep, val fraction: Float? = null) : MaintenanceStatus
    data class NeedsBackupPassword(val retry: Boolean) : MaintenanceStatus        // restore of an encrypted backup
    data class NeedsDatabasePassword(val error: AppError?) : MaintenanceStatus    // restore from Corrupt in PASSWORD mode
    data class NeedsConfirmation(val preview: RestorePreview) : MaintenanceStatus
    /** previousContentLost: true only for a backup to a document that failed after openOutputStream returned a stream on a document that already existed (createdEmpty was false); its old content is gone. */
    data class Finished(val op: MaintenanceOp, val result: AppResult<MaintenanceResult>, val previousContentLost: Boolean = false) : MaintenanceStatus
}
/** encryptionTurnsOff: true when the origin is KeyUnavailable and at least one generation file that the restore will replace is not plaintext; the same condition as Restored.encryptionTurnedOff. Computed once, at AWAIT_CONFIRMATION (Restore step 12). */
data class RestorePreview(val notes: Int, val persons: Int, val projects: Int, val resultingMode: KeyMode, val encryptionTurnsOff: Boolean)

sealed interface MaintenanceResult {
    data object BackupSaved : MaintenanceResult
    class BackupShared(val uri: Uri, val displayName: String) : MaintenanceResult {
        override fun toString() = "BackupShared"                             // no URI in logs (DEC-70)
    }
    data class Restored(val notes: Int, val persons: Int, val projects: Int, val encryptionTurnedOff: Boolean) : MaintenanceResult
    data object RestoreCancelled : MaintenanceResult                         // the user cancelled; no message
    data class KeyChanged(val from: KeyMode, val to: KeyMode) : MaintenanceResult   // from: the committed mode before, to: the target; ui.md § 9.6 chooses the snackbar from the pair
    data object StartedFresh : MaintenanceResult                             // the UI always shows the encryption-off notice (DEC-85)
    data object Erased : MaintenanceResult
}

enum class MaintenanceOp { Backup, Restore, KeyChange, StartFresh, Erase }  // GateState.Maintenance(op, origin)
/** The state an operation starts from. Same four names as SessionGate's `Origin`; defined here because data.maintenance does not import session. */
enum class MaintenanceOrigin { Ready, Corrupt, KeyUnavailable, NeedsPassword }
enum class StartupCompletion { Done, Transient, KeyUnavailable }            // result of completeStartup (E2)
sealed interface MaintenanceOutcome {                                         // GateEvent.MaintenanceFinished(outcome)
    data object Reopened : MaintenanceOutcome
    data class ReopenFailed(val kind: OpenFailureKind) : MaintenanceOutcome
    data object Restart : MaintenanceOutcome
    data object Aborted : MaintenanceOutcome
}
interface MaintenanceListener {                                               // implemented by SessionGate
    fun onMaintenanceStarted(op: MaintenanceOp)
    fun onMaintenanceFinished(outcome: MaintenanceOutcome)
}
```

- `status` survives rotation and screen changes; any screen re-reads it ([architecture.md](architecture.md) § Threading). The UI renders `Running` of a restore before its confirmation as the "checking" dialog, every other `Running` as the progress of the maintenance overlay or screen, `Needs…` as dialogs and `Finished` as the outcome message, then calls `acknowledge()` ([ui.md](ui.md) § 8.7, § 9.6, § 10.6). `Finished` stays until `acknowledge()` or until the next operation starts. `RestoreCancelled` shows nothing. None of these types holds user text, a password or a key.
- `keyMode` is set by `reconcileAtStartup` (the mode of a valid key-state, else null), by `completeStartup` (`PLAIN` after a first run or an adoption) and after every `Committed` commit; Settings renders the encryption rows from it ([ui.md](ui.md) § 9). `backupRequiresPassword()` is `keyMode != PLAIN || SettingsStore encryptBackups` (DEC-83).
- `discardDocument(uri)` reads `OpenableColumns.SIZE` and, only when it is known to be 0, calls `DocumentsContract.deleteDocument(contentResolver, uri)`; every failure is ignored. The UI calls it when the user cancels the backup password dialog after the picker returned the document. A document that already holds data (the user picked an existing file to overwrite) is never deleted at this point: nothing has been written to it yet.
- Passwords arrive as `CharArray`; DbMaintenance converts them with `passwordBytes` ([security.md](security.md) § 6.3), which zeroes the chars, and zeroes the bytes per [Key custody](#key-custody).
- `MaintenanceListener` keeps the import rules: `data.maintenance` does not import `session`. DbMaintenance makes each call inside `withContext(dispatchers.main)` and SessionGate's implementation calls `dispatch` directly, so the gate state has changed before the next pipeline step runs. AppContainer wires the listener (and the holder's `CorruptionListener`), both as providers, to SessionGate, and the holder's `FlowProblemListener` to `AppMessages` ([architecture.md](architecture.md) § Event wiring).

### Operation frame

Every user operation (`backup*`, `changeKey`, `restore`, `startFresh`, `erase`) runs the same frame:

1. It runs detached in `applicationScope` on `dispatchers.io` through `applicationScope.runDetached { … }` (`core.coroutines`, [architecture.md](architecture.md) § Layering and data flow, rule 6), so a cancelled caller never cancels it and an exception of the operation still reaches the uncaught handler instead of being lost with the caller.
2. `opMutex.tryLock()`; if the mutex is held, return `Err(Busy)` and do nothing. Startup reconciliation and `completeStartup` use the same mutex with `withLock`.
3. With the mutex held (so no restore or backup can be using them), except for Erase: empty `staging/` and `restore/` with `files.clearDir`. A leftover that cannot be deleted fails the operation with `Err(DatabaseUnavailable)` before anything else happens. Backups, restores and generations therefore never meet a stale file or a stale `-journal` under a reused name.
4. Check the origin that the caller passed (`MaintenanceOrigin`: Settings passes `Ready`, the recovery screens pass their own state, Erase is always `NeedsPassword`) against `DatabaseHolder.state`. DbMaintenance never infers a recovery origin: `Corrupt` and `KeyUnavailable` both leave the holder `Closed`, so only the screen that started the operation knows which one it is, and the origin selects the key rule of [Origins and target key](#origins-and-target-key): `Ready` requires `Open`, every other origin requires `Closed`. A mismatch is an `IllegalStateException`, except `Ready` with a holder that is no longer `Open` (the gate left `Ready` meanwhile): that returns `Err(DatabaseUnavailable)` before anything started, and a restore treats it as in step 6. Each operation states which origins it accepts; any other is a programming error.
5. Run the checks that need no state change (`PRECHECK`, and for a key change the current-password check of [security.md](security.md) § 4 step 2). A failure returns its error without entering `Maintenance`.
6. `listener.onMaintenanceStarted(op)`: SessionGate enters `Maintenance(op, origin)` (T27, T33, T8; T12 while the gate is `Locked(Ready)`, `Locked(Corrupt)` or `Locked(KeyUnavailable)`). Every operation except Restore raises it right after step 5, when every dialog of the operation has been answered. A restore raises it only after the user confirmed (`AWAIT_CONFIRMATION`); its earlier steps run while the gate keeps its state, under the restore dialogs ([ui.md](ui.md) § 8.7, § 10.6). At that moment the restore determines its origin again: if the origin is `Ready` and the holder is no longer `Open` (the gate went to `Corrupt` meanwhile), it continues as a restore from `Corrupt`; its export target is keyed for the unchanged slot, so it stays valid.
7. Run the operation's steps, each preceded by `faultHook.before(op, step)` and published as `Running(op, step)`.
8. Finally, in `NonCancellable`: delete the operation's temporary files (`CLEANUP`), zero every key and password the operation owns, set `status = Finished(op, result)`, call `listener.onMaintenanceFinished(outcome)` if and only if step 6 ran, then `opMutex.unlock()`. The mutex is released last, so an effect E1 started by `Restart` waits for it instead of racing the cleanup.

`commitStarted` is a per-operation flag that the frame sets immediately before the first call of `commitGeneration`. Any `Throwable` that escapes an operation's steps (an `Error` included; inside an exclusive block it is caught by the block, [Exclusive mode](#exclusive-mode) step 5) is classified here: with `commitStarted` set it is `Unknown` (nothing deleted, marker left, outcome `Restart`, `Err(OutcomeUnknown)`), because the key-state on disk may already name the new generation; otherwise it is a Programming failure (rollback, `Err(Unexpected)`).

A restore that ends before its confirmation (cancelled, rejected, failed) raises neither event; the gate never left its state. Otherwise:

| Origin | How the operation ended | `MaintenanceFinished` outcome |
|---|---|---|
| `Ready` | Done or rolled back, and the database is open again | `Reopened` (T28) |
| `Ready` | The reopen of the old or new generation failed, or the export source was found corrupt | `ReopenFailed(kind)` (T29) |
| `Ready` | `CommitOutcome.Unknown`, or a `Throwable` after `commitStarted`, or a throwing exclusive block | `Restart` (T30) |
| `Corrupt`, `KeyUnavailable` | Restore or Start fresh `Committed` | `Restart` (T30) |
| `Corrupt`, `KeyUnavailable` | Failed after `MaintenanceStarted` but before `COMMIT` returned `Committed`, `NotCommitted` included | `Aborted` (T31) |
| `Corrupt`, `KeyUnavailable` | `CommitOutcome.Unknown`, or a `Throwable` after `commitStarted` | `Restart` (T30) |
| `NeedsPassword` (Erase) | Failed before `KeyVault.eraseState()` changed anything (the `DELETE_OLD` step included) | `Aborted` (T31d) |
| `NeedsPassword` (Erase) | `KeyVault.eraseState()` ran, completely or not | `Restart` |

While `Locked(Maintenance(…))` the same outcomes apply (T14, T15). SessionGate ignores maintenance events that do not match its state (T35).

### Free-space precheck

`PRECHECK` compares `noBackupFilesDir.usableSpace` (the same filesystem as `databases/` and `cacheDir`) with the requirement below, where `MARGIN = 16 MiB` keeps the device from being driven to zero free space. The check is an early, clear failure; running out of space later is still handled (storage full → rollback).

| Operation | Requirement |
|---|---|
| Backup (both paths) | `L + MARGIN`, where `L` is the length of `doro-G.db` plus its `-wal`. The share copy is moved, not copied. The space of a SAF destination cannot be checked; a full destination fails `WRITE_DOCUMENT` |
| Key change | `L + MARGIN`: the new generation coexists with the old one until the new one is open |
| Restore | `3 × D + MARGIN` before `COPY_INPUT`, where `D` is the document's `OpenableColumns.SIZE` (the copy, the migration journal, the export). When the provider reports no size, `COPY_INPUT` enforces the rolling bound of [Restore](#steps) step 4 and a final check of `2 × copied + MARGIN` after the copy |
| Start fresh, first run | `MARGIN` |
| Erase | None |

### Maintenance connection

The export always runs on a dedicated SQLCipher connection whose main database is the source, never on Room's instance (DEC-82). ATTACH through Room is forbidden: sqlcipher-android disables WAL on the instance that executes an `ATTACH`, and Room's other pooled connections would not see the attachment.

```kotlin
// package com.doro.organizer.data.maintenance
internal class MaintenanceConnection private constructor(private val db: SQLiteDatabase) : Closeable {
    companion object {
        /** Main database = export source: the live file, or a validated restore candidate. */
        fun open(source: File, sourceKey: ByteArray) = MaintenanceConnection(RawConnections.open(source, sourceKey))
    }

    fun quickCheckOk(): Boolean = db.column0("PRAGMA quick_check") == listOf("ok")

    /** EXPORT into `target`, which must not exist yet. */
    fun exportTo(target: File, targetKey: ByteArray) {
        check(target.createNewFile())        // the connection was opened without SQLITE_OPEN_CREATE; SQLite opens a 0-byte file as an empty database
        db.execSQL("ATTACH DATABASE ? AS target KEY ?", arrayOf<Any>(target.path, targetKey))   // key always bound (DEC-82)
        try {
            db.query("SELECT sqlcipher_export('target')").use { check(it.moveToFirst()) }       // throws on failure; never rawExecSQL
            val version = db.longOf("PRAGMA main.user_version")
            db.execSQL("PRAGMA target.user_version = $version")                                 // export does not copy it
            db.execSQL("PRAGMA target.application_id = ${AppDatabase.APPLICATION_ID}")         // export does not copy it
            checkCopied()
        } finally {
            runCatching { db.execSQL("DETACH DATABASE target") }   // close() releases it anyway; keep the original exception
        }
    }

    /** Same row count in every table, and the same sqlite_sequence, in main and target. */
    private fun checkCopied() {
        val tables = db.column0("SELECT name FROM main.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'")
        for (name in tables) {
            val q = "\"" + name!!.replace("\"", "\"\"") + "\""
            check(db.longOf("SELECT count(*) FROM main.$q") == db.longOf("SELECT count(*) FROM target.$q"))
        }
        check(db.column0("SELECT name || ':' || seq FROM main.sqlite_sequence ORDER BY name") ==
              db.column0("SELECT name || ':' || seq FROM target.sqlite_sequence ORDER BY name"))
    }

    override fun close() = db.close()
}
```

- The connection is opened read-write without `CREATE_IF_NECESSARY`, without the WAL flag and with `NO_LOCALIZED_COLLATORS` (no `android_metadata` table is written), so the source is never created by accident and every statement runs on one connection. On the live file this switches the journal mode to DELETE for the duration (journaled; Room switches back on reopen). The attached export target inherits that journal mode: DELETE leaves no `-journal` after the connection closes. Verify at setup: `PRAGMA target.journal_mode` reports `delete` on the attached target; if it reports `truncate` or `persist`, the zero-length-sidecar rule of [target verification](#target-verification) covers it.
- `KEY ?` is bound for every target. SQLCipher uses a TEXT or BLOB key byte for byte: the 67-byte `x'…'` form is a raw key, UTF-8 password bytes are a passphrase (PBKDF2-HMAC-SHA512, 256,000 iterations, per-file salt), and an empty BLOB attaches a plaintext file. A missing or NULL key would silently reuse the source key and salt, which [target verification](#target-verification) detects through the header. Verify at setup: binding `ByteArray(0)` produces a plaintext target on API 28 and API 35 (L-06, L-07 assert the header).
- `sqlcipher_export` copies the schema of every table, index, trigger and view, the rows, and `sqlite_sequence` (so ids are never reused, [data-layer.md](data-layer.md) INV-11), including `room_master_table` and therefore Room's identity hash. It copies neither `user_version` nor `application_id`; both are set from code.
- `PRAGMA name = value` statements cannot bind their value, so the only values interpolated into SQL text are integers computed by the app and table names read from a schema that is either the live database or a candidate whose object set already equals this build's ([Restore validation](#restore-validation)). The table-valued forms `pragma_table_xinfo(?)` and the like, used by the schema comparison, bind their argument. User input never reaches these statements.
- A backup password of the exact form `x'` + 64 or 96 hex digits + `'` is treated by SQLCipher as a raw key rather than a passphrase; export and restore treat the bytes identically, so such a backup still round-trips.

### Target verification

`VERIFY_TARGET` runs on a fresh connection after the maintenance connection is closed, before anything can commit (DEC-82):

```kotlin
internal fun verifyTarget(file: File, key: ByteArray) {
    check(RawConnections.isPlaintext(file) == key.isEmpty())           // catches an export keyed with the wrong (source) key
    removeEmptySidecars(file)                                          // a zero-length -journal or -wal is never hot; TRUNCATE mode leaves one
    RawConnections.open(file, key).use { db ->
        check(db.column0("PRAGMA integrity_check") == listOf("ok"))
        check(db.query("PRAGMA foreign_key_check").use { it.count == 0 })
        if (key.isNotEmpty()) check(db.query("PRAGMA cipher_integrity_check").use { it.count == 0 })
        check(db.longOf("PRAGMA user_version") == AppDatabase.VERSION.toLong())
        check(db.longOf("PRAGMA application_id") == AppDatabase.APPLICATION_ID.toLong())
    }
    removeEmptySidecars(file)                                          // the verification connection may leave one too: the commit precondition holds on return
}

/** Fails when a sidecar holds data (a hot journal, or a WAL with frames: the file was not closed cleanly). Deletes empty ones and any -shm (an index, never data). */
internal fun removeEmptySidecars(file: File) {
    for (suffix in listOf("-journal", "-wal", "-shm")) {
        val sidecar = File(file.path + suffix)
        if (!sidecar.exists()) continue
        if (suffix != "-shm") check(sidecar.length() == 0L)
        check(sidecar.delete())
    }
}
```

- `integrity_check` returns the single row `ok` when sound; `foreign_key_check` and `cipher_integrity_check` return one row per problem and nothing when sound. `cipher_integrity_check` verifies the HMAC of every page, free pages included.
- In journal mode `TRUNCATE` or `PERSIST` SQLite leaves a zero-length `-journal` after every commit and after close; refusing it would fail every first run, Start fresh, backup, key change and restore. A zero-length journal is not hot and harmless, so it is deleted; a non-empty one or a non-empty `-wal` is a defect and fails the check.
- Every target has `user_version = AppDatabase.VERSION`: backups and key changes export from the live database, which Room has already migrated, and restores export after migrating the candidate.
- A failed check is a programming or hardware fault, never the user's data: the target is deleted, nothing is committed, the result is `Unexpected` ([Failure classification](#failure-classification)).

### Empty databases

```kotlin
/** CREATE_EMPTY: an empty plaintext database of this build at `file`, which must not exist. */
internal fun createEmptyDatabase(context: Context, file: File) {
    val db = buildRoom(context, file, ByteArray(0), temporary = true)
    try { db.openHelper.writableDatabase } finally { db.close() }   // onCreate: tables, identity hash, application_id, user_version
}
```

Used for the first-run generation, the Start fresh generation (both then go through `VERIFY_TARGET` and the commit) and the restore reference database. Room's own `onCreate` path writes the schema, so it always matches the build ([data-layer.md](data-layer.md) § Entities; `AppDatabase.CALLBACK` sets `application_id`).

### Pipeline steps and fault hook

```kotlin
enum class PipelineStep {
    PRECHECK, PREPARE_KEY, CLOSE_ROOM, CHECK_SOURCE, CREATE_EMPTY, EXPORT, VERIFY_TARGET,
    WRITE_DOCUMENT, PUBLISH_SHARE,
    COPY_INPUT, OPEN_INPUT, VALIDATE_INPUT, MIGRATE_INPUT, CHECK_SCHEMA, UNLOCK_DEVICE_KEY, AWAIT_CONFIRMATION,
    MARK_RESET, RENAME, COMMIT, DATA_RESET, REOPEN, DELETE_OLD, DELETE_KEK,
    ERASE_KEY_STATE, ERASE_FILES, RESET_SETTINGS, CLEANUP,
}
fun interface MaintenanceFaultHook { fun before(op: MaintenanceOp?, step: PipelineStep) }   // op = null: completeStartup
```

| Operation | Steps, in order |
|---|---|
| Backup to a document | `PRECHECK`, `CLOSE_ROOM`, `CHECK_SOURCE`, `EXPORT`, `REOPEN`, `VERIFY_TARGET`, `WRITE_DOCUMENT`, `CLEANUP` |
| Backup for sharing | `PRECHECK`, `CLOSE_ROOM`, `CHECK_SOURCE`, `EXPORT`, `REOPEN`, `VERIFY_TARGET`, `PUBLISH_SHARE`, `CLEANUP` |
| Key change | `PRECHECK`, `PREPARE_KEY`, `CLOSE_ROOM`, `CHECK_SOURCE`, `EXPORT`, `VERIFY_TARGET`, `RENAME`, `COMMIT`, `REOPEN`, `DELETE_OLD`, `DELETE_KEK` (target `PLAIN` only), `CLEANUP` |
| Restore from `Ready` | `PRECHECK`, `COPY_INPUT`, `OPEN_INPUT`, `VALIDATE_INPUT`, `MIGRATE_INPUT`, `CHECK_SCHEMA`, `EXPORT`, `VERIFY_TARGET`, `AWAIT_CONFIRMATION`, `CLOSE_ROOM`, `MARK_RESET`, `RENAME`, `COMMIT`, `DATA_RESET`, `REOPEN`, `DELETE_OLD`, `CLEANUP` |
| Restore from `Corrupt` | As from `Ready`, plus `UNLOCK_DEVICE_KEY` before `EXPORT`, minus `CLOSE_ROOM` and `REOPEN` |
| Restore from `KeyUnavailable` | As from `Ready`, minus `CLOSE_ROOM` and `REOPEN`, plus `DELETE_KEK` after `DELETE_OLD` |
| Start fresh | `PRECHECK`, `CREATE_EMPTY`, `VERIFY_TARGET`, `MARK_RESET`, `RENAME`, `COMMIT`, `DATA_RESET`, `DELETE_OLD`, `DELETE_KEK`, `CLEANUP` |
| Erase | `DELETE_OLD` (the unreferenced generations, before the key-state is touched), `ERASE_KEY_STATE`, `ERASE_FILES`, `RESET_SETTINGS` |
| First run (`completeStartup`) | `PRECHECK`, `CREATE_EMPTY`, `VERIFY_TARGET`, `RENAME`, `COMMIT`, `DELETE_OLD` |
| Adopt plaintext (`completeStartup`) | `COMMIT`, `DELETE_OLD` |

- The hook runs immediately before its step: every earlier step has completed and the step itself has not started. Release builds bind a no-op hook. Tests bind one that throws an `IOException` (L-14) or kills the process (L-15, [testing.md](testing.md) § 5); a kill at the hook of step X is the earliest instant of the matrix row "crash at X".
- An exception thrown by the hook in a backup, or at a step up to and including `COMMIT` of any other operation except Erase, must end in a rollback that leaves the key-state file byte-identical, the live generation's content unchanged (compare `canonicalDump`, not bytes: opening a maintenance connection rewrites page 1 of the live file to switch its journal mode) and no staging file behind; the gate returns to the operation's origin state (`Ready` through `Reopened`, otherwise the recovery or password state through `Aborted`). At a later step the commit stands and the failure is handled as the last row of the DbMaintenance context in [Failure classification](#failure-classification) says. Erase has no rollback once `ERASE_KEY_STATE` starts; its `DELETE_OLD` step precedes that point, so an exception there ends the Erase with `Aborted` and nothing erased. Its later interruptions are covered by the [crash-safety matrix](#crash-safety-matrix).

### Cancellation policy

- Operations are detached from their callers (frame step 1). Rotation, leaving the screen and a cleared ViewModel do not stop them; `status` reports progress and the result.
- The user can cancel at the SAF picker and the Settings dialogs that precede an operation (before it exists), and during a restore until its confirmation: the "checking" dialog's Cancel (`cancelRestore()`), a password dialog (`answerPassword(null)`) and the confirmation itself (`answerConfirmation(false)`). All of these lie before `MaintenanceStarted` and long before `RENAME`. `cancelRestore()` also closes the `ParcelFileDescriptor` of `COPY_INPUT` and triggers the `CancellationSignal` of the candidate statements ([Restore](#steps) steps 3 and 4), so a blocked provider read or a long `integrity_check` ends too. Once `MaintenanceStarted` was raised there is no cancel button; the overlay or progress screen blocks input ([architecture.md](architecture.md) § Rendering).
- `CLOSE_ROOM` through `REOPEN` of exclusive mode, the commit, rollback and `CLEANUP` run in `NonCancellable`. If a coroutine cancellation still reaches an operation (production never cancels `applicationScope`; tests may), it rolls back as for a failure before `COMMIT`, or finishes the post-commit steps after it, and then rethrows.
- An operation waiting at a `Needs…` status waits indefinitely and keeps the mutex; other operations get `Busy`. A lock (T25, T32) does not interrupt it: the UI hides the question while `Locked` and shows it again after unlocking.
- Process death or a reboot interrupts nothing that startup cannot finish: see the [crash-safety matrix](#crash-safety-matrix).

## Backup

A backup is always an export, never a byte copy of the live file (DEC-83): a copy of a Keystore-keyed file could not be opened after reinstall or on another device, and a copy taken while Room runs would miss the WAL. Settings offers two destinations: a document chosen through SAF (primary) and the share sheet (secondary). Screens and wording: [ui.md](ui.md).

### Password rules

- A backup password is required exactly when `backupRequiresPassword()` is true: the database is `DEVICE` or `PASSWORD`, or it is `PLAIN` and "Encrypt backups" is on (DEC-83).
- When required, it is a new password typed twice under the policy and field rules of [security.md](security.md) § 6.1–6.2. It is never stored or prefilled, it is independent of the database password (it may equal it), it never becomes the database password ([security.md](security.md) § 4), and nothing throttles it.
- When not required, the export is plaintext and the plaintext warning (DEC-83, wording in ui.md) is shown before the picker or the share, so the user can turn "Encrypt backups" on instead.
- For a document, the password is asked after the destination was picked, so no password waits in memory while the picker is open, and a picker result delivered to a new process ([architecture.md](architecture.md) § Result launchers and process death) simply asks for it then. For sharing, it is asked before the export.
- Key: `passwordBytes(password)` (UTF-8 of NFC, [security.md](security.md) § 6.3) as a SQLCipher passphrase, or `ByteArray(0)` for a plaintext backup.

### File name

`"doro-organizer-backup-${appTime.today()}.db"`: `LocalDate.toString()` is ISO `YYYY-MM-DD`, and `AppTime.today()` uses the current zone, so the name carries the local date (DEC-83, L-08). It is the suggested name of the SAF document and the display name of a shared file. Providers may change it (for example by appending " (1)"); restore never depends on the name or on the MIME type.

### To a document (SAF)

UI part ([ui.md](ui.md), launchers in [architecture.md](architecture.md) § Result launchers and process death):

1. If `backupRequiresPassword()` is false: the plaintext warning; continuing proceeds.
2. Post the `CreateDocument("application/octet-stream")` request with the file name above; MainActivity launches it under the one-shot re-lock exemption ([security.md](security.md) § 7.7). `application/octet-stream` keeps providers from appending an extension.
3. A null `Uri` means the user cancelled: nothing exists, nothing to undo.
4. If a password is required: the password dialog. Cancelling it calls `discardDocument(uri)`, which deletes the document only when the picker created it (the size is known to be 0); a document that already holds data is left untouched.
5. Call `backupToDocument(uri, password)`.

DbMaintenance part (origin `Ready` only):

6. Before anything is written, `backupToDocument` reads `OpenableColumns.SIZE` of the document and records `createdEmpty = (size == 0L)`: true for a document the picker just created, false for an existing document the user chose to overwrite (DocumentsUI asks to overwrite and returns the existing URI) and when the size is unknown. It then runs `PRECHECK` (free space). On failure the document is handled by the [partial-document rule](#to-a-document-saf) below and the result is `Err(StorageFull)`.
7. `MaintenanceStarted(Backup)`; empty `cacheDir/share/` (earlier share files are deleted at the next export, DEC-83).
8. `CLOSE_ROOM`: `holder.exclusive { … }` begins; Room is drained and closed, so the WAL is checkpointed and removed (DEC-89).
9. `CHECK_SOURCE`: open the [maintenance connection](#maintenance-connection) on `doro-G.db` with the lent live key; `quick_check` must return `ok`. Otherwise the source is Corrupt: no byte is exported or written, the document is handled by the partial-document rule (a pre-existing intact backup stays untouched: it may be the user's only way back), the block returns `StayClosed`, the result is `Err(DatabaseUnavailable)` and the outcome is `ReopenFailed(Corrupt)`, so the Corrupt screen replaces Settings (DEC-83, DEC-85). That screen may show the backup's `Finished` status ("no backup was made"); the shell that normally shows outcomes is not composed in `Corrupt`.
10. `EXPORT` into `noBackupFilesDir/staging/backup.db` with the backup key; close the connection.
11. `REOPEN`: the block returns `Reopen.Same`; Room reopens on `G` with the full [open sequence](#open-sequence). Reopening here, before verification and writing, keeps the database closed for the shortest time. If the reopen fails, the backup still continues (the export is complete) and the outcome becomes `ReopenFailed(kind)`.
12. `VERIFY_TARGET` on `backup.db` with the backup key; then zero the password bytes.
13. `WRITE_DOCUMENT`: `contentResolver.openOutputStream(uri, "wt")` (null → `FileAccess`). Once this call has returned a stream, the previous content of the document is gone (`"wt"` truncates). Copy `backup.db` with `InputStream.copyTo`, `flush()`, `(stream as? FileOutputStream)?.fd?.sync()` with failures ignored (pipe-backed providers cannot sync), `close()`. The byte-count check: the count returned by `copyTo` must equal `backup.db.length()`, and every write, flush and close must have succeeded.
14. `CLEANUP`: delete `backup.db` and its `-journal`.
15. Result `BackupSaved`; outcome `Reopened` (or `ReopenFailed(kind)` from step 11).

- Mode `"wt"` is required: `"w"` may leave the tail of a longer existing document in place, which would corrupt a backup written over an older, larger one. A provider that rejects `"wt"` fails the backup with `FileAccess`. Verify at setup: the system Files/Downloads provider and Google Drive accept `"wt"` (M-12 in [testing.md](testing.md)).
- Partial-document rule. A failure before `WRITE_DOCUMENT` has opened the stream (precheck, corrupt source, export, verification, password cancel) calls `DocumentsContract.deleteDocument(contentResolver, uri)` only when `createdEmpty` is true; a pre-existing document is left exactly as it was, including its intact old backup. A failure after `openOutputStream(uri, "wt")` returned a stream (write, flush, close, byte count) always deletes the document (best effort): its old content is already gone and what remains is partial. A failed or unsupported deletion is ignored, and the failure message tells the user that an incomplete file may remain, and, when the target existed, that its previous content was lost: the operation sets `Finished.previousContentLost = true` exactly when `createdEmpty` is false and `openOutputStream` had returned a stream ([User-facing messages](#user-facing-messages)). The user's choice to overwrite an existing document costs that document's old content only once the write has begun.
- Provider metadata is not read back after the write: providers backed by MediaStore update `OpenableColumns.SIZE` asynchronously, so a size comparison could report a good backup as failed and delete it. The size read before any write (step 6) only ever prevents a deletion: anything but a known 0 leaves the document in place. A successful local write also does not mean a cloud provider has uploaded the file.

### For sharing (share sheet)

1. UI: the plaintext warning or the password dialog, as above; then `backupForShare(password)`.
2. `PRECHECK`; `MaintenanceStarted(Backup)`.
3. Empty `cacheDir/share/` (DEC-83), as on the document path.
4. `CLOSE_ROOM`, `CHECK_SOURCE`, `EXPORT`, `REOPEN`, `VERIFY_TARGET` as for a document.
5. `PUBLISH_SHARE`: `Os.rename(backup.db, cacheDir/share/<UUID.randomUUID()>.db)` (same filesystem, no second copy), then `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file, displayName)` with the file name above as `displayName`, and schedule its [deletion after one hour](#share-file-lifetime).
6. Result `BackupShared(uri, displayName)`; outcome `Reopened`.
7. UI: post the chooser as an `ExternalRequest` for the activity-level `StartActivityForResult` launcher, which MainActivity launches under the re-lock exemption:

```kotlin
val send = Intent(Intent.ACTION_SEND)
    .setType("application/octet-stream")
    .putExtra(Intent.EXTRA_STREAM, uri)
    .setClipData(ClipData.newRawUri(displayName, uri))
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)          // read only; never a write grant
val chooser = Intent.createChooser(send, chooserTitle)        // chooserTitle: ui.md; createChooser carries the grant and ClipData over
```

- `BackupFileProvider` is not exported and serves only `cacheDir/share/` ([security.md](security.md) § 9.1). The random on-disk name gives every share its own URI, so a grant held by an earlier receiver never reaches a newer file.
- The chooser's result is ignored: Android reports neither the chosen target nor when it finished reading the file.
- Verify at setup: the four-argument `FileProvider.getUriForFile(context, authority, file, displayName)` exists in androidx.core 1.19.1 and the receiver sees `displayName`.

### Share-file lifetime

- A share file is deleted (DEC-83): at the start of every backup (step 3 above); at the first startup reconciliation of a process, a cold start (reconciliation step 2; not at a same-process re-run after `Restart`, so a receiver still reading a younger file is not cut off); after a committed key change ([Key changes](#key-changes) step 14); by a one-shot coroutine in `applicationScope` that waits 60 minutes after `PUBLISH_SHARE` (`delay`, elapsed time) and then deletes that file by path; and by `sweepShareFiles()`, which runs on every `ProcessLifecycleOwner` ON_START (wired by AppContainer with the other process observers) and deletes each file in `cacheDir/share/` with `appTime.nowMillis() - file.lastModified() >= 60 min`.
- While the process lives, the timer bounds a file's life to one hour of awake time (coroutine delays use the monotonic clock, which stops during deep sleep), and a wall clock moved backwards cannot extend it; a return to the app after a long sleep is caught by the ON_START sweep, which uses wall-clock age. If the process dies first, the file stays until the next cold start, which deletes it: the app has no service or scheduled job that could run without its process ([security.md](security.md) § 9.1).
- The system may purge `cacheDir` earlier when storage is low; a receiver that reads that late fails. Accepted (DEC-83).
- `sweepShareFiles()` does not take the operation mutex: it only deletes files at least one hour old, and a file being published is never that old.

### Temporary files of a backup

`noBackupFilesDir/staging/backup.db` exists from `EXPORT` to `CLEANUP` (or until `PUBLISH_SHARE` moves it) and never survives a cold start. While it exists it is plaintext when the backup is plaintext; it never leaves app-private storage except through the SAF write or the share grant.

## Restore

Restore replaces all data with a validated backup (DEC-84, §11). Nothing is replaced before the user confirms, and the replacement is the [generation commit](#generation-commit): the old data stays live until the key-state names the new file.

### Origins and target key

The restored data is exported into the device's current key configuration; restore never changes a `SettingsStore` security setting ([security.md](security.md) § 2, DEC-84). The one exception is a restore started from `KeyUnavailable`: it writes a `PLAIN` key-state, because the current configuration cannot produce a key, and it is the only restore that changes the encryption mode. The confirmation and the result say so (notice of [ui.md](ui.md) § 8.7), and the user can turn encryption on again ([Key changes](#key-changes), DEC-61). DEC-84 and DEC-86 record this exception. Even when the Keystore still works but the key-state is invalid, there is no slot to keep.

| Started from | Origin | Key configuration of the new generation | Key for the export target | After the commit |
|---|---|---|---|---|
| Settings ("Restore from backup", §4) | `Ready` | Unchanged: same mode, same slot (same DEK) | `holder.copyLiveKey()` | `REOPEN` of the new generation, outcome `Reopened` |
| Corrupt screen | `Corrupt` | Unchanged: same mode, same slot | `UNLOCK_DEVICE_KEY`: `PLAIN` → `ByteArray(0)`; `DEVICE` → `KeyVault.openKey(slot, null)`; `PASSWORD` → ask the database password (status `NeedsDatabasePassword`), then `KeyVault.openKey(slot, passwordBytes)`, which applies the backoff of [security.md](security.md) § 5.3 itself. The `UnlockResult` is mapped as in [Key changes](#key-changes) step 2 (`PasswordRejected` → `WrongPassword`, `Throttled` → `Throttled`, `KeyFailed` → `KeyUnavailable`), with `NeedsDatabasePassword(error)` carrying the first two | Outcome `Restart` (T30) |
| KeyUnavailable screen | `KeyUnavailable` | `PLAIN`, slot from `keyVault.prepare(PLAIN, null)` (the exception above) | `ByteArray(0)` | `DELETE_KEK`; outcome `Restart`; the result shows the encryption-off notice when the replaced data was encrypted (step 21) |

- The key of a `Corrupt` origin is needed again because `close()` zeroed it when the gate entered `Corrupt` (DEC-64). In `PASSWORD` mode this is a second password entry; it is a database-password check, so it counts toward the backoff.
- A Keystore failure in `UNLOCK_DEVICE_KEY` ends the restore with `Err(KeyUnavailable)`; the user stays on the Corrupt screen, where Start fresh remains possible.
- From `KeyUnavailable` the new generation is unencrypted; the confirmation states it ([Confirmation](#confirmation)) and the user can turn encryption on afterwards ([Key changes](#key-changes)).

### Steps

UI part: post the `OpenDocument` request with MIME filter `arrayOf("*/*")` (DEC-84: providers label `.db` files inconsistently, so content decides) under the re-lock exemption. A null `Uri` means cancelled. Otherwise call `restore(uri, origin)`, passing `Ready` from Settings and the screen's own state from the Corrupt and KeyUnavailable screens; the restore drives its dialogs through `status` ([ui.md](ui.md) § 8.7), and the UI answers with `answerPassword`, `answerConfirmation` and, while the backup is being checked, `cancelRestore`.

DbMaintenance part. Steps 1–12 change nothing the user could lose: the gate stays in its state (`Ready`, `Corrupt` or `KeyUnavailable`), Room stays as it is, and only files under `noBackupFilesDir/restore/` and `noBackupFilesDir/staging/` are written.

1. Frame: mutex; the origin the caller passed (`Ready`, `Corrupt` or `KeyUnavailable`; [Operation frame](#operation-frame) steps 3 and 4).
2. `PRECHECK`: read `OpenableColumns.SIZE` with `contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)` (null cursor, missing column or null value: unknown) and check free space ([Free-space precheck](#free-space-precheck)).
3. From here until the confirmation, `cancelRestore()` (or `answerPassword(null)`, `answerConfirmation(false)`) ends the restore with `Ok(RestoreCancelled)`. It sets a flag that is checked before every step and between the 64 KiB blocks of `COPY_INPUT`, closes the input's `ParcelFileDescriptor` (step 4), and triggers the `CancellationSignal` that candidate statements run with. An `IOException` caused by that close is reported as `RestoreCancelled`, not as `FileAccess`. Verify at setup: closing a `ParcelFileDescriptor` unblocks a `read()` pending on it (libcore's `IoBridge` signals blocked threads); whether sqlcipher-android's `query`/`rawQuery` accepts a `CancellationSignal` (if it does not, a running statement finishes first, bounded by the file size).
4. `COPY_INPUT`: `contentResolver.openFileDescriptor(uri, "r")` (null → `FileAccess`), read through `ParcelFileDescriptor.AutoCloseInputStream`; a provider that cannot offer a descriptor (`FileNotFoundException`) falls back to `openInputStream(uri)`, which `cancelRestore()` cannot unblock. Copy it to `noBackupFilesDir/restore/incoming.db` in 64 KiB blocks and count the bytes. SQLite needs a real path and must never open a descriptor owned by another app, so only this private copy is ever opened. The copy is bounded, because a broken provider can deliver more than it reported, or without end, while the gate is `Ready` and Room is live: stop with `Err(StorageFull)` as soon as `noBackupFilesDir.usableSpace < MARGIN + 2 × copied`; when the size was known, stop with `InvalidBackup(Damaged)` once `copied > D`. Without a known size this rolling bound replaces the up-front check, and at the end of the copy the check `2 × copied + MARGIN` of the [free-space precheck](#free-space-precheck) must hold.
5. `OPEN_INPUT`: header and length checks, then open the copy ([Restore validation](#restore-validation), checks 2–7):
   - Plaintext candidate (`SQLite format 3\0`): key `ByteArray(0)`.
   - Otherwise an encrypted candidate: status `NeedsBackupPassword(retry = false)`; `answerPassword(null)` cancels; the key is `passwordBytes(password)`. If SQLCipher rejects it (`SQLiteNotADatabaseException`), zero the bytes and ask again with `NeedsBackupPassword(retry = true)`; attempts are unlimited and not throttled ([security.md](security.md) § 5.3).
   - After opening: `PRAGMA cell_size_check = ON` and `PRAGMA trusted_schema = OFF` on the candidate connection (precautions for files of unknown origin). Verify at setup: both pragmas are accepted by the SQLite inside sqlcipher-android 4.20.0.
6. `VALIDATE_INPUT`: checks 8–16, in order, on the candidate connection; the first failure ends the restore.
7. `MIGRATE_INPUT`, only when `user_version < AppDatabase.VERSION`: close the candidate connection; build a temporary Room instance on `incoming.db` with the candidate key (`buildRoom(…, temporary = true)`, [Room builder](#room-builder)); `openHelper.writableDatabase` runs every migration in one transaction followed by Room's schema validation (DEC-88, [data-layer.md](data-layer.md) § Schema evolution); close it. Then reopen the candidate connection.
8. `CHECK_SCHEMA`: check 17; then count `notes`, `persons` and `projects` for the preview.
9. The device key: `holder.copyLiveKey()` (origin `Ready`: a suspend call under the holder's transition mutex that returns a copy only in state `Open`; if it returns null because the gate went to `Corrupt` meanwhile, continue as from `Corrupt`), `UNLOCK_DEVICE_KEY` (origin `Corrupt`), or `ByteArray(0)` (origin `KeyUnavailable`). The candidate connection stays open meanwhile; it holds no lock that matters.
10. `EXPORT`: close the candidate connection; `n` = next generation; open the [maintenance connection](#maintenance-connection) on `incoming.db` with the candidate key and `exportTo(staging/doro-<n>.db, deviceKey)`; close it. Zero the backup password bytes: this was their last use.
11. `VERIFY_TARGET` on `doro-<n>.db` with `deviceKey`. Origin `Ready` then keeps `keyDigest = SHA-256(deviceKey)`. Then zero `deviceKey` (a copy owned by DbMaintenance). Target verification checks the target with the very key it was exported with, so it cannot detect a wrong source of that key; the digest does (step 13).
12. `AWAIT_CONFIRMATION`: status `NeedsConfirmation(RestorePreview(notes, persons, projects, resultingMode, encryptionTurnsOff))`, where `encryptionTurnsOff` is true when the origin is `KeyUnavailable` and at least one generation file set that the restore will replace has a main file that is not plaintext (`RawConnections.isPlaintext`, step 4 of [Startup reconciliation](#startup-reconciliation)); it is computed here once and step 21 reuses it. `answerConfirmation(false)` cancels. The question waits through locks and backgrounding (the UI hides the dialog while `Locked`); a process death discards the restore.
13. `MaintenanceStarted(Restore)` (T27 from `Ready`, T33 from `Corrupt` or `KeyUnavailable`, T12 under a lock), with the origin determined again ([Operation frame](#operation-frame) step 6); then `CLOSE_ROOM` (origin `Ready`): `holder.exclusive { … }` begins. Its first action, before `MARK_RESET`: `check(MessageDigest.isEqual(sha256(scope.liveKey), keyDigest))`. A mismatch means the exported file is keyed with something other than the live DEK; it is a Programming failure that rolls back (`Reopen.Same`, `Err(Unexpected)`) before anything was marked or committed. (A restore that fell back to origin `Corrupt` skips the check: its key came from `copyLiveKey` under the mutex or from `KeyVault`.)
14. `MARK_RESET`: write the [data-reset marker](#data-reset-marker) with `n`.
15. `RENAME`, `COMMIT`: `commitGeneration(staging/doro-<n>.db, n, slot)` with the current slot (origins `Ready`, `Corrupt`) or the `PLAIN` slot (origin `KeyUnavailable`).
16. `DATA_RESET`: `SettingsStore.onDataReplaced()`, then delete the marker ([Commit and reset](#commit-and-reset)).
17. `REOPEN` (origin `Ready`): the exclusive block returns `Reopen.Generation(n, newKey = null, newData = true)`: the holder opens `n` with its own key, which is the same DEK, and increments `dataset`.
18. `DELETE_OLD`: origin `Ready`, only after `REOPEN` returned `Opened`: delete generation `G` (after a failed reopen it is left for startup reconciliation, which deletes it as unreferenced); recovery origins delete every generation file set except `n` right after the commit (the damaged or unreadable files, now explicitly replaced by the user, DEC-85, DEC-86).
19. `DELETE_KEK` (origin `KeyUnavailable`): `KeyVault.deleteKek()`, best effort (a committed switch to `PLAIN`, [security.md](security.md) § 3.1).
20. `CLEANUP`: empty `restore/` and `staging/`; zero whatever key or password bytes remain.
21. Result `Restored(notes, persons, projects, encryptionTurnedOff)`, where `encryptionTurnedOff` is the `encryptionTurnsOff` of step 12; outcome per the [operation frame](#operation-frame).

Any rejection, cancellation or failure before `COMMIT` returned `Committed`, except `CommitOutcome.Unknown`: steps 20 and 21 run with the error (or `RestoreCancelled`), and the live generation and the key-state file are untouched. Before step 13 no gate event is raised: the error dialog appears on the screen that started the restore ([ui.md](ui.md) § 8.7). After step 13, origin `Ready` reopens `G` (`Reopen.Same`) and ends `Reopened`; recovery origins end `Aborted` (T31), back on their screen with the message.

`CommitOutcome.Unknown` (or a `Throwable` after `commitStarted`), any origin: the key-state on disk may already name `n`, so `G` must not be reopened (every write to it would be deleted at the next start). The exclusive block (origin `Ready`) returns `Reopen.StayClosed`, the data-reset marker stays, nothing is deleted, every key is zeroed, `staging/` and `restore/` are emptied only by startup, the result is `Err(OutcomeUnknown)` and the outcome is `Restart` (T30): startup reconciliation then decides from the key-state on disk.

### Restore validation

```kotlin
// package com.doro.organizer.core.result, next to AppError.InvalidBackup(reason)
enum class BackupRejection {
    NotABackup,     // not a SQLite or SQLCipher database, or not this app's (application_id)
    NewerVersion,   // user_version above AppDatabase.VERSION
    Incompatible,   // this app's file, but a schema this build does not accept
    Damaged,        // this app's file, but its content fails a check
}
```

The four values are the four message categories of [ui.md](ui.md) § 8.7, one message each. A wrong backup password is not a rejection: the password question repeats.

Checks in order; the first failure ends the restore with the result shown ([User-facing messages](#user-facing-messages)). The order follows DEC-84; checks 2–7 come first because they need the file to be read or opened at all.

| # | Step | Check | Failure |
|---|---|---|---|
| 1 | `COPY_INPUT` | The document opens and copies completely | `FileAccess`; `StorageFull` for `ENOSPC`/`EDQUOT` |
| 2 | `OPEN_INPUT` | Length ≥ 512 bytes (the smallest SQLite page) | `InvalidBackup(NotABackup)` |
| 3 | `OPEN_INPUT` | Plaintext header: the page size at offset 16 (big-endian u16, 1 meaning 65536) is a power of two from 512 to 65536, and the length is a multiple of it | `InvalidBackup(Damaged)` |
| 4 | `OPEN_INPUT` | No plaintext header: the length is a multiple of 4096 (the SQLCipher 4 page size, [security.md](security.md) § 3.4) and at least 4096 | `InvalidBackup(NotABackup)`, without asking for a password |
| 5 | `OPEN_INPUT` | Encrypted: the copy opens with the backup password (SQLCipher reads `sqlite_schema` after keying) | `WrongPassword`; the question repeats |
| 6 | `OPEN_INPUT` | Plaintext: `SELECT count(*) FROM sqlite_master` succeeds | `InvalidBackup(Damaged)` |
| 7 | `OPEN_INPUT` | `sqlite_master` has at least one row whose name is not `android_metadata` | `InvalidBackup(NotABackup)` |
| 8 | `VALIDATE_INPUT` | `PRAGMA application_id` = 0x444F524F (INV-12) | `InvalidBackup(NotABackup)` |
| 9 | `VALIDATE_INPUT` | `PRAGMA user_version` ≤ `AppDatabase.VERSION` | `InvalidBackup(NewerVersion)`; a value below 1 is `InvalidBackup(Incompatible)` |
| 10 | `VALIDATE_INPUT` | Reference: [create an empty database](#empty-databases) at `restore/reference.db`, read its identity hash (`SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1`) and its schema object set (check 17), then delete it | Classified per the DbMaintenance context (storage full, or `Unexpected`) |
| 11 | `VALIDATE_INPUT` | When `user_version` = `AppDatabase.VERSION`: the candidate's identity hash (same query) equals the reference's; a missing table or row fails | `InvalidBackup(Incompatible)` |
| 12 | `VALIDATE_INPUT` | `PRAGMA integrity_check` returns exactly `ok` | `InvalidBackup(Damaged)` |
| 13 | `VALIDATE_INPUT` | Encrypted candidate: `PRAGMA cipher_integrity_check` returns no row | `InvalidBackup(Damaged)` |
| 14 | `VALIDATE_INPUT` | `PRAGMA foreign_key_check` returns no row | `InvalidBackup(Damaged)` |
| 15 | `VALIDATE_INPUT` | Expected tables: `sqlite_master` has no row of type `trigger` or `view`, and the tables `notes`, `persons`, `projects`, `note_persons`, `note_projects` and `room_master_table` exist | `InvalidBackup(Incompatible)` |
| 16 | `VALIDATE_INPUT` | Every R-check of [data-layer.md § Restore checks](data-layer.md#restore-checks) for the candidate's `user_version` (`RestoreChecks.forVersion`; version 1: R-1 the DEC-22 invariant, R-2 content bytes, R-3 date range, R-4 value types, R-5 name bytes) returns 0 | `InvalidBackup(Damaged)` |
| 17 | `MIGRATE_INPUT`, `CHECK_SCHEMA` | The migration (if any) succeeds; then the candidate's schema object set (a–c below) equals the reference's and its identity hash equals the reference's | Room `IllegalStateException` (missing migration path, failed schema validation): `InvalidBackup(Incompatible)`; storage full: `StorageFull`; object set or hash differs: `InvalidBackup(Incompatible)` |
| — | `OPEN_INPUT` (after check 5 for an encrypted candidate) through `CHECK_SCHEMA` | Any `android.database.sqlite.SQLiteException` except `SQLiteFullException`, thrown by the candidate connection, by the maintenance connection's reads of `main`, or by the temporary Room instance on `incoming.db`: corruption classes, but also a plain `SQLiteException` such as "unsupported file format" (schema-format byte above 4) or a `SQLiteCantOpenDatabaseException` or `SQLiteDiskIOException` on a truncated tail. The statements are fixed, so the file is the cause | `InvalidBackup(Damaged)` |
| — | Any step | Storage full | `StorageFull` |
| — | Any step | An exception from `restore/reference.db`, from the export target or from DbMaintenance's own code; anything else | Classified per the DbMaintenance context of [Failure classification](#failure-classification): `StorageFull`, `DatabaseUnavailable` (Transient) or `Unexpected` (programming error, rolled back) |

- Schema object set, compared between the candidate and the reference of check 10. A hash is only a value stored in `room_master_table`, and Room's open skips its structure check when the hash matches, so a crafted or foreign file with the right table names and a copied hash would otherwise be exported into the live generation and crash every later DAO query. Three parts:
  - (a) The rows `(type, name, tbl_name)` of `sqlite_master`, excluding names that start with `sqlite_autoindex_` and the table `android_metadata` (on both sides), compared as sets.
  - (b) For every table of the reference (the names come from the reference, so nothing from the candidate is interpolated; the name is a bound argument): the rows of `SELECT name, type, "notnull", dflt_value, pk, hidden FROM pragma_table_xinfo(?) ORDER BY name`, of `SELECT "table", "from", "to", on_update, on_delete, "match" FROM pragma_foreign_key_list(?) ORDER BY "table", "from"` and of `SELECT name, "unique", origin, partial FROM pragma_index_list(?) ORDER BY name`, and for every index of the reference the rows of `SELECT seqno, name, "desc", coll, key FROM pragma_index_xinfo(?) ORDER BY seqno`, equal between candidate and reference. Column positions (`cid`) are left out on purpose: a migrated file may legitimately differ in column order.
  - (c) When `MIGRATE_INPUT` did not run: the `sql` of every table and index with runs of whitespace collapsed to one space is equal to the reference's. This catches CHECK constraints, generated-column expressions and column order, which no pragma reports. After a migration, Room's own schema validation has run and the texts may legitimately differ, so (c) is skipped.
  Comparing with a database freshly created by this build, rather than with a hard-coded list, keeps the check correct for every future schema version, and also rejects extra tables, indexes, triggers and views that `sqlcipher_export` would otherwise copy into the live database. Verify at setup: the table-valued pragma functions accept bound arguments in the SQLite inside sqlcipher-android 4.20.0 (API 28 and 35), and a freshly exported backup passes (a)–(c) against the reference.
- The table list of check 15 holds for every version so far. A future schema version that renames one of these tables must turn it into a per-version list ([data-layer.md](data-layer.md) § Schema evolution); the R-checks read version-1 columns for the same reason, which is why `RestoreChecks.forVersion` chooses them by the candidate's version.
- Table names from the candidate are never interpolated into SQL before check 17 has proven that its object set equals the reference's.
- Every rejection deletes `restore/` and `staging/` contents and leaves the live files and the key-state file untouched (L-11 in [testing.md](testing.md)).

### Confirmation

`RestorePreview` carries what the dialog states (DEC-84; wording and layout in [ui.md](ui.md)):

- the number of notes, persons and projects in the backup, counted after migration;
- that all current data will be replaced and that this cannot be undone;
- the resulting encryption state from `resultingMode`: not encrypted (`PLAIN`), encrypted on this device (`DEVICE`), or encrypted with the startup password (`PASSWORD`). From `KeyUnavailable` it is always `PLAIN`.

Everything that can fail on the content of the backup has already run when the question is asked (validation, migration, export, verification), so after "Restore" only the swap remains.

### Commit and reset

- The swap is the [generation commit](#generation-commit) of `staging/doro-<n>.db` as generation `n` with the slot of the table above: an atomic replace (§11). Room is closed first (origin `Ready`), and the old generation's files are deleted only after the new one is committed and, from `Ready`, open (after a failed reopen they are left for startup reconciliation).
- Post-commit reset (DEC-84): `SettingsStore.onDataReplaced()` clears the remembered person and project and increments `dataEpoch`; NavigationState then resets every tab stack to its root and selects All, which drops the filters and searches held in the tab entries' `SavedStateHandle`s, and EditorViewModels drop their save-on-leave ([architecture.md](architecture.md) § Data reset). The [data-reset marker](#data-reset-marker) makes this happen even when the process dies right after the commit.
- `withDb` calls that waited during the swap fail with `DatabaseUnavailable` (`dataset` changed); flows re-subscribe and emit the restored rows ([Observation](#observation)).
- Security settings, the biometric lock, screenshot protection and "Encrypt backups" are untouched; from `KeyUnavailable` only the encryption mode becomes `PLAIN`, as the confirmation said (the exception described under [Origins and target key](#origins-and-target-key)).

### Temporary files of a restore

`restore/incoming.db` (possibly plaintext, when the backup is plaintext), `restore/reference.db` and `staging/doro-<n>.db` exist only during the restore and are deleted by `CLEANUP` whatever the outcome, or at the next cold start. They are never exposed to other apps.

## Key changes

Mechanics only. Which transitions exist, what each asks for, password rules and zeroing policy: [security.md](security.md) § 4 (DEC-61). Every key change, including a plain password change, creates a new DEK (none for target `PLAIN`) and re-encrypts by exporting into a new generation: the database is literally rewritten (§11 "re-encrypts the database"), and the old file stays intact until the commit. `PRAGMA rekey` is never used ([security.md](security.md) § 3.4): it cannot turn a plaintext file into an encrypted one or back, and it rewrites the live file in place.

`changeKey(target, currentPassword, newPassword)`, origin `Ready` only:

1. Frame: mutex.
2. Source `PASSWORD`: `KeyVault.openKey(slot, passwordBytes(currentPassword))` verifies the current password ([security.md](security.md) § 4 step 2). It returns an `UnlockResult` and applies and persists the backoff itself ([security.md](security.md) § 3.6, § 5.3). DbMaintenance maps `PasswordRejected` to `Err(WrongPassword)`, `Throttled` to `Err(Throttled(retryAtElapsedMs))` and `KeyFailed` (or a `KeyVaultException`) to `Err(KeyUnavailable)`, and returns at once; the dialog stays open with the inline error, and no `Maintenance` was entered. A returned `Key` is zeroed at once; the export uses the key held by DatabaseHolder.
3. `PRECHECK`: free space for a second copy of the database.
4. `MaintenanceStarted(KeyChange)` (T27).
5. `PREPARE_KEY`: `KeyVault.prepare(target, newPasswordBytes)` returns `PreparedKey(slot, rawKey)`: new DEK, KEK ensured, PBKDF2 calibration and both wraps for `PASSWORD`, raw-key encoding; it persists nothing. Zero the new password bytes. It runs behind the progress overlay because the calibration takes about a second.
6. `CLOSE_ROOM`: `holder.exclusive { scope -> … }` begins.
7. `CHECK_SOURCE`: maintenance connection on `doro-G.db` with `scope.liveKey`; `quick_check` must return `ok`, otherwise Corrupt: nothing is written, the prepared key is zeroed, the block returns `StayClosed`, the result is `Err(DatabaseUnavailable)` and the outcome `ReopenFailed(Corrupt)`.
8. `EXPORT`: `n` = next generation; `exportTo(staging/doro-<n>.db, prepared.rawKey)`; close the connection.
9. `VERIFY_TARGET` with `prepared.rawKey`: the header shows plaintext exactly when the target is `PLAIN`, and the previous key cannot have been used (L-06).
10. `RENAME`, `COMMIT`: `commitGeneration(staging/doro-<n>.db, n, prepared.slot)`. Generation and slot change in the same `AtomicFile` rewrite (DEC-80).
11. `Committed`: the block returns `Reopen.Generation(n, prepared.rawKey, newData = false)`. The holder zeroes the old key (the old instance and the maintenance connection are closed) and takes ownership of the new one ([security.md](security.md) § 4 step 5). `REOPEN` runs the open sequence on `n`.
12. `DELETE_OLD`, only when step 11's reopen returned `Opened`: `deleteGeneration(G)`. After encryption was turned on this removes the old plaintext file; blocks of it may survive in flash until reused ([security.md](security.md) § 12). After `ReopenFailed` the old generation stays (it may be the only copy known to be readable) and is deleted by startup reconciliation.
13. `DELETE_KEK`, target `PLAIN` only, and only when step 11's reopen returned `Opened`: `KeyVault.deleteKek()` ([security.md](security.md) § 3.1).
14. `CLEANUP`: empty `staging/` and, because the committed key replaced the one earlier share copies were made under, `cacheDir/share/` ([security.md](security.md) § 4 step 5; DEC-83).
15. Result `KeyChanged(from, to)` with `from` = `keyMode` at the start of the operation and `to` = `target`; outcome `Reopened`, or `ReopenFailed(kind)` if step 11's open failed.

- Failure before `Committed` (including `NotCommitted`): zero `prepared.rawKey` after every connection keyed with it is closed, empty `staging/`, return `Reopen.Same`: generation `G` and its slot stay live with the old key. A KEK created in step 5 stays and is reused next time ([security.md](security.md) § 4 step 6).
- `CommitOutcome.Unknown`, or a `Throwable` after `commitStarted` (the exclusive block catches it): return `Reopen.StayClosed` (the holder zeroes the live key), zero `prepared.rawKey`, leave `staging/` for startup, result `Err(OutcomeUnknown)`, outcome `Restart`. Startup reads whichever key-state is on disk and asks for the matching password: after a password change the new one may already be required, which is why the message must not claim that nothing changed.
- After the commit, the next cold start needs what the new slot needs: the new password (`PASSWORD`), the Keystore (`DEVICE`) or nothing (`PLAIN`). Existing backups and share copies are not changed ([security.md](security.md) § 4).
- Navigation and remembered selections stay: the data is the same, so no data-reset marker is written.

## Corruption and KeyUnavailable recovery

Nothing deletes the database without an explicit user action (DEC-85, DEC-86). The library defaults that delete files are excluded by design: SQLCipher is the only engine (DEC-08), its default error handler never deletes, Room is never given a destructive option (DEC-88), and the raw checks keep Room from recreating a missing file.

### Detection points

| Where | What is detected | Gate path |
|---|---|---|
| Startup reconciliation (E1) | Key-state invalid (whatever files exist); key-state missing while a database file is not recognizably plaintext | `StartupKeyFailed` → `KeyUnavailable` (T6), or `Locked(KeyUnavailable)` first when the lock is on (T5) |
| `KeyVault` in E2 | Keystore failure, unusable slot ([security.md](security.md) § 5.2) | `KeyFailed` → `KeyUnavailable` (T23) |
| [Open sequence](#open-sequence) in E2 | Live file missing or empty, header not matching the mode, not readable with the proven key, empty schema, wrong `application_id`, `user_version` < 1, `quick_check` not `ok`, corruption during Room's open | `OpenFailed(Corrupt)` → `Corrupt` (T17), or `Locked(Corrupt)` when the lock is pending |
| Runtime (`withDb`, `observe`) | `SQLiteDatabaseCorruptException`, `SQLiteNotADatabaseException` | `CorruptAtRuntime` → `Corrupt` (T26) or `Locked(Corrupt)` (T13); E3 closes Room |
| DbMaintenance `CHECK_SOURCE` | `quick_check` of the live file before a backup or key change | `ReopenFailed(Corrupt)` → `Corrupt` (T29) |
| DbMaintenance `REOPEN` | The open sequence after an operation | `ReopenFailed(Corrupt)` → `Corrupt` |

### The two screens

- `Corrupt` (DEC-85) and `KeyUnavailable` (DEC-86) offer the same two actions, "Restore from backup" and "Start fresh"; layout and wording are in [ui.md](ui.md). Both appear only after the authentication the gate requires first: the biometric lock when it is on (T5, T13, `lockPending` in T17 and T23), and in `PASSWORD` mode the startup password, except when the key-state cannot even be read (T6). Neither shows note-derived data (DEC-66).
- The files stay on disk until one of the two actions completes. Leaving the app or killing it changes nothing; the next cold start reaches the same screen again (or, if the fault was transient, opens normally).
- Room is closed on both screens (E3, or never opened), so both actions run without exclusive mode, and both end with `Restart` after a commit (T30): the app starts over from `Starting(true)`, which skips the biometric lock but still asks for the password in `PASSWORD` mode ([architecture.md](architecture.md) § SessionGate).

### Start fresh

After the confirmation dialog ([ui.md](ui.md)), `startFresh(origin)` (origins `Corrupt`, `KeyUnavailable`):

1. Frame: mutex; `PRECHECK`; `MaintenanceStarted(StartFresh)` (T33).
2. `CREATE_EMPTY`: `n` = next generation; [create an empty database](#empty-databases) at `staging/doro-<n>.db`.
3. `VERIFY_TARGET` with `ByteArray(0)`.
4. `MARK_RESET`; `RENAME`, `COMMIT`: `commitGeneration(staging/doro-<n>.db, n, keyVault.prepare(PLAIN, null).slot)`. This rewrites a missing or invalid key-state too; that is the user's explicit action ([security.md](security.md) § 3.5).
5. `DATA_RESET`: `SettingsStore.onDataReplaced()`; delete the marker.
6. `DELETE_OLD`: `deleteGeneration` for every other generation file set, which runs `net.zetetic.database.sqlcipher.SQLiteDatabase.deleteDatabase(File)` on each: this is the deletion of the corrupted or unreadable database that §5 asks for, done only now.
7. `DELETE_KEK`: `KeyVault.deleteKek()`, best effort (a committed switch to `PLAIN`).
8. `CLEANUP`; result `StartedFresh`; outcome `Restart`. Startup then reads `Proceed(n, PLAIN)` and opens the empty database.

- The new generation is committed before the old files are deleted, so a crash never leaves the app without a valid key-state and database.
- `CommitOutcome.Unknown`, or a `Throwable` after `commitStarted`: nothing is deleted, the marker stays, every key is zeroed, the result is `Err(OutcomeUnknown)` and the outcome is `Restart`; startup reconciliation decides from the key-state on disk (a valid key-state naming `n` applies the reset and opens the empty database; otherwise the same recovery screen returns, files kept).
- The UI shows the encryption-off notice after every Start fresh (DEC-85), from `Finished(StartFresh, Ok(StartedFresh))`, through the shell's `MaintenanceStatusHost` once the app is back in `Ready` ([ui.md § 8.12](ui.md#812-maintenance-status-host), [§ 10.7](ui.md#107-corrupt-and-keyunavailable-screens)); its wording is true whatever the previous mode was ([ui.md](ui.md)). The notice lives in memory only: a process death right after Start fresh loses it, while Settings shows encryption off either way.
- Everything else in `SettingsStore` stays: biometric lock, screenshot protection, "Encrypt backups" (L-12 in [testing.md](testing.md)); only the two backoff values reset, because `KeyVault.commit` of the `PLAIN` slot resets them ([security.md](security.md) § 4.1). Only the encryption mode is now `PLAIN`, recorded in the key-state. The Erase settings reset is deliberately not reused here, because DEC-85 changes only the encryption state of the data; reusing it would be a new decision.

### Restore from these screens

"Restore from backup" runs the normal [Restore](#restore) with origin `Corrupt` or `KeyUnavailable` (`restore(uri, origin)`): the same picker, validation, migration, export and confirmation, shown over the recovery screen, which stays in place until the user confirms; Room stays closed throughout; the key comes from `UNLOCK_DEVICE_KEY` (`Corrupt`) or is empty with target `PLAIN` (`KeyUnavailable`); after the confirmation the gate enters `Maintenance(Restore, origin)` (T33), and after the commit every other generation file set is deleted and the outcome is `Restart`. A rejected or cancelled restore leaves the user on the same screen with its message, files untouched.

### Erase (forgotten password)

Policy, screens and the typed confirmation: [security.md](security.md) § 5.4 (DEC-63). Mechanics, as operation `Erase` from `NeedsPassword` (T8); Room is never open:

1. Frame: mutex; `MaintenanceStarted(Erase)`.
2. `DELETE_OLD`: while the key-state still names `G`, delete every generation file set except `G` (`deleteGeneration`). If any of them is still present afterwards, end with `Err(DatabaseUnavailable)` and outcome `Aborted`: nothing was erased, and the user can try again. Without this step, a leftover unreferenced plaintext generation from before encryption was turned on (its earlier deletion failed and was left to the next cold start) would survive an Erase that died after deleting `G`, and the next start would adopt it (`AdoptPlain`) and silently bring back notes the user chose to erase.
3. `ERASE_KEY_STATE`: `KeyVault.eraseState()` deletes `db-state.json` (which makes encrypted data unreadable at once), then the KEK.
4. `ERASE_FILES`: `DbFiles.deleteEverything()`: `deleteGeneration` for every generation file set (`G` last), then the contents of `staging/`, `restore/` and `cacheDir/share/`, and the data-reset marker.
5. `RESET_SETTINGS`: [security.md](security.md) § 5.4 step 4 (`SettingsStore.resetSecurity()`, then `SettingsStore.onDataReplaced()`).
6. Result `Erased`; outcome `Restart`. Startup finds no key-state and no file (`FirstRun`), resets the settings again (no change), and `completeStartup` creates generation 1: the first-run state, with Restore available in Settings.

Interrupted Erase: see the [crash-safety matrix](#crash-safety-matrix). An Erase that stopped between steps 3 and 4 leaves encrypted files without a key-state: startup shows `KeyUnavailable`, whose Start fresh deletes those files but does not reset the security settings (it keeps every setting, DEC-85; [security.md](security.md) § 5.4 words the same limit); the two backoff values are reset by that Start fresh's `PLAIN` commit. One that stopped between steps 4 and 5 is completed by the first-run settings reset of [reconciliation](#startup-reconciliation) step 7.

## Crash-safety matrix

A crash is process death at any instant (kill, crash, OOM, reboot); "crash at X" means every step before `X` completed and `X` did not finish, so a step that renames or commits also includes the instant after its system call and before its next one. Every row also holds for a power cut, because the [generation commit](#generation-commit) syncs the staged file before renaming it, the rename before the key-state names it, and the key-state before anything older is deleted (and the [data-reset marker](#data-reset-marker) is durable before the commit). Startup always runs [reconciliation](#startup-reconciliation) first; the gate then applies the usual lock and password rules (DEC-87, L-15 in [testing.md](testing.md)). Every row has an identifier `CS-nn`. L-15 asserts the "Next start" column of the row whose Operation and "Crash at" columns cover an (operation, step) pair of the [step table](#pipeline-steps-and-fault-hook), and its meta-test fails for a pair that no row covers. Identifiers are never renumbered: a new row takes the next number.

| ID | Operation | Crash at | On disk afterwards | Next start |
|---|---|---|---|---|
| CS-01 | Backup, key change, Start fresh, Erase | Before `MaintenanceStarted` | Unchanged (a backup may have created its empty document, or may have had a pre-existing target that is still untouched) | Normal start |
| CS-02 | First run (`completeStartup`) | `PRECHECK`, `CREATE_EMPTY`, `VERIFY_TARGET`, `RENAME` before `Os.rename` | No key-state; maybe a partial `staging/doro-<n>.db` | Staging emptied; `FirstRun` again |
| CS-03 | First run | `RENAME` after `Os.rename` returned, `COMMIT` before the `AtomicFile` rename | No key-state (an interrupted first `AtomicFile` write leaves only a `.new` file, which reads as missing); plaintext `databases/doro-<n>.db` | `AdoptPlain(n)`; `completeStartup` commits it |
| CS-04 | First run | `COMMIT` after the `AtomicFile` rename, `DELETE_OLD` | Key-state `(n, PLAIN)` | `Proceed(n, PLAIN)`; other file sets deleted |
| CS-05 | Adopt plaintext (`completeStartup`) | `COMMIT` before the `AtomicFile` rename | No key-state; plaintext `databases/doro-<g>.db` | `AdoptPlain(g)` again |
| CS-06 | Adopt plaintext | `COMMIT` after the `AtomicFile` rename, `DELETE_OLD` | Key-state `(g, PLAIN)`; maybe other generation sets | `Proceed(g, PLAIN)`; other sets deleted |
| CS-07 | Backup, either path | `CLOSE_ROOM` to `VERIFY_TARGET` | Generation `G` intact (its journal mode may be DELETE; a hot `-journal`, if any, is kept for SQLite); maybe a partial `staging/backup.db`; on the document path the picked document exists: empty if the picker created it, otherwise with its previous content untouched | Staging emptied; normal start. A document the picker created stays empty at the chosen location: after process death the app no longer holds its URI. Documented limitation |
| CS-08 | Backup to a document | `WRITE_DOCUMENT`, `CLEANUP` | As above; the document may be partially written (the old content of a pre-existing target is gone once the stream was opened) | As above; the partial document stays. Documented limitation |
| CS-09 | Backup for sharing | `PUBLISH_SHARE` or later | A share file in `cacheDir/share/` | Deleted at cold start (DEC-83) |
| CS-10 | Key change | `PREPARE_KEY` to `VERIFY_TARGET`, or `RENAME` (including after `Os.rename`) | Old key-state, generation `G` intact; maybe a partial `staging/doro-<n>.db`, or an unreferenced `databases/doro-<n>.db`; maybe a new KEK | Staging emptied; generation `n` deleted if present; normal start with the old mode and key. A KEK created by `PREPARE_KEY` stays and is reused ([security.md](security.md) § 3.1) |
| CS-11 | Key change | `COMMIT` before the `AtomicFile` rename | Old key-state; unreferenced `databases/doro-<n>.db` | `Proceed(G, old mode)`; generation `n` deleted |
| CS-12 | Key change | `COMMIT` after the `AtomicFile` rename, `REOPEN`, `DELETE_OLD` | Key-state `(n, new slot)`; maybe the unreferenced generation `G` | `Proceed(n, new mode)`; generation `G` deleted. The startup password is the new one |
| CS-13 | Key change to `PLAIN` | `DELETE_KEK`, `CLEANUP` | Key-state `(n, PLAIN)`; the KEK still exists; maybe share files | `Proceed(n, PLAIN)`; the leftover KEK is harmless and is reused or replaced by the next encryption |
| CS-14 | Any operation | `CommitOutcome.Unknown` (read-back failed, or a `Throwable` after `commitStarted`) | Either key-state; marker `n` if the operation writes one; nothing deleted | Decided from the key-state on disk: a Valid key-state naming `n` is `Proceed(n)` (marker applies the reset, old generation deleted); the old one is `Proceed(G)` (generation `n` deleted) |
| CS-15 | Restore, any origin | `COPY_INPUT` to `AWAIT_CONFIRMATION`, or `CLOSE_ROOM` | Old key-state and data; partial files in `restore/` and `staging/` | Temporary files emptied; normal start (or the same recovery screen); the user starts the restore again |
| CS-16 | Restore from `Ready` or `Corrupt` | `MARK_RESET`, `RENAME` (either side of `Os.rename`), `COMMIT` before the `AtomicFile` rename | Old Valid key-state; marker `n`; maybe an unreferenced `databases/doro-<n>.db` | Marker ≠ the key-state's generation: deleted without a reset; generation `n` deleted; old data (origin `Corrupt`: the `Corrupt` screen again, files kept) |
| CS-17 | Restore from `KeyUnavailable` | The same steps | Missing or Invalid key-state; marker `n`; maybe an unreferenced plaintext `databases/doro-<n>.db` next to the unreadable files | Key-state not Valid: nothing was committed, so the marker is deleted without a reset and nothing in `databases/` is deleted (DEC-86): `KeyUnavailable` again; the next restore or Start fresh numbers above `n`. If the key-state is Valid (a Keystore failure, T23): marker deleted without a reset, `Proceed(G)`, generation `n` deleted, E2 raises `KeyFailed`: `KeyUnavailable` again |
| CS-18 | Restore, any origin | `COMMIT` after the `AtomicFile` rename, `DATA_RESET` | Key-state `(n, slot)`; marker `n`; old generation(s) | Marker = committed `n`: `SettingsStore.onDataReplaced()`, marker deleted; old generations deleted. Navigation, remembered selections and filters are reset (DEC-84) |
| CS-19 | Restore, any origin | `REOPEN`, `DELETE_OLD`, `CLEANUP` | Key-state `(n, slot)`; maybe old generation(s) | `Proceed(n, mode)`; old generations deleted |
| CS-20 | Restore from `KeyUnavailable` | `DELETE_KEK` | Key-state `(n, PLAIN)`; the old KEK | `Proceed(n, PLAIN)`; leftover KEK harmless |
| CS-21 | Start fresh | `PRECHECK`, `CREATE_EMPTY`, `VERIFY_TARGET`, `MARK_RESET` | Unchanged except temporary files and maybe the marker | Same recovery screen as before, after authentication (a marker without a committed `n` is deleted without a reset) |
| CS-22 | Start fresh | `RENAME` (either side of `Os.rename`), `COMMIT` before the `AtomicFile` rename | Marker `n`; unreferenced `databases/doro-<n>.db` next to the old files | Valid key-state (from `Corrupt`, or from `KeyUnavailable` after a Keystore failure, T23): marker deleted without a reset, generation `n` deleted, `Corrupt` again (or `Proceed(G)` and `KeyFailed`). Missing or invalid key-state (from `KeyUnavailable`): marker deleted without a reset (nothing was committed), `KeyUnavailable` again, all files kept; the next Start fresh numbers above `n` and deletes everything else |
| CS-23 | Start fresh | `COMMIT` after the `AtomicFile` rename, `DATA_RESET`, `DELETE_OLD`, `DELETE_KEK`, `CLEANUP` | Key-state `(n, PLAIN)`; maybe marker `n`; maybe old files and the KEK | `Proceed(n, PLAIN)`: reset applied if the marker is still there, old generations deleted, empty database opens. The encryption-off notice is lost (it lives in memory) |
| CS-24 | Erase | `DELETE_OLD`, `ERASE_KEY_STATE` before `db-state.json` is deleted | Key-state unchanged; maybe fewer unreferenced generations | `NeedsPassword` again; the user can repeat the Erase |
| CS-25 | Erase | `ERASE_KEY_STATE` after `db-state.json` is deleted, `ERASE_FILES` before `doro-G.db` is deleted | No key-state; encrypted generation `G`; maybe the KEK | `KeyUnavailable` (DEC-86); its Start fresh deletes the files but keeps every setting except the two backoff values, which its `PLAIN` commit resets ([security.md](security.md) § 5.4) |
| CS-26 | Erase | `ERASE_FILES` after `doro-G.db` is deleted, `RESET_SETTINGS` | No key-state, no main file (maybe orphan sidecars); settings maybe not yet reset | `FirstRun`: reconciliation step 7 resets the security settings and calls `onDataReplaced()`, completing the Erase |
| CS-27 | Startup reconciliation | Deleting unreferenced generations or temporary files | Some of them deleted | Repeated; every deletion is idempotent |
| CS-28 | Any operation | `DELETE_OLD` partly done (main file gone, sidecars left) | Orphan sidecars of an unreferenced generation | Deleted as an unreferenced generation file set |

Invariants the matrix proves (each is a test in L-15):

1. After any crash, startup reaches either the old state (old key-state with its file) or the new one (new key-state with its file); never a key-state that names a missing file, and never a file keyed differently from what the key-state says.
2. The only automatic deletions are of temporary files and of generations that are not referenced by a valid key-state whose own file exists.
3. After the first successful open following a crash, exactly one generation exists and `staging/`, `restore/` and `cacheDir/share/` are empty (share files that are younger than one hour and survive a same-process `Restart` excepted), except in the `KeyUnavailable` rows, which keep every file until the user acts.
4. A data reset happens whenever a Restore or Start fresh was committed (a Valid key-state naming the marker's number), and never when it was not: a Missing or Invalid key-state at the marker's startup means nothing was committed. The only extra reset is the repeat after a crash between `DATA_RESET` and the deletion of the marker, which is harmless, and an unparseable marker, which `AtomicFile` cannot produce.

## Concurrency rules

Who may hold a connection, by gate state:

| Gate state | Live generation `databases/doro-G.db` | Files under `noBackupFilesDir` |
|---|---|---|
| `Starting` (E1) | No connection. Reconciliation only reads headers and deletes unreferenced generations | Temporary directories emptied; nothing open |
| `Opening` (E2) | In order, never two at once: `completeStartup`'s commit (no connection), the raw verification connection, then Room | First run: a temporary Room instance, then a verification connection, on `staging/doro-<n>.db` |
| `Ready`, `Locked(Ready)` | Room only | A restore before its confirmation: the candidate connection, temporary Room instances, the maintenance connection and verification connections on `restore/` and `staging/` files, one at a time |
| `Maintenance(op, Ready)` (or `Locked(Maintenance(op, Ready))`) before `CLOSE_ROOM` and after `REOPEN` | Room, still serving the content behind the overlay | Backup: the verification connection on `staging/backup.db` after `REOPEN` |
| `Maintenance(op, Ready)` between `CLOSE_ROOM` and `REOPEN` | Backup and key change: the maintenance connection, then nothing. Restore: nothing. Room is closed | Export target and its verification connection |
| `Corrupt`, `KeyUnavailable`, `NeedsPassword`, `Locked(Open)`, `Locked(Corrupt)`, `Locked(KeyUnavailable)`, `Opening(transientError)`, and `Maintenance` with those origins | None: E3 closed Room and zeroed the key, or Room was never opened (DEC-90) | Restore (all of its steps), Start fresh and Erase work here |

Rules:

1. One owner per live file at a time. Room's pool, the raw verification connection and the maintenance connection never overlap on `doro-G.db`, except for a retired Room query that outlives the 2 s drain timeout of [exclusive mode](#exclusive-mode) step 4 (SQLite's in-process locking keeps that case safe); each raw connection is closed in `finally` (`use { }`) before the next one opens.
2. Never ATTACH through Room (DEC-82): only the maintenance connection attaches, and only its export target. Never open a second Room instance on the live file. Never use the framework SQLite, `FrameworkSQLiteOpenHelperFactory` or `AndroidSQLiteDriver` for any of these files (DEC-08).
3. No file operation on a generation while a connection to it is open. The live generation's files are never renamed, copied, truncated or deleted at all; files become live only by the rename of the commit, and stop being live only by a later commit (Erase excepted, which runs with no key-state and no connection).
4. One maintenance operation at a time: `opMutex` covers every user operation, `reconcileAtStartup` and `completeStartup`. User operations use `tryLock` (`Busy` otherwise); the startup functions wait for it. `sweepShareFiles()` needs no lock (it deletes only share files at least one hour old).
5. Lock order: `opMutex`, then DatabaseHolder's transition mutex. DatabaseHolder never waits for `opMutex`; DbMaintenance never calls `withDb`; `exclusive` never nests.
6. Temporary Room instances belong to DbMaintenance: built only on files under `noBackupFilesDir`, only in `Opening` (first run), during a restore's checking phase (migration of the candidate, reference database; the gate is `Ready`, `Corrupt` or `KeyUnavailable`) and during `Maintenance(StartFresh, …)`, held in local variables only and closed before their step ends. They never touch `databases/`. The live instance belongs to DatabaseHolder alone and is built only by `open` (E2) and by exclusive mode's reopen (DEC-81, DEC-90). DEC-90 and CLAUDE.md rule 5 say "Room is built only in `Opening`" without this qualification; they mean the live instance, and the temporary instances above are the intended exception.
7. A key array is zeroed only after every connection keyed with it is closed: SQLCipher keys pooled connections opened later from the same array ([Key custody](#key-custody)).
8. Threads: all file and SQLCipher work runs on `dispatchers.io`; `KeyVault` runs PBKDF2 on `dispatchers.default` ([security.md](security.md)). `MaintenanceListener` calls are made inside `withContext(dispatchers.main)`, so the gate has changed before DbMaintenance continues; `CorruptionListener` launches its dispatch on `dispatchers.main` without blocking the reporting thread ([architecture.md](architecture.md) § Threading: `SessionGate.dispatch` runs on the main thread only); `FlowProblemListener` only posts to the thread-safe `AppMessages` queue. The main thread never touches these files.
9. One process: the app's databases are opened only by the app's main process. The debug-only `:crashtest` process of [testing.md](testing.md) runs while the instrumentation process has nothing open.
10. Clocks: no lifecycle decision depends on the wall clock except the age test of the share sweep and the date in the backup file name. Generations are ordered by number, never by file time, so a clock change cannot make an older file look newer.
