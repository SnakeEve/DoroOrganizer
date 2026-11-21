# Data layer

Defines the Room schema, its invariants, every query and every write rule for notes, persons, projects and their links.

Related: [spec](../stakeholder-notepad-spec.md) · [decisions.md](decisions.md) · [architecture.md](architecture.md) · [db-lifecycle.md](db-lifecycle.md) · [security.md](security.md) · [ui.md](ui.md) · [testing.md](testing.md)

## Model overview

- §9 is the logical model. The entities below and the exported schema JSON under `app/schemas/` (DEC-07) are the source of truth (DEC-30); every difference from §9 is listed in the table. Room also creates `room_master_table`, and `AUTOINCREMENT` creates `sqlite_sequence`. The §9 "Key Queries" are argument settings of the single perspective query ([Read model](#perspective-query)).
- All SQL is static: `@Query` strings are built only from `const val` fragments, with a bind parameter for every value (§11 "Parameterized queries / Room only"). `@RawQuery`, `SimpleSQLiteQuery` and SQL assembled at runtime are forbidden.
- DAOs: `NoteDao` (notes, junction rows, list queries), `PersonDao` and `ProjectDao` (entities, impact counts, pickers); the full method list is in [DAO surface](#dao-surface). Reads return `Flow` or are `suspend`; writes are `suspend`. Only `NoteRepository` and `EntityRepository` call DAOs, always through `DatabaseHolder` ([db-lifecycle.md § DatabaseHolder](db-lifecycle.md#databaseholder)); nothing caches a DAO or the database (DEC-81).

| §9 | Implemented | Source |
|---|---|---|
| timestamps nullable, unit unstated | epoch milliseconds (UTC) from `AppTime`; `created_at`, `updated_at` NOT NULL on `notes`, `persons` and `projects`; `checked_at` nullable, set exactly when `checked = 1` | DEC-20, DEC-22 |
| no search column | `notes.content_fold TEXT NOT NULL` | DEC-29 |
| `idx_note_persons_note`, `idx_note_projects_note` | not created (both composite primary keys lead with `note_id`); every other §9 index name is kept through `Index(name = …)` | DEC-30 |
| `persons.notes` | same column, Kotlin property `remarks` | DEC-30 |
| no file identity | `application_id = 0x444F524F` ("DORO"), `user_version` = 1 | DEC-30 |
| `content` length unbounded | at most `MAX_CONTENT_CHARS` = 200,000 characters; empty content is `""` | DEC-24 |
| `notes` columns `id, content, date, checked, checked_at, created_at, updated_at` | `id, date, checked, checked_at, created_at, updated_at, content, content_fold` (the large columns last) | [Size and performance](#size-and-performance) |
| `name TEXT NOT NULL` | trimmed, non-blank, at most `MAX_NAME_CHARS` = 200 characters (INV-9) | DEC-32; the 200 limit is the picker's input limit in [ui.md § 7.2](ui.md#72-layout) |
| `date` any epoch day | NULL or within `EpochDays.MIN..MAX` = 1900-01-01..2100-12-31 (INV-5) | DEC-26; the bounds are the date pickers' year range ([ui.md § 3.5](ui.md#35-date-filter-sheet)) |
| `role`, `notes`, `description`, `status` | kept, always NULL in v1, no UI | DEC-31 |

### Types and packages

The packages follow the import rules of [architecture.md § Import rules](architecture.md#import-rules): `ui` sees only `model` types, `NoteRepository` and `EntityRepository`.

| Package | Types |
|---|---|
| `model` (pure Kotlin: stdlib and `java.time` only) | `Perspective`, `DateFilter`, `NoteFilter`, `NoteQuery`, `NoteFilter.toQuery()`, `SearchText`, `TextWhitespace`, `MAX_CONTENT_CHARS`, `MAX_NAME_CHARS`, `EpochDays`, `EntityRef`, `NoteRow`, `NoteDetail`, `EntityOption`, `NoteDraft`, `NewEntity`, `SaveResult`, `BatchResult`, `DeleteImpact`, `EntityDeleteResult`; `EntityKind` is defined in [ui.md § 3.2](ui.md#32-perspectivespec) |
| `data.db` | entities and cross-refs, `NoteRowRaw`, `NoteDetailRow`, `SaveProbe`, `EntityOptionRow`, the DAOs and their unannotated supertype `EntityDao` ([DAO surface](#dao-surface)), `AppDatabase`, the SQL constants, `MIGRATIONS`, `RestoreChecks` |
| `data` | `DatabaseHolder`, `NoteRepository`, `EntityRepository`, `CreatedIds`, the internal helpers `EntityRepository.insertNew`, `newEntityError`, `writeTransaction`, `AppDatabase.transaction` and `AppDatabase.entities(kind)`, and the mapper `NoteRowRaw` → `NoteRow` (`org.json`, `Collator`) |

- `model` constants: `const val MAX_CONTENT_CHARS = 200_000` (UTF-16 units, DEC-24), `const val MAX_NAME_CHARS = 200`, and `object EpochDays { const val MIN = -25_567L; const val MAX = 47_846L }` (1900-01-01 and 2100-12-31 as epoch days).
- Types that reach a `SavedStateHandle` are stored by the ViewModels as primitive values and arrays ([ui.md § 3.10](ui.md#310-list-state-and-saved-state), [§ 6.14](ui.md#614-editor-state)), so the model types need no serialization annotations.
- Every data class in this doc overrides `toString()` and prints ids, counts and lengths only, never text (DEC-70; [security.md § 10](security.md#10-logging-and-leak-hygiene-dec-70); `ArchitectureTest`, I-39 in [testing.md § 4](testing.md#4-invariant-catalog)). The sketches show the override where the output is not obvious from the class name: ids for `NoteEntity`, `PersonEntity`, `ProjectEntity`, `NoteRow`, `NoteRowRaw`, `NoteDetail`, `NoteDetailRow`, `EntityRef`, `EntityOption`, `EntityOptionRow`; counts for `BatchResult`, `DeleteImpact`, `EntityDeleteResult`, `CreatedIds`; the class name alone for `SaveProbe`.

## Entities

```kotlin
@Entity(
    tableName = "notes",
    indices = [Index("date", name = "idx_notes_date"), Index("created_at", name = "idx_notes_created"),
        Index("updated_at", name = "idx_notes_updated"), Index("checked_at", name = "idx_notes_checked_at")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,      // INTEGER PRIMARY KEY AUTOINCREMENT
    val date: Long?,                                        // epoch day (DEC-26), INV-5
    @ColumnInfo(defaultValue = "0") val checked: Boolean,   // INTEGER NOT NULL DEFAULT 0
    @ColumnInfo(name = "checked_at") val checkedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val content: String,                                    // the two large columns stay last
    @ColumnInfo(name = "content_fold") val contentFold: String,
) { override fun toString() = "NoteEntity(id=$id)" }

@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: String? = null, @ColumnInfo(name = "notes") val remarks: String? = null,   // NULL in v1 (DEC-31)
    @ColumnInfo(name = "created_at") val createdAt: Long, @ColumnInfo(name = "updated_at") val updatedAt: Long,
) { override fun toString() = "PersonEntity(id=$id)" }

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null, val status: String? = null,                         // NULL in v1 (DEC-31)
    @ColumnInfo(name = "created_at") val createdAt: Long, @ColumnInfo(name = "updated_at") val updatedAt: Long,
) { override fun toString() = "ProjectEntity(id=$id)" }

@Entity(
    tableName = "note_persons", primaryKeys = ["note_id", "person_id"],
    foreignKeys = [ForeignKey(NoteEntity::class, ["id"], ["note_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PersonEntity::class, ["id"], ["person_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("person_id", name = "idx_note_persons_person")],
)
data class NotePersonCrossRef(
    @ColumnInfo(name = "note_id") val noteId: Long, @ColumnInfo(name = "person_id") val personId: Long) {
    override fun toString() = "NotePersonCrossRef(noteId=$noteId, personId=$personId)"
}

@Entity(
    tableName = "note_projects", primaryKeys = ["note_id", "project_id"],
    foreignKeys = [ForeignKey(NoteEntity::class, ["id"], ["note_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ProjectEntity::class, ["id"], ["project_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("project_id", name = "idx_note_projects_project")],
)
data class NoteProjectCrossRef(
    @ColumnInfo(name = "note_id") val noteId: Long, @ColumnInfo(name = "project_id") val projectId: Long) {
    override fun toString() = "NoteProjectCrossRef(noteId=$noteId, projectId=$projectId)"
}

@Database(
    entities = [NoteEntity::class, PersonEntity::class, ProjectEntity::class,
        NotePersonCrossRef::class, NoteProjectCrossRef::class],
    version = AppDatabase.VERSION, exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun personDao(): PersonDao
    abstract fun projectDao(): ProjectDao
    companion object {
        const val VERSION = 1                                 // the @Database(version) value, read by db-lifecycle.md
        const val APPLICATION_ID = 0x444F524F                 // "DORO" = 1146049103 (DEC-30)
        val MIGRATIONS: Array<Migration> = emptyArray()       // see Schema evolution
        val CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) =
                db.execSQL("PRAGMA application_id = $APPLICATION_ID")
        }
    }
}
```

- `note_id` leads each composite primary key, which covers its foreign key, so Room raises no `MISSING_INDEX_ON_FOREIGN_KEY_CHILD` warning without the dropped `*_note` indexes.
- Because the entities declare foreign keys, Room turns `PRAGMA foreign_keys` on for its connections after migrations ([Schema evolution](#schema-evolution)); an instrumented test proves enforcement (I-03 in [testing.md § 4](testing.md#4-invariant-catalog)). `CASCADE` is only a safety net: code deletes junction rows explicitly ([Deletes](#deletes)).
- There is no `UNIQUE` constraint on names or content (§1 no deduplication, DEC-32).
- Room emits columns in field order, which keeps `content` and `content_fold` at the end of each record ([Size and performance](#size-and-performance)). Verify at setup: the `createSql` of `notes` in `1.json` ends with these two columns, and Room's KSP processor accepts `AppDatabase.VERSION` (a `const val` of the companion) as the annotation argument.
- The builder in `DatabaseHolder` adds `.addCallback(AppDatabase.CALLBACK)` and `.addMigrations(*AppDatabase.MIGRATIONS)` ([db-lifecycle.md § Room builder](db-lifecycle.md#room-builder)). `onCreate` runs only for a new file. `sqlcipher_export` does not copy `application_id`, so `DbMaintenance` sets it on every export (DEC-82).
- Verify at setup: with `openHelperFactory`, Room 2.8.5 calls the `SupportSQLiteDatabase` overload of `Callback.onCreate` (an instrumented test reads `PRAGMA application_id` on a fresh database).

## Invariants

Invariant IDs are `I-n`, unpadded. Other docs cite them with this doc's name; the catalog of [testing.md § 4](testing.md#4-invariant-catalog) has its own zero-padded IDs (`I-01` …).

| ID | Invariant | Enforced by |
|---|---|---|
| INV-1 | `notes.content` is never NULL; empty content is `""`; length ≤ `MAX_CONTENT_CHARS` = 200,000 UTF-16 units (DEC-24) | `NOT NULL`; `require` in `NoteRepository`. The editor truncates on input and, for a longer stored note, on load ([ui.md § 6.9](ui.md#69-content-limit)), so `save` never receives more. Restore check R-2 bounds only the byte size (CursorWindow safety), not the unit count |
| INV-2 | `content_fold == SearchText.fold(content)` in every row (DEC-29) | Single write path: only `NoteRepository` writes `content`, always together with `content_fold`. Not checkable in SQL (no ICU); restores keep the exported column. It holds for the Unicode tables of the Android version that wrote the row: after a restore onto, or an update to, another Android version, a row can differ for characters whose case mapping changed between Unicode versions, and a search for such a character can miss it. Accepted v1 limit; the next edit of the note rewrites the fold |
| INV-3 | `checked` ∈ {0, 1} and `checked = 1` ⇔ `checked_at IS NOT NULL` (DEC-22) | Guarded toggles; an insert sets `checked_at = created_at` when created checked; saves never write these columns; restore check R-1 |
| INV-4 | `created_at` and `updated_at` are NOT NULL on all three entity tables, taken from `AppTime.nowMillis()`, one `now` per transaction (DEC-20) | `NOT NULL`; repositories capture `now` once per transaction |
| INV-5 | `date` is NULL or an epoch day within `EpochDays.MIN..EpochDays.MAX` (1900-01-01..2100-12-31) | `require` in `NoteRepository.save` and `setDate`; restore check R-3. The bounds are the year range of every date picker and of `MonthGrid` ([ui.md § 3.5](ui.md#35-date-filter-sheet)). Verify at setup: the pinned Material 3 `rememberDatePickerState` throws `IllegalArgumentException` for an initial date outside its `yearRange`, which is why a stored date outside the range would crash the editor. `LocalDate.ofEpochDay` itself accepts far larger values |
| INV-6 | Orphan is derived, never stored: no `note_persons` row and no `note_projects` row (DEC-27) | No column exists; one predicate constant, `ORPHAN_PRED` |
| INV-7 | Every junction row references an existing note and an existing person/project | Foreign keys; explicit junction deletes; writes drop vanished ids before inserting links; restore `foreign_key_check` (DEC-84) |
| INV-8 | A note is linked to a given entity at most once | Composite primary key; `INSERT OR IGNORE` |
| INV-9 | Names are trimmed (`TextWhitespace.trim`, the whitespace rule of DEC-24), non-blank and at most `MAX_NAME_CHARS` = 200 UTF-16 units; duplicates are allowed (DEC-32) | Names enter only through `NoteRepository.save` and `NoteRepository.associate` (DEC-33). Both run the pre-write check `newEntityError` before any write and before detaching: a name that is empty after `TextWhitespace.trim` returns `Err(InvalidInput(InputRule.BlankName))` (DEC-32), a longer name is a `require` failure (the pickers cut their input at 200, [ui.md § 7.2](ui.md#72-layout)). `EntityRepository.insertNew` then stores `TextWhitespace.trim(name)` inside the caller's transaction and only `require`s a non-blank name, so a blank name reaching it is a programming error ([Transactions and results](#transactions-and-results)); restore check R-5 bounds the byte size; blank or untrimmed names are not checked at restore (they only render as an empty chip) |
| INV-10 | `role`, `persons.notes`, `description` and `status` are NULL (DEC-31) | No write path sets them; not checked at restore |
| INV-11 | Ids are never reused within one database file (DEC-30) | `AUTOINCREMENT`; table rebuilds keep `sqlite_sequence` ([Schema evolution](#schema-evolution)) |
| INV-12 | `application_id = 0x444F524F`; `user_version` = `AppDatabase.VERSION` | `CALLBACK.onCreate` and Room; exports and restore validation ([db-lifecycle.md § Restore validation](db-lifecycle.md#restore-validation)) |

### Restore checks

Restore validation (DEC-84, [db-lifecycle.md § Restore validation](db-lifecycle.md#restore-validation), check 16) runs every R-check of the staged copy's version on the candidate connection; each statement must return 0. They run before the migrations, so they read the backup's own schema version.

```sql
SELECT COUNT(*) FROM notes WHERE checked NOT IN (0, 1) OR (checked = 1) <> (checked_at IS NOT NULL);  -- R-1
SELECT COUNT(*) FROM notes WHERE length(CAST(content AS BLOB)) > 600000;                              -- R-2
SELECT COUNT(*) FROM notes WHERE date IS NOT NULL AND date NOT BETWEEN -25567 AND 47846;              -- R-3
SELECT (SELECT COUNT(*) FROM notes WHERE typeof(content) <> 'text' OR typeof(content_fold) <> 'text'
            OR typeof(checked) <> 'integer' OR typeof(date) NOT IN ('integer', 'null')
            OR typeof(checked_at) NOT IN ('integer', 'null')
            OR typeof(created_at) <> 'integer' OR typeof(updated_at) <> 'integer')
     + (SELECT COUNT(*) FROM persons WHERE typeof(name) <> 'text'
            OR typeof(created_at) <> 'integer' OR typeof(updated_at) <> 'integer')
     + (SELECT COUNT(*) FROM projects WHERE typeof(name) <> 'text'
            OR typeof(created_at) <> 'integer' OR typeof(updated_at) <> 'integer');                    -- R-4
SELECT (SELECT COUNT(*) FROM persons WHERE length(CAST(name AS BLOB)) > 600)
     + (SELECT COUNT(*) FROM projects WHERE length(CAST(name AS BLOB)) > 600);                         -- R-5
```

- R-2 measures UTF-8 bytes because `length()` on text stops at the first NUL; 200,000 UTF-16 units encode to at most 600,000 bytes, so every value the app wrote passes. A longer stored note is possible only in a file written outside the app; the editor truncates it on load (INV-1).
- R-4 rejects BLOB or mistyped values, which Room is not strict about and which would make a cursor read throw in every list or picker that shows the row. Junction values are covered by `foreign_key_check`.
- R-5: 200 UTF-16 units encode to at most 600 bytes. It protects the CursorWindow budget of [Size and performance](#size-and-performance).
- The checks are chosen by the staged copy's `user_version`: `RestoreChecks.forVersion(version: Int): List<String>` (`data.db`). Version 1 returns R-1 to R-5. A migration that renames, retypes or drops a checked column adds a list for its target version and never edits an older list; a version without its own list uses the newest list at or below it.

## Write rules

Every write method takes `val now = appTime.nowMillis()` once, inside its transaction, and passes it to every statement (DEC-20). Statements with an empty id list are skipped. Id lists longer than `SQL_CHUNK = 900` are split into chunks inside the same transaction, which keeps every statement below any `SQLITE_MAX_VARIABLE_NUMBER`.

### Transactions and results

Shape of every public write method of `NoteRepository` and `EntityRepository`:

```kotlin
suspend fun save(draft: NoteDraft): AppResult<SaveResult> {
    validate(draft)?.let { return AppResult.Err(it) }          // no database access (step 1 of Note save)
    return applicationScope.runDetached {                      // detached (architecture.md § Layering, rule 6)
        databaseHolder.withDb { db -> db.transaction { saveIn(db, draft) } }
    }
}
```

- Only the public method opens the transaction, and only through `internal suspend fun <T> AppDatabase.transaction(block: suspend () -> T): T` (`data/WriteTransaction.kt`, DEC-97) on the `AppDatabase` that `withDb` hands out; nothing else in production opens one (`TransactionRule`, [architecture.md § Boundary test](architecture.md#boundary-test)). It runs Room's `withTransaction` (in room-runtime 2.8.5 without a separate ktx artifact, confirmed by the T7 build) and keeps the block's own `SQLiteException` when SQLite has already rolled the transaction back by itself and Room's `ROLLBACK` then fails with "cannot rollback - no transaction is active". On the Poco F7 a full disk inside a transaction ends exactly that way (T22): with a plain `withTransaction` the `SQLiteFullException` was lost, `withDb` rethrew the plain `SQLiteException` as a programming error, and the app would have crashed instead of returning `Err(StorageFull)`. `transactionFailure` states the exact choice (DEC-97).
- Inside the block the code uses only DAOs obtained from that `db` and `internal` helpers that take `db` and `now` as parameters. It never calls a public repository method, `DatabaseHolder`, `withDb`, `applicationScope`, `runDetached`, `withContext`, `async` or `launch`: a public method detaches into `applicationScope` through `runDetached`, so a nested call would run without Room's transaction context, wait for the writer connection that the outer block holds, and deadlock.
- Entities created inside a save or a batch confirm use `internal suspend fun EntityRepository.Companion.insertNew(db: AppDatabase, persons: List<NewEntity>, projects: List<NewEntity>, now: Long): CreatedIds` (`data/NewEntities.kt`), with `data class CreatedIds(val persons: Map<String, Long>, val projects: Map<String, Long>)` (key → new id; `toString()` prints the two sizes only; package `data`). It is an extension on the empty `companion object` of `EntityRepository`, so the call reads `EntityRepository.insertNew(db, …)` and needs no repository instance: it is stateless, takes `db` and `now`, and `DefaultNoteRepository` calls it without depending on an `EntityRepository`. It stores the trimmed names (INV-9), inserts each entity with `created_at = updated_at = now` and runs in the caller's transaction; it `require`s every name to be non-blank and at most `MAX_NAME_CHARS`, because the caller has already validated them.
- The pre-write check of new entities is `internal fun newEntityError(persons: List<NewEntity>, projects: List<NewEntity>): AppError?` in the same file, called by `save` and `associate` before they detach (step 1 of [Note save](#note-save)). It returns `InvalidInput(BlankName)` when a name is blank after `trim()`, else null, and throws `IllegalArgumentException` for a name longer than `MAX_NAME_CHARS` and for a `NewEntity.key` used twice within `persons` or within `projects` (a programming error: `CreatedIds` maps each key to one new id, so the entity behind the duplicate would be created but never linked, DEC-33). The same key in `persons` and in `projects` is allowed: the two maps are separate.
- The shared write frame is `internal suspend fun <T> DatabaseHolder.writeTransaction(applicationScope, appTime, block: suspend (db: AppDatabase, now: Long) -> T): AppResult<T>` (`data/WriteTransaction.kt`): `applicationScope.runDetached { withDb { db -> db.transaction { block(db, appTime.nowMillis()) } } }`. Both repositories run every write through it, so `now` is taken once, inside the transaction (DEC-20).
- One-shot reads (`loadNote` and the delete impact with several statements in `AppDatabase.transaction`; `hasAnyData` with one statement) use `withDb` in the caller's scope, without detaching.
- Results: every public `suspend` method returns `AppResult<T>` ([architecture.md § Error model](architecture.md#error-model)), with `T` as named in this doc: `save` → `SaveResult`; `loadNote` → `NoteDetail?`; `hasAnyData` → `Boolean`; `selectIds` → `List<Long>`; `setChecked` → `Boolean`; `associate`, `setDate`, `deleteNotes` → `BatchResult`; `personDeleteImpact`, `projectDeleteImpact` → `DeleteImpact`; `deletePerson`, `deleteProject` → `EntityDeleteResult`. `withDb` maps the database exceptions ([db-lifecycle.md § One-shot access: withDb](db-lifecycle.md#one-shot-access-withdb)): a closed or replaced database is `Err(DatabaseUnavailable)`, `SQLiteFullException` is `Err(StorageFull)`, corruption is reported to `SessionGate` and becomes `Err(DatabaseUnavailable)`, `CancellationException` is rethrown. The repositories add only `Err(InvalidInput(InputRule.BlankName))` for a blank new name, from `save` and `associate`.
- A vanished row is a value, not an error: `SaveResult.NoteGone`, `setChecked` returning false, `EntityDeleteResult.deleted = false`, `loadNote` returning null. These repositories never return `Err(NotFound)`.
- A content-length, date-range or name-length violation, or a `NewEntity.key` used twice within one kind, is a programming error (`IllegalArgumentException`) thrown before any write: the UI prevents it. Observed `Flow`s (`observeRows`, `observeMarkedDays`, `observeOptions`, `observe`) are not wrapped.

### updated_at matrix (DEC-21)

| Operation | `notes` columns written | `updated_at` | Other writes |
|---|---|---|---|
| Insert note | all; `created_at = now`; `checked_at = now` if created checked, else NULL | `now` | links; new persons/projects |
| Editor save with an actual change (content, date, or any link added or removed) | `content`, `content_fold`, `date` | `now` | link diff |
| Editor save without a change | none | unchanged | none |
| Unlink from the editor's icon menu | none directly: a draft edit, written by the next save | `now`, through that save | link diff |
| Check | `checked = 1`, `checked_at = now` | unchanged | none |
| Uncheck | `checked = 0`, `checked_at = NULL` | `now` | none |
| Batch associate | none besides `updated_at` | `now` for notes that gained ≥ 1 link | links; new entities |
| Batch set date | `date` where it actually changes | `now` for those rows only | none |
| Note delete, batch delete | row deleted | n/a | junction rows deleted first |
| Person/project delete | none | unchanged (DEC-34) | junction rows, then the entity |
| Opening a note, any read | none | unchanged | none |
| Person/project created (inside a save or a batch confirm) | none | n/a | entity row with `created_at = updated_at = now`, never updated in v1 |

### Check and uncheck (DEC-22)

```sql
UPDATE notes SET checked = 1, checked_at = :now WHERE id = :id AND checked = 0;                    -- NoteDao.check
UPDATE notes SET checked = 0, checked_at = NULL, updated_at = :now WHERE id = :id AND checked = 1; -- NoteDao.uncheck
```

- `NoteRepository.setChecked(noteId, checked: Boolean): AppResult<Boolean>` takes the target state, never "toggle", so a double tap or a stale row cannot flip the note twice. It returns `Ok(true)` when a row changed.
- These two statements are the only writes to `checked`/`checked_at` of an existing note. In the editor of a saved note the checkbox calls them immediately (DEC-37).

### Note save

```kotlin
data class NewEntity(val key: String, val name: String) {         // key: draft-local UUID string
    override fun toString() = "NewEntity(key=$key)"
}
data class NoteDraft(                                               // held by EditorViewModel (DEC-37)
    val draftKey: String,                                           // random UUID, created with the draft; see the process-death rule
    val noteId: Long?, val content: String, val date: Long?,        // noteId is null until the first insert
    val checked: Boolean,                                           // read only when inserting
    val personIds: Set<Long>, val projectIds: Set<Long>,
    val newPersons: List<NewEntity>, val newProjects: List<NewEntity>,
) { override fun toString() = "NoteDraft(noteId=$noteId)" }
sealed interface SaveResult {
    data class Saved(val noteId: Long, val wrote: Boolean,
        val createdPersons: Map<String, Long>, val createdProjects: Map<String, Long>) : SaveResult {
        override fun toString() = "Saved(noteId=$noteId, wrote=$wrote, " +
            "createdPersons=${createdPersons.size}, createdProjects=${createdProjects.size})"
    }
    data object NoteGone : SaveResult                           // the note was deleted; nothing written
}
```

`NoteRepository.save(draft): AppResult<SaveResult>` follows [Transactions and results](#transactions-and-results). The steps are ordered so that nothing is written before the existence check:

1. Validate outside the transaction, before the write is detached: `content.length ≤ MAX_CONTENT_CHARS` (INV-1), `date` in range (INV-5), every new name trimmed by `TextWhitespace` is non-empty and at most `MAX_NAME_CHARS` (INV-9), and every `NewEntity.key` occurs once within `newPersons` and once within `newProjects` (`newEntityError`, [Transactions and results](#transactions-and-results)). A blank name returns `Err(InvalidInput(InputRule.BlankName))` (DEC-32); the other violations are `IllegalArgumentException`. Nothing is written in either case. The editor prevents all of them.
2. In the transaction, take `now`.
3. Existing note (`noteId != null`): `SELECT content, date FROM notes WHERE id = :id`. No row → return `NoteGone`; nothing has been written, so a draft that holds picker-created entities creates none (DEC-33). Never re-insert.
4. Drop `personIds`/`projectIds` that no longer exist (`SELECT id FROM persons WHERE id IN (:ids)`, same for projects), then `EntityRepository.insertNew(db, newPersons, newProjects, now)` (DEC-33). Desired links = surviving ids ∪ created ids.
5. New note (`noteId == null`): insert the row with `content_fold = SearchText.fold(content)` and the timestamps of the matrix, insert every desired link, return `Saved(wrote = true, …)`.
6. Existing note: it changed if `content` differs (exact `String` equality, so whitespace edits count), `date` differs, or the desired link sets differ from `SELECT person_id FROM note_persons WHERE note_id = :id` (same for projects); an entity created in step 4 is a new link, so it always counts. No change → return `Saved(noteId, wrote = false, emptyMap(), emptyMap())` without writing anything.
7. Change → `UPDATE notes SET content = :content, content_fold = :fold, date = :date, updated_at = :now WHERE id = :id`, then `DELETE FROM note_persons WHERE note_id = :id AND person_id IN (:removed)` and `INSERT OR IGNORE` of the added pairs; the same for projects. Return `Saved(noteId, wrote = true, createdPersons, createdProjects)`.

Caller rules:
- `EditorViewModel` decides whether a new draft is saved at all, by the rules of [ui.md § 6.8](ui.md#68-saving-as-the-user-experiences-it) (changed from its initial value with the prefill as part of that value, and not blank; or the user tapped Save).
- Saves of one draft never overlap (one `Mutex` in `EditorViewModel`). After `Saved`, the ViewModel stores `noteId` and moves `createdPersons`/`createdProjects` from the `new*` lists into the id sets before the next save, so Back after `ON_STOP` never inserts the note or an entity twice within one process.
- Across process death the saved state can predate `Saved`: Android captures it right after `onStop`, while the `ON_STOP` save in `applicationScope` may still be running, and a restored draft would then insert the note and its new entities a second time (no deduplication exists, §1). `NoteDraft.draftKey`, a UUID kept in the `SavedStateHandle`, closes the gap. After every `Saved` of a first insert, `EditorViewModel` stores `draftKey → (noteId, createdPersons, createdProjects)` through `SettingsStore` and only then updates its `SavedStateHandle`; a ViewModel restored with `noteId == null` looks its `draftKey` up before its first save and, on a hit, adopts the stored ids exactly as after `Saved`. The record is dropped after the draft's final save and on a data reset. The records are stored as a JSON array in the string preference `nav.draft_inserts` (encoded with `org.json`, at most 8 records); text that does not parse reads as no records instead of failing the editor, which only reopens the window accepted below (DEC-37; encoding: [architecture.md § SettingsStore](architecture.md#settingsstore)). Accepted residual risk: the process dies between the database commit and the end of the `SettingsStore` write. The atomic alternative, a unique nullable `notes.insert_key` column checked inside the save transaction, changes the schema and needs a DEC-30 amendment first. The `SettingsStore` member is part of [architecture.md § Dependency injection](architecture.md#dependency-injection); the lifecycle of the record is part of [ui.md § 6.8](ui.md#68-saving-as-the-user-experiences-it).
- After `deleteNotes` succeeds for the open note, the editor discards its draft without saving ([ui.md § 6.12](ui.md#612-delete-note)); `NoteGone` is the safety net for a save that races the delete.
- A save never writes `checked`/`checked_at` of an existing note, so editing a checked note keeps it checked (DEC-22).

## Read model

### Rows

```kotlin
// model
data class EntityRef(val id: Long, val name: String) { override fun toString() = "EntityRef(id=$id)" }
data class NoteRow(
    val id: Long,
    val preview: String,             // up to 300 code points from the first non-blank character (DEC-24); UI shows "(No data)" if blank (DEC-25)
    val date: Long?,                 // epoch day; Overdue is derived in the UI (DEC-26)
    val checked: Boolean, val checkedAt: Long?, val createdAt: Long, val updatedAt: Long,
    val persons: List<EntityRef>, val projects: List<EntityRef>,   // Collator on name, then id: the order of the pickers (DEC-32) applied to chips
) {
    val orphan: Boolean get() = persons.isEmpty() && projects.isEmpty()     // DEC-27
    override fun toString() = "NoteRow(id=$id)"
}
data class NoteDetail(                                                      // the editor's load result
    val id: Long, val content: String, val date: Long?, val checked: Boolean, val checkedAt: Long?,
    val createdAt: Long, val updatedAt: Long,
    val persons: List<EntityRef>, val projects: List<EntityRef>,           // sorted like row links
) { override fun toString() = "NoteDetail(id=$id)" }
data class EntityOption(val id: Long, val name: String, val createdAt: Long, val noteCount: Int, val coLinked: Boolean) {
    override fun toString() = "EntityOption(id=$id)"
}
data class BatchResult(val selected: Int, val changed: Int) {
    override fun toString() = "BatchResult(selected=$selected, changed=$changed)"
}
data class DeleteImpact(val n: Int, val m: Int) { override fun toString() = "DeleteImpact(n=$n, m=$m)" }
data class EntityDeleteResult(val deleted: Boolean, val n: Int, val m: Int) {
    override fun toString() = "EntityDeleteResult(deleted=$deleted, n=$n, m=$m)"
}

// data.db: Room result rows (snake_case result columns are mapped with @ColumnInfo)
data class NoteRowRaw(
    val id: Long, val preview: String, val date: Long?, val checked: Boolean,
    @ColumnInfo(name = "checked_at") val checkedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "persons_json") val personsJson: String,
    @ColumnInfo(name = "projects_json") val projectsJson: String,
) { override fun toString() = "NoteRowRaw(id=$id)" }
data class NoteDetailRow(                                                   // the seven columns of loadNote's first SELECT
    val id: Long, val content: String, val date: Long?, val checked: Boolean,
    @ColumnInfo(name = "checked_at") val checkedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) { override fun toString() = "NoteDetailRow(id=$id)" }
data class SaveProbe(val content: String, val date: Long?) { override fun toString() = "SaveProbe" }
data class EntityOptionRow(val id: Long, val name: String, val createdAt: Long, val noteCount: Int, val coLinked: Boolean) {
    override fun toString() = "EntityOptionRow(id=$id)"                    // filled through the SQL aliases createdAt, noteCount, coLinked
}
```

`NoteRepository` maps `NoteRowRaw` to `NoteRow`, `NoteDetailRow` plus the two link lists to `NoteDetail`, and `EntityOptionRow` to `EntityOption` (`EntityRepository`).

- Association names come from two correlated subqueries per row (`ROW_COLUMNS` below). Each returns a JSON array of `[id, name]` pairs, or `[]` without links, parsed with `org.json.JSONArray` (skip parsing for `[]`).
- JSON instead of `group_concat` with a separator: names are free text (DEC-32 only trims), so any separator can occur inside a name, while `json_group_array` escapes every character. Ids travel with names because duplicate names are allowed (§1): chips, the omission of the primary entity in Individual/Project rows ([ui.md § 3.2](ui.md#32-perspectivespec)) and stale checks need ids.
- Sorting happens in Kotlin because the SQLCipher build has no ICU (DEC-08) and DEC-32 requires `Collator` order, then `id`. Use one `java.text.Collator.getInstance()` per mapping pass (instances are not thread-safe; the default locale is read on each pass), on the background dispatcher from [architecture.md § Threading](architecture.md#threading).
- Verify at setup: the JSON functions exist in the shipped SQLCipher build and in Room's compile-time query verifier; if only the verifier lacks them, mark `observeRows`, the only query that selects `ROW_COLUMNS`, with `@SkipQueryVerification`. JVM tests of the parser need `testImplementation("org.json:json")`, because Android's `org.json` is a stub on the JVM.
- The preview starts at the first non-whitespace character, so it is blank exactly when the content is blank (DEC-25) and the row title of DEC-39 (first non-blank line) is always inside it. `WHITESPACE_CHARS` lists the code points of `model.TextWhitespace` (DEC-24), the app's one whitespace rule for user text: the code points for which `Char.isWhitespace()` is true on JDK 21. The rule is an explicit set because `Char.isWhitespace()` depends on the runtime: on ART (the Poco F7, T22) it also counts U+180E MONGOLIAN VOWEL SEPARATOR, which Unicode 6.3 moved to the format characters (Cf) and which JDK 21 does not count. With Kotlin's own rule, the SQL preview of a note made of U+180E characters followed by text started with U+180E and Kotlin called it blank, so the row would have shown "(No data)" for a note with content. The title, the blank check and every other rule on user text therefore use `TextWhitespace`, never Kotlin's `trim()`, `isBlank()` or `Char.isWhitespace()` without a predicate (`WhitespaceRule`, [architecture.md § Boundary test](architecture.md#boundary-test)). `WhitespaceCharsTest` (JVM) asserts that this list, `TextWhitespace.codePoints` and an enumeration of `0..0xFFFF` by the rule are equal and that the rule is JDK 21's `Char.isWhitespace()`; `NoteRowTest` checks on the device that the SQL preview follows the rule wherever the runtime's `Char.isWhitespace()` differs from it. `substr` counts code points, so the preview has at most 300 code points and at most 600 UTF-16 units.

### Perspective query

```kotlin
const val PERSON_PRED = """(:personId IS NULL OR EXISTS (SELECT 1 FROM note_persons x
    WHERE x.note_id = n.id AND x.person_id = :personId))"""
const val PROJECT_PRED = """(:projectId IS NULL OR EXISTS (SELECT 1 FROM note_projects y
    WHERE y.note_id = n.id AND y.project_id = :projectId))"""
const val ORPHAN_PRED = """(NOT EXISTS (SELECT 1 FROM note_persons a WHERE a.note_id = n.id)
    AND NOT EXISTS (SELECT 1 FROM note_projects b WHERE b.note_id = n.id))"""
const val SEARCH_PRED = """(:searchPattern IS NULL
    OR n.content_fold LIKE '%' || :searchPattern || '%' ESCAPE '\')"""         // DEC-29
const val PERSPECTIVE_WHERE = """WHERE $PERSON_PRED AND $PROJECT_PRED
    AND (:orphanOnly = 0 OR $ORPHAN_PRED) AND (:undatedOnly = 0 OR n.date IS NULL)
    AND (:fromDay IS NULL OR n.date >= :fromDay) AND (:toDay IS NULL OR n.date <= :toDay)
    AND $SEARCH_PRED"""
/** DEC-23: the only ORDER BY for note lists. Every list query aliases notes as n. */
const val CANONICAL_ORDER = """ORDER BY n.checked ASC,
    CASE WHEN n.checked = 0 THEN n.updated_at END DESC,
    CASE WHEN n.checked = 0 THEN n.created_at END DESC,
    CASE WHEN n.checked = 1 THEN n.checked_at END ASC,
    n.id DESC"""
/** The code points of TextWhitespace (DEC-24); keeps the preview blank exactly when the content is blank. */
const val WHITESPACE_CHARS = """char(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194, 8195,
    8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)"""
const val ROW_COLUMNS = """n.id, substr(ltrim(n.content, $WHITESPACE_CHARS), 1, 300) AS preview, n.date, n.checked,
    n.checked_at, n.created_at, n.updated_at,
    (SELECT json_group_array(json_array(p.id, p.name)) FROM note_persons np
        JOIN persons p ON p.id = np.person_id WHERE np.note_id = n.id) AS persons_json,
    (SELECT json_group_array(json_array(j.id, j.name)) FROM note_projects nj
        JOIN projects j ON j.id = nj.project_id WHERE nj.note_id = n.id) AS projects_json"""

// NoteDao
@Query("SELECT $ROW_COLUMNS FROM notes n $PERSPECTIVE_WHERE $CANONICAL_ORDER")
fun observeRows(personId: Long?, projectId: Long?, orphanOnly: Boolean, undatedOnly: Boolean,
    fromDay: Long?, toDay: Long?, searchPattern: String?): Flow<List<NoteRowRaw>>
@Query("SELECT n.id FROM notes n $PERSPECTIVE_WHERE")                         // Select all (DEC-36)
suspend fun selectIds(personId: Long?, projectId: Long?, orphanOnly: Boolean, undatedOnly: Boolean,
    fromDay: Long?, toDay: Long?, searchPattern: String?): List<Long>
@Query("""SELECT DISTINCT n.date FROM notes n WHERE n.date BETWEEN :fromDay AND :toDay
    AND $PERSON_PRED AND $PROJECT_PRED AND $SEARCH_PRED""")
fun observeMarkedDays(fromDay: Long, toDay: Long, personId: Long?, projectId: Long?,
    searchPattern: String?): Flow<List<Long>>
```

- `observeRows` serves every list. The §9 Key Queries map to its arguments: Individual = `personId`, Project = `projectId`, Date = `fromDay`/`toDay`, Orphan = `orphanOnly`. All terms combine with AND (DEC-28). A date range excludes undated notes because comparisons with NULL are false.
- `selectIds` uses the same WHERE, so Select all covers every row of the filtered list, checked notes included (DEC-36).
- `observeMarkedDays` (DEC-53): `fromDay`/`toDay` are the first and last day of the month shown by `MonthGrid` ([ui.md § 4.4.1](ui.md#441-monthgrid)); the other arguments come from the Date perspective's `NoteQuery`. The bounds are non-null so SQLite can use `idx_notes_date`. Checked notes mark days too.
- `NoteRepository` exposes `observeRows(query: NoteQuery)`, `selectIds(query: NoteQuery)` and `observeMarkedDays(query: NoteQuery, monthFirstDay: Long, monthLastDay: Long): Flow<Set<Long>>`, which spread the `NoteQuery` fields into these arguments. `observeMarkedDays` emits the marked epoch days as a set and skips a result equal to the previous one (`distinctUntilChanged`); the Date perspective keys it on its query without the selection's `fromDay`/`toDay` (item 6 of § Perspective to NoteQuery mapping), so a day tap or a new range does not re-run it. `observeRows` and `selectIds` exist since the batch code (Select all, the Orphan Flow of I-17); `observeMarkedDays` is added with the calendar (`MonthGrid`), its first caller, while the DAO query already exists.

### Editor loads, pickers and existence probes

```sql
-- NoteRepository.loadNote(id): AppResult<NoteDetail?>  (the three statements run in one transaction)
SELECT id, content, date, checked, checked_at, created_at, updated_at FROM notes WHERE id = :id;
SELECT p.id, p.name FROM note_persons x JOIN persons p ON p.id = x.person_id WHERE x.note_id = :id;
SELECT j.id, j.name FROM note_projects y JOIN projects j ON j.id = y.project_id WHERE y.note_id = :id;
-- PersonDao.observeOptions(coLinkedTo: Long?): Flow<List<EntityOptionRow>>   (coLinkedTo = a project id)
SELECT p.id, p.name, p.created_at AS createdAt,
       (SELECT COUNT(*) FROM note_persons c WHERE c.person_id = p.id) AS noteCount,
       (:coLinkedTo IS NOT NULL AND EXISTS (SELECT 1 FROM note_projects y
            JOIN note_persons x ON x.note_id = y.note_id
           WHERE y.project_id = :coLinkedTo AND x.person_id = p.id)) AS coLinked
  FROM persons p;
-- ProjectDao.observeOptions(coLinkedTo: Long?): Flow<List<EntityOptionRow>>  (coLinkedTo = a person id)
SELECT j.id, j.name, j.created_at AS createdAt,
       (SELECT COUNT(*) FROM note_projects c WHERE c.project_id = j.id) AS noteCount,
       (:coLinkedTo IS NOT NULL AND EXISTS (SELECT 1 FROM note_persons x
            JOIN note_projects y ON y.note_id = x.note_id
           WHERE x.person_id = :coLinkedTo AND y.project_id = j.id)) AS coLinked
  FROM projects j;
-- PersonDao.observe(id) / ProjectDao.observe(id): Flow<EntityRef?>, the existence probes
SELECT id, name FROM persons WHERE id = :id;
SELECT id, name FROM projects WHERE id = :id;
-- NoteRepository.hasAnyData(): AppResult<Boolean>, the empty-database probe of the startup-password backup offer (DEC-73)
SELECT EXISTS (SELECT 1 FROM notes) OR EXISTS (SELECT 1 FROM persons) OR EXISTS (SELECT 1 FROM projects);
```

- `loadNote` returns the note's columns plus `persons`/`projects` sorted like row links, or `Ok(null)` when the note is gone (the editor then closes, [ui.md § 6.8](ui.md#68-saving-as-the-user-experiences-it)). `SELECT *` on `notes` is forbidden, and `content_fold` is never selected; only `SEARCH_PRED` reads it. Full `content` is selected only by `loadNote` and by the change probe of [Note save](#note-save) step 3.
- Every picker uses `observeOptions` and lists every entity, zero-note ones included (DEC-33). `coLinked` is computed only for the secondary Person filter of the Project perspective (`coLinkedTo` = the primary project) and the secondary Project filter of the Individual perspective (`coLinkedTo` = the primary person); every other picker passes null.
- `EntityRepository.observeOptions(kind: EntityKind, coLinkedTo: Long?)` sorts in Kotlin: co-linked entities first, then `Collator` on name, then `id` (DEC-32); the picker's sections are in [ui.md § 7.4](ui.md#74-ordering-and-sections). `noteCount` and `createdAt` feed "N notes · added <date>" ([ui.md § 7.3](ui.md#73-rows-and-duplicate-display)). The delete picker of DEC-34 puts the note's draft links first instead. Name search inside a picker and the create-row wording are in [ui.md § 7.2](ui.md#72-layout) and [§ 7.6](ui.md#76-multi-select-variant-and-the-create-row); both use `SearchText.fold`.
- `EntityRepository.observe(kind: EntityKind, id: Long): Flow<EntityRef?>` emits null once the row is gone ([Reactivity](#reactivity)).

### DAO surface

DAOs declare no `@Transaction` methods; repositories open transactions only with `AppDatabase.transaction` ([Transactions and results](#transactions-and-results), DEC-97).

| DAO | Methods |
|---|---|
| `NoteDao` | `insertNote(NoteEntity): Long` · `noteDetail(id): NoteDetailRow?` · `linkedPersons(noteId): List<EntityRef>` · `linkedProjects(noteId): List<EntityRef>` · `saveProbe(id): SaveProbe?` · `personIdsOf(noteId): List<Long>` · `projectIdsOf(noteId): List<Long>` · `updateNote(id, content, fold, date, now): Int` · `insertPersonLinks(List<NotePersonCrossRef>): List<Long>` and `insertProjectLinks(List<NoteProjectCrossRef>): List<Long>`, both `@Insert(onConflict = IGNORE)` · `unlinkPersons(noteId, personIds): Int` · `unlinkProjects(noteId, projectIds): Int` · `check(id, now): Int` · `uncheck(id, now): Int` · `existingNoteIds(ids): List<Long>` · `bump(ids, now): Int` · `setDate(ids, date, now): Int` · `deletePersonLinksOfNotes(ids): Int` · `deleteProjectLinksOfNotes(ids): Int` · `deleteNotes(ids): Int` · `hasAnyData(): Boolean` (the probe of [Editor loads, pickers and existence probes](#editor-loads-pickers-and-existence-probes); it reads all three entity tables) · `observeRows(…)` · `selectIds(…)` · `observeMarkedDays(…)` |
| `PersonDao` | `insert(PersonEntity): Long` · `existingIds(ids): List<Long>` · `exists(id): Int` · `impactN(id): Int` · `impactM(id): Int` · `deleteLinks(id): Int` · `delete(id): Int` · `observeOptions(coLinkedTo): Flow<List<EntityOptionRow>>` · `observe(id): Flow<EntityRef?>` |
| `ProjectDao` | the same methods over `projects` and `note_projects` |
| `EntityDao` | not a Room DAO: the unannotated supertype that `PersonDao` and `ProjectDao` both extend. It declares the shared shape (`observeOptions`, `observe`, `existingIds`, `exists`, `impactN`, `impactM`, `deleteLinks`, `delete`); each sub-interface overrides every method with its own `@Query` SQL, so Room generates the code per DAO. `insert` stays on the sub-interfaces, because its parameter type differs |

- `internal fun AppDatabase.entities(kind: EntityKind): EntityDao` (`data/EntityDaos.kt`) returns `personDao()` or `projectDao()`; `EntityRepository` and the entity-id helpers use it to serve both kinds through one code path. Like every DAO it is obtained per call inside `withDb` or `observe` and never stored (DEC-81). `EntityDao` is an internal DAO shape, not a component name of decisions.md.

## Perspective to NoteQuery mapping

```kotlin
// model
enum class Perspective { All, Individual, Project, Date, Orphan }          // DEC-50 internal names
sealed interface DateFilter {
    data object Any : DateFilter
    data object Undated : DateFilter
    data class Range(val fromDay: Long?, val toDay: Long?) : DateFilter {   // inclusive, either end open
        override fun toString() = "Range(fromDay=$fromDay, toDay=$toDay)"
    }
}
data class NoteFilter(
    val perspective: Perspective,
    val primaryId: Long? = null,                   // Individual: person id; Project: project id
    val primaryDays: DateFilter.Range? = null,     // Date: selected day (fromDay == toDay) or range; both ends set
    val personId: Long? = null, val projectId: Long? = null,       // secondary filters (DEC-28)
    val date: DateFilter = DateFilter.Any, val search: String = "",
) {
    override fun toString() = "NoteFilter(perspective=$perspective, primaryId=$primaryId, primaryDays=$primaryDays, " +
        "personId=$personId, projectId=$projectId, date=$date, search=<${search.length} chars>)"
}
data class NoteQuery(val personId: Long?, val projectId: Long?, val orphanOnly: Boolean,
    val undatedOnly: Boolean, val fromDay: Long?, val toDay: Long?, val searchPattern: String?) {
    override fun toString() = "NoteQuery(personId=$personId, projectId=$projectId, orphanOnly=$orphanOnly, " +
        "undatedOnly=$undatedOnly, fromDay=$fromDay, toDay=$toDay, searchPattern=<${searchPattern?.length ?: 0} chars>)"
}
fun NoteFilter.toQuery(): NoteQuery?
```

| Perspective | Primary folds into | `personId` | `projectId` | `date` | `search` |
|---|---|---|---|---|---|
| All | nothing | kept | kept | kept | kept |
| Individual | `personId = primaryId` | dropped (the primary uses the slot) | kept | kept | kept |
| Project | `projectId = primaryId` | kept | dropped (the primary uses the slot) | kept | kept |
| Date | `fromDay`/`toDay` from `primaryDays` | kept | kept | dropped | kept |
| Orphan | `orphanOnly = true` | dropped | dropped | kept | kept |

Contract of `toQuery()`:
1. Pure: no I/O, no clock, no default locale; equal inputs give equal outputs. It is JVM-tested against this table (I-15 in [testing.md § 4](testing.md#4-invariant-catalog)). `NoteListViewModel` applies `distinctUntilChanged()` to its `NoteQuery` stream, so state changes that leave the query equal never re-run SQL.
2. It returns null when Individual or Project has no `primaryId`. The screen then shows the entity picker body (DEC-52) and no list query runs. Date always has a primary: `NoteListViewModel` starts it as `Range(today, today)` from `AppTime.today()` (DEC-53, [ui.md § 4.4](ui.md#44-date-calendar)), and `toQuery()` applies `require` that `primaryDays` is non-null with both ends non-null.
3. Fields marked "dropped" are ignored, never combined (§2, DEC-28). `PerspectiveSpec` ([ui.md § 3.2](ui.md#32-perspectivespec)) offers only the kept dimensions; `Undated` is never offered in Date.
4. A `Range` (secondary `date` or Date `primaryDays`) maps `fromDay ?: EpochDays.MIN` and `toDay ?: EpochDays.MAX`, so an open end becomes a bound that every dated note satisfies and undated notes stay excluded (DEC-28), also for `Range(null, null)`; a reversed range, which the pickers never produce, is swapped. `Any` gives no bounds; `Undated` gives `undatedOnly = true` and no bounds.
5. `search` becomes `SearchText.toLikeTerm(search)`; blank input gives null.
6. Calendar marks use the Date perspective's query: `NoteRepository.observeMarkedDays(query, monthFirstDay, monthLastDay)` passes the query's `personId`, `projectId` and `searchPattern` and replaces `fromDay`/`toDay` with the displayed month's bounds.

## Search

```kotlin
// model
object SearchText {
    const val MAX_TERM_CHARS = 1_000
    fun fold(text: String): String = text.lowercase()                 // = lowercase(Locale.ROOT), the only folding rule (DEC-29)
    fun toLikeTerm(input: String): String? {                          // bind value for :searchPattern
        val folded = fold(TextWhitespace.trim(input))                // the app's whitespace rule (DEC-24)
        var end = minOf(folded.length, MAX_TERM_CHARS)
        if (end < folded.length && folded[end - 1].isHighSurrogate()) end--   // never cut a surrogate pair
        val term = folded.substring(0, end)
        if (term.isEmpty()) return null                               // blank input: no search filter
        return buildString(term.length + 8) {
            for (c in term) { if (c == '\\' || c == '%' || c == '_') append('\\'); append(c) }
        }
    }
}
```

- Both sides are folded in Kotlin, and SQL never calls `lower()` or `upper()`: SQLite without ICU folds ASCII only (DEC-08, DEC-29). `lowercase()` without argument is `toLowerCase(Locale.ROOT)` on the JVM, so `model` needs no `java.util` import. "Ä" finds "ä". There is no accent folding ("a" does not find "ä") and no full case folding ("strasse" does not find "Straße"); Greek capital Σ folds to final ς at the end of a word and to σ elsewhere (Final_Sigma), so an all-caps query that ends in Σ does not match the same letters inside a longer word. All are accepted v1 limits.
- The escape character is `\`, and `SEARCH_PRED` adds the surrounding `%`. In a Kotlin raw string write `ESCAPE '\'`; in a regular string literal write `ESCAPE '\\'`.
- `MAX_TERM_CHARS` keeps the pattern (at most 2,000 characters after escaping, 4 UTF-8 bytes each, plus two `%`) far below the 50,000-byte `SQLITE_MAX_LIKE_PATTERN_LENGTH` of the SQLCipher build. The cut applies to the folded text and is a safety net: a query longer than the limit silently matches on its first `MAX_TERM_CHARS` characters (I-27 in [testing.md § 4](testing.md#4-invariant-catalog)).
- Search covers note content only, never names (DEC-29). Checked notes stay in the results, in canonical order (§8). The search field and its debounce are in [ui.md § 3.3](ui.md#33-search-bar).

## Deletes

### Notes

```sql
-- NoteRepository.deleteNotes(ids): AppResult<BatchResult>  (one transaction, chunks of ≤ 900 ids)
DELETE FROM note_persons  WHERE note_id IN (:chunk);
DELETE FROM note_projects WHERE note_id IN (:chunk);
DELETE FROM notes         WHERE id IN (:chunk);
```

- A single delete (DEC-35) is `deleteNotes(listOf(id))` after the confirmation dialog ([ui.md § 8.2](ui.md#82-delete-note-dec-35-q6)). Deleting a never-saved draft writes nothing. The result is `BatchResult(selected = ids.size, changed = <notes rows deleted>)`; a single delete reads `changed`.
- Junction rows are deleted explicitly although the foreign keys cascade: whether Room's invalidation tracker sees cascaded deletes is undocumented, and picker note counts read only the junction tables.

### Persons and projects (DEC-34)

```sql
-- N: notes linked to the person
SELECT COUNT(*) FROM note_persons WHERE person_id = :id;
-- M (a subset of N): linked notes that have no other person and no project
SELECT COUNT(*) FROM note_persons x WHERE x.person_id = :id
   AND NOT EXISTS (SELECT 1 FROM note_persons o WHERE o.note_id = x.note_id AND o.person_id <> :id)
   AND NOT EXISTS (SELECT 1 FROM note_projects y WHERE y.note_id = x.note_id);
```

For a project, the mirror (`ProjectDao.impactM`):

```sql
SELECT COUNT(*) FROM note_projects y WHERE y.project_id = :id
   AND NOT EXISTS (SELECT 1 FROM note_projects o WHERE o.note_id = y.note_id AND o.project_id <> :id)
   AND NOT EXISTS (SELECT 1 FROM note_persons x WHERE x.note_id = y.note_id);
```

and `impactN` is `SELECT COUNT(*) FROM note_projects WHERE project_id = :id`.

- `EntityRepository.personDeleteImpact(id): AppResult<DeleteImpact>` reads both counts in one transaction for the dialog.
- `EntityRepository.deletePerson(id): AppResult<EntityDeleteResult>` runs one transaction:
  1. If `SELECT COUNT(*) FROM persons WHERE id = :id` returns 0, return `EntityDeleteResult(deleted = false, n = 0, m = 0)`.
  2. Recompute N and M with the statements above (the dialog may be stale).
  3. `DELETE FROM note_persons WHERE person_id = :id`, then `DELETE FROM persons WHERE id = :id`.
  4. Return `EntityDeleteResult(deleted = true, n, m)`. No `notes` row is written, so no `updated_at` changes (DEC-21).
- `projectDeleteImpact` and `deleteProject` mirror these.
- Counts cover committed rows only. Before it opens the impact dialog, `EditorViewModel` saves its draft if DEC-37 would save it on leave, so the open note's link edits are counted ([ui.md § 6.11](ui.md#611-delete-person-or-project)).
- Every reference to the deleted entity resets through the existence probes ([Reactivity](#reactivity)).

## Batch operations

Each action is one transaction with one `now` (§9, DEC-36) and works in chunks of ≤ 900 ids. It first drops note ids that no longer exist (`NoteDao.existingNoteIds`, `SELECT id FROM notes WHERE id IN (:chunk)`), because `INSERT OR IGNORE` still aborts on a foreign-key error. Before calling, `NoteListViewModel` prunes the selection to the ids of the latest list emission ([ui.md § 5.3](ui.md#53-actions)). Every action returns `BatchResult(selected: Int, changed: Int)`: `selected` is the number of distinct ids passed in, which the snackbar shows (DEC-36), and `changed` serves tests and counts-only logging (DEC-70).

Associate: `NoteRepository.associate(noteIds, personIds, projectIds, newPersons, newProjects): AppResult<BatchResult>`. Before the transaction, and before the write is detached, `newEntityError(newPersons, newProjects)` validates the new entities exactly as step 1 of [Note save](#note-save) does: a blank name returns `Err(InvalidInput(InputRule.BlankName))` and writes nothing; an over-long name or a key used twice within one kind throws `IllegalArgumentException`.
1. Drop vanished note ids first. If none survives, return `BatchResult(selected, changed = 0)` before creating any entity (DEC-33).
2. `EntityRepository.insertNew(db, newPersons, newProjects, now)` (created on confirm, DEC-33, DEC-36), add the created ids to the chosen sets, and drop chosen ids that no longer exist.
3. Insert every (note, person) and (note, project) pair with `NoteDao.insertPersonLinks` / `insertProjectLinks` (`@Insert(onConflict = OnConflictStrategy.IGNORE)`), returning `List<Long>` aligned with the input list. Room returns `-1` for each pair that already existed (a test pins this).
4. `gained` = distinct note ids of the pairs whose returned rowid is not `-1`; run `UPDATE notes SET updated_at = :now WHERE id IN (:chunk)` (`NoteDao.bump`) over `gained` (DEC-21).
5. Return `BatchResult(selected = noteIds.size, changed = gained.size)`.

Set date: `NoteRepository.setDate(noteIds, date: Long?): AppResult<BatchResult>`, where `null` means "Remove date". `date` must satisfy INV-5 (`require`). `IS NOT` compares NULL as a value, so only rows whose date actually changes are written and bumped; `changed` is the sum of affected rows.

```sql
UPDATE notes SET date = :date, updated_at = :now WHERE id IN (:chunk) AND date IS NOT :date;
```

Delete: `deleteNotes(ids)` from [Deletes](#deletes), after the confirmation dialog (DEC-35); `changed` is the number of rows deleted.

## Reactivity

- A Room `Flow` re-runs whenever a table read by its query is written, whether or not its result changed. Repositories build each list as `DatabaseHolder.observe { db -> db.noteDao().observeRows(…) }` ([db-lifecycle.md § Observation](db-lifecycle.md#observation)) → `distinctUntilChanged()` → `map` (JSON parse, `Collator` sort) → `flowOn` the background dispatcher ([architecture.md § Threading](architecture.md#threading)), so an equal result is neither parsed nor sorted again and flows survive a database swap. `NoteRowRaw` is a data class, so `distinctUntilChanged()` compares by value.
- The row query reads all five tables, so creating or deleting an entity also refreshes lists. A note leaves the Orphan list as soon as the transaction that links it commits (§6).
- Every write that removes notes or entities deletes junction rows explicitly in the same transaction ([Deletes](#deletes)), so the junction tables are invalidated without relying on cascades.
- Contract relied on, provided by [db-lifecycle.md § Observation](db-lifecycle.md#observation): while the holder is not `Open` (closed, opening, draining, exclusive) `observe` emits nothing and downstream keeps its last value; after a reopen it subscribes to the new instance and emits current rows. It never emits a placeholder, an empty list or null because of a close, so an existence probe may treat null as "the row is gone".
- `AUTOINCREMENT` (INV-11) guarantees that a stale id never matches a newer row only within one database file. After Restore, Start fresh or Erase, an old id can name a different row, so the existence probes are no protection there: [architecture.md § Data reset](architecture.md#data-reset) (`dataEpoch`) clears every holder of an id.

| Holder of a possibly stale id | Rule |
|---|---|
| Individual/Project primary, persisted in `SettingsStore` (DEC-28) | `NoteListViewModel` observes the existence probe; on null it clears the stored id and shows the picker (DEC-52). This also covers a stale id at cold start |
| Secondary person/project filter in any perspective | Same probe; on null the filter dimension is cleared (DEC-34) |
| Editor draft links | The ViewModel removes an entity it just deleted; `save` drops ids that vanished (step 4) |
| Editor note | `loadNote` returning `Ok(null)` closes the editor; `save` returning `NoteGone` writes nothing |
| Selection | Pruned to the ids of each new list emission; repositories skip vanished ids |
| Any id after a restore, Start fresh or Erase | [architecture.md § Data reset](architecture.md#data-reset): navigation, `SettingsStore` remembered ids, `SavedStateHandle` filters, selections and drafts are dropped by `dataEpoch`; an `EditorViewModel` from before the reset never saves |

## Size and performance

- Lists read `preview`, never `content`. Only `loadNote` and the save probe read full content, and nothing selects `content_fold` (DEC-24). The 200,000-character cap bounds a value to 600 KB of UTF-8, well below the 2 MB `CursorWindow`.
- Row size: the preview is at most 300 code points (about 1.2 KB), and every linked entity adds its name (at most 200 UTF-16 units, about 650 bytes with the JSON framing and the id). A row therefore stays below the window until a note has several thousand links; v1 sets no cap on links per note. Observed on the Poco F7 (T22, `EngineSetupTest.contentAtTheCap_loadsWhole`): a note at the cap in three-byte UTF-8 (600 KB) loads whole through `noteDetail`, so sqlcipher-android's cursor window holds the largest value; the window size itself is not read.
- `content` and `content_fold` are the last columns, so the small columns used by `WHERE` and `ORDER BY` sit at the start of each record, before any overflow pages. A long note still costs a full read when it is in the result, because `substr` loads the whole value.
- The CASE-based ORDER BY sorts in a temporary B-tree in memory; no temporary file is written. Observed on the Poco F7 (T22, `EngineSetupTest`): the build's `TEMP_STORE` compile option together with `PRAGMA temp_store` keeps temporary data in memory, so a large sort never spills into a file.

| Query | Expected `EXPLAIN QUERY PLAN` |
|---|---|
| `observeRows` | `SCAN n` (the `:x IS NULL OR …` terms cannot use an index) and `USE TEMP B-TREE FOR ORDER BY`; correlated subqueries `SEARCH` the junction primary keys |
| `selectIds` | `SCAN n`, no temp B-tree (no ORDER BY); the `EXISTS` subqueries `SEARCH` the junction primary keys |
| `observeMarkedDays` | `SEARCH n USING INDEX idx_notes_date` |
| Impact counts, junction deletes by entity, picker counts, `coLinked` | `SEARCH` using `idx_note_persons_person` / `idx_note_projects_project` |
| Editor link loads, note deletes, orphan and JSON subqueries | `SEARCH` using `sqlite_autoindex_note_persons_1` / `sqlite_autoindex_note_projects_1` |
| (none) | `idx_notes_created`, `idx_notes_updated` and `idx_notes_checked_at` are kept for §9 (DEC-30); no query uses them |

- Budget: 10,000 notes (DEC-10). Timing targets are in [architecture.md § Performance budgets](architecture.md#performance-budgets); the seeded benchmark is P-1 in [testing.md § 8](testing.md#8-performance-tests). An instrumented test runs `EXPLAIN QUERY PLAN` for every query constant on the seeded database and asserts the table above; in particular, no alias of `note_persons` or `note_projects` is ever `SCAN`ned.
- If the benchmark misses its budget, two steps, in order:
  1. A larger `PRAGMA cache_size` on Room's connections (a page that misses the cache is decrypted again; the per-connection hook is in [db-lifecycle.md § Room builder](db-lifecycle.md#room-builder)).
  2. Per-perspective static queries instead of the single one, still only `const val` fragments, sharing `ROW_COLUMNS`, `CANONICAL_ORDER` and the remaining predicates and differing in the driving clause: Individual `FROM note_persons x JOIN notes n ON n.id = x.note_id WHERE x.person_id = :personId AND …` (uses `idx_note_persons_person`); Project the same over `note_projects`/`:projectId` (`idx_note_projects_project`); Date `FROM notes n WHERE n.date BETWEEN :fromDay AND :toDay AND …` (`idx_notes_date`). All and Orphan keep the generic query, and `selectIds` gets matching variants. `NoteQuery` and `toQuery()` do not change: `NoteRepository` picks the DAO method from the non-null fields. Each new method gets a row in the plan table above.
- Never fall back to SQL built at runtime.

## Schema evolution

- `AppDatabase.VERSION` starts at 1 (DEC-30). Any change to an entity, column, index, foreign key, default or the set of tables raises it by exactly 1 and ships a `Migration(n, n + 1)` in `AppDatabase.MIGRATIONS` or, for a purely additive change, an `@AutoMigration(from = n, to = n + 1)` in `@Database(autoMigrations = …)`, plus a `MigrationTestHelper` test (DEC-88).
- `exportSchema = true`, with the Room Gradle plugin set up per [architecture.md § AGP 9 build configuration](architecture.md#agp-9-build-configuration). The JSON of every version is committed under `app/schemas/`. While the app is unreleased, the exported JSON of version 1 is regenerated with the entities during development (the app has no installed base whose files it would have to match); the JSON of every released version is never edited again, and CI fails when it changes ([testing.md § 9](testing.md#9-ci-pipeline), job `schema`, `config/released-schema-versions.txt`; DEC-14).
- Never `fallbackToDestructiveMigration*` and never `allowDataLossOnRecovery` (DEC-88). Downgrades are unsupported: the raw verification of the open sequence reads `user_version` before Room is built and classifies a value above `AppDatabase.VERSION` as `NewerVersion` ([db-lifecycle.md § Open sequence](db-lifecycle.md#open-sequence), [§ Failure classification](db-lifecycle.md#failure-classification)); Room itself would throw on a downgrade, and no migration for it exists. Restore rejects such a backup the same way (check 9 of [db-lifecycle.md § Restore validation](db-lifecycle.md#restore-validation)).
- Migration code is raw SQL with explicit column lists. It never uses DAOs or entity classes (they describe the latest schema) and never reads the clock, zone, settings or keys, because the same migration also runs on restored backups. A new NOT NULL column gets `DEFAULT x` in the migration SQL and the identical `@ColumnInfo(defaultValue = "x")` on the entity field; Room compares defaults when it validates a migrated table, and `runMigrationsAndValidate(name, n + 1, true, migration)` proves they are equal. Verify at setup: default-value validation in Room 2.8.5.
- Foreign keys during a table rebuild: Room turns `PRAGMA foreign_keys` on in its generated `onOpen`, after migrations, and the pragma is a no-op inside the migration transaction. A rebuild therefore runs with enforcement off, and every rebuild migration first asserts `PRAGMA foreign_keys` returns 0 and throws otherwise; without that, `DROP TABLE notes` would cascade into both junction tables and delete every link. Never set `PRAGMA foreign_keys` inside a migration. Verify at setup: Room 2.8.5 runs migrations before its generated `onOpen`, on SQLCipher connections too.
- Rebuild procedure for a table `T`: `CREATE TABLE T_new (…)`; `INSERT INTO T_new (cols) SELECT cols FROM T`; read `old = (SELECT seq FROM sqlite_sequence WHERE name = 'T')`; `DROP TABLE T`; `ALTER TABLE T_new RENAME TO T`; `DELETE FROM sqlite_sequence WHERE name = 'T'`; `INSERT INTO sqlite_sequence(name, seq) VALUES ('T', max(old, (SELECT IFNULL(MAX(id), 0) FROM T)))` so ids are never reused (INV-11); recreate every index under its explicit name; run `PRAGMA foreign_key_check` and throw if it returns a row (Room runs none after a manual migration). A migration test checks that junction row counts and `sqlite_sequence` survive a rebuild.
- A change to the folding rule of DEC-29 is a migration that rewrites `content_fold` for every row in Kotlin.
- Open before the first migration ships (DEC-97): Room runs migrations inside the SQLCipher open helper's own transaction, which `AppDatabase.transaction` does not reach. A full disk during a migration makes SQLite roll that transaction back by itself, and the helper's failing `ROLLBACK` would replace the `SQLiteFullException` with a plain `SQLiteException`: a Programming failure at an open, `InvalidBackup(Damaged)` at `MIGRATE_INPUT`. The release that adds schema version 2 decides the remedy and tests it.
- `application_id` never changes. Room's identity hash changes with every schema change, and restore compares it when versions are equal (DEC-84), so two released builds must never ship different schemas under one version number (the `schema` job enforces it for every version listed in `config/released-schema-versions.txt`).
- Restores of older backups run these migrations on the staged copy before the export into the device's key configuration (DEC-84). The restore builds a temporary Room instance with the production builder ([db-lifecycle.md § Restore](db-lifecycle.md#restore), step 7), never calls `MIGRATIONS` directly, so auto-migrations run too and foreign keys stay off until the migrations finish. The R-checks of [Restore checks](#restore-checks) are chosen by the backup's version.
- Migration tests run on SQLCipher (DEC-08, DEC-12): `MigrationTestHelper(instrumentation, AppDatabase::class.java, emptyList(), SupportOpenHelperFactory(ByteArray(0)))`. Verify at setup: this constructor exists in the Room 2.8.5 testing artifact.
