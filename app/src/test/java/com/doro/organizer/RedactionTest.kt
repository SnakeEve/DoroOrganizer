package com.doro.organizer

import com.doro.organizer.core.password.PasswordCheck
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.data.CreatedIds
import com.doro.organizer.data.ExclusiveScope
import com.doro.organizer.data.OpenFailureKind
import com.doro.organizer.data.Reopen
import com.doro.organizer.data.db.EntityOptionRow
import com.doro.organizer.data.db.NoteDetailRow
import com.doro.organizer.data.db.NoteEntity
import com.doro.organizer.data.db.NotePersonCrossRef
import com.doro.organizer.data.db.NoteProjectCrossRef
import com.doro.organizer.data.db.NoteRowRaw
import com.doro.organizer.data.db.PersonEntity
import com.doro.organizer.data.db.ProjectEntity
import com.doro.organizer.data.db.SaveProbe
import com.doro.organizer.data.maintenance.CommitOutcome
import com.doro.organizer.data.maintenance.CommitResult
import com.doro.organizer.data.maintenance.ExclusiveExport
import com.doro.organizer.data.maintenance.KeyStateRead
import com.doro.organizer.data.maintenance.MaintenanceFaultHook
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.data.maintenance.OperationRun
import com.doro.organizer.data.maintenance.PipelineStep
import com.doro.organizer.data.maintenance.StartupDecision
import com.doro.organizer.model.BatchResult
import com.doro.organizer.model.DateFilter
import com.doro.organizer.model.DeleteImpact
import com.doro.organizer.model.DraftInsert
import com.doro.organizer.model.EntityDeleteResult
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteDetail
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.NoteRow
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.SaveResult
import com.doro.organizer.security.BioSlot
import com.doro.organizer.security.Kdf
import com.doro.organizer.security.KeyFailure
import com.doro.organizer.security.KeySlot
import com.doro.organizer.security.KeyState
import com.doro.organizer.security.KeyVaultException
import com.doro.organizer.security.PreparedKey
import com.doro.organizer.security.UnlockResult
import com.doro.organizer.testing.SENTINEL
import com.doro.organizer.testing.TEST_SLOT_KEY_ALIAS
import com.doro.organizer.ui.common.EntityPickerUiState
import com.doro.organizer.ui.common.EntityRowUi
import com.doro.organizer.ui.common.NoteRowUi
import com.doro.organizer.ui.notes.ChipValue
import com.doro.organizer.ui.notes.FilterChipUi
import com.doro.organizer.ui.notes.FilterDimension
import com.doro.organizer.ui.notes.NoteListUiState
import com.doro.organizer.ui.notes.PerspectiveSpec
import com.doro.organizer.ui.notes.Primary
import java.time.LocalDate
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * testing.md I-39 (DEC-70): each class is built with [SENTINEL] in every `String` field, and its `toString()` prints
 * ids only. Grows with every class that I-39 lists.
 */
class RedactionTest {
    @Test
    fun noteEntity_printsIdOnly() {
        val note = NoteEntity(
            id = 7,
            date = 20_000,
            checked = true,
            checkedAt = 3,
            createdAt = 1,
            updatedAt = 2,
            content = SENTINEL,
            contentFold = SENTINEL,
        )

        assertEquals("NoteEntity(id=7)", note.toString())
    }

    @Test
    fun personEntity_printsIdOnly() {
        val person = PersonEntity(
            id = 8,
            name = SENTINEL,
            role = SENTINEL,
            remarks = SENTINEL,
            createdAt = 1,
            updatedAt = 2,
        )

        assertEquals("PersonEntity(id=8)", person.toString())
    }

    @Test
    fun projectEntity_printsIdOnly() {
        val project = ProjectEntity(
            id = 9,
            name = SENTINEL,
            description = SENTINEL,
            status = SENTINEL,
            createdAt = 1,
            updatedAt = 2,
        )

        assertEquals("ProjectEntity(id=9)", project.toString())
    }

    @Test
    fun crossRefs_printIdsOnly() {
        assertEquals("NotePersonCrossRef(noteId=1, personId=2)", NotePersonCrossRef(1, 2).toString())
        assertEquals("NoteProjectCrossRef(noteId=3, projectId=4)", NoteProjectCrossRef(3, 4).toString())
    }

    @Test
    fun noteRowRaw_printsIdOnly() {
        val raw = NoteRowRaw(
            id = 10,
            preview = SENTINEL,
            date = null,
            checked = false,
            checkedAt = null,
            createdAt = 1,
            updatedAt = 2,
            personsJson = SENTINEL,
            projectsJson = SENTINEL,
        )

        assertEquals("NoteRowRaw(id=10)", raw.toString())
    }

    @Test
    fun noteDetailRow_printsIdOnly() {
        val row = NoteDetailRow(
            id = 11,
            content = SENTINEL,
            date = null,
            checked = false,
            checkedAt = null,
            createdAt = 1,
            updatedAt = 2,
        )

        assertEquals("NoteDetailRow(id=11)", row.toString())
    }

    @Test
    fun entityOptionRow_printsIdOnly() {
        assertEquals("EntityOptionRow(id=12)", EntityOptionRow(12, SENTINEL, 1, 3, true).toString())
    }

    @Test
    fun noteRow_printsIdOnly() {
        val links = listOf(EntityRef(1, SENTINEL))
        val row = NoteRow(
            id = 13,
            preview = SENTINEL,
            date = null,
            checked = false,
            checkedAt = null,
            createdAt = 1,
            updatedAt = 2,
            persons = links,
            projects = links,
        )

        assertEquals("NoteRow(id=13)", row.toString())
    }

    @Test
    fun entityRef_printsIdOnly() {
        assertEquals("EntityRef(id=14)", EntityRef(14, SENTINEL).toString())
    }

    @Test
    fun noteFilter_printsSearchLengthOnly() {
        val filter = NoteFilter(
            perspective = Perspective.Date,
            primaryDays = DateFilter.Range(5, 6),
            search = SENTINEL,
        )

        val text = filter.toString()
        assertFalse(text, text.contains(SENTINEL))
        assertEquals(
            "NoteFilter(perspective=Date, primaryId=null, primaryDays=Range(fromDay=5, toDay=6), personId=null, " +
                "projectId=null, date=Any, search=<${SENTINEL.length} chars>)",
            text,
        )
    }

    @Test
    fun noteQuery_printsPatternLengthOnly() {
        val query = NoteQuery(1, 2, orphanOnly = false, undatedOnly = true, fromDay = 3, toDay = 4, SENTINEL)

        val text = query.toString()
        assertFalse(text, text.contains(SENTINEL))
        assertEquals(
            "NoteQuery(personId=1, projectId=2, orphanOnly=false, undatedOnly=true, fromDay=3, toDay=4, " +
                "searchPattern=<${SENTINEL.length} chars>)",
            text,
        )
    }

    @Test
    fun noteDraft_printsNoteIdOnly() {
        val entities = listOf(NewEntity("k1", SENTINEL))
        val draft = NoteDraft(
            draftKey = SENTINEL,
            noteId = 15,
            content = SENTINEL,
            date = null,
            checked = false,
            personIds = setOf(1),
            projectIds = setOf(2),
            newPersons = entities,
            newProjects = entities,
        )

        assertEquals("NoteDraft(noteId=15)", draft.toString())
    }

    @Test
    fun newEntity_printsKeyOnly() {
        assertEquals("NewEntity(key=k1)", NewEntity("k1", SENTINEL).toString())
    }

    @Test
    fun saved_printsIdsAndCountsOnly() {
        val saved = SaveResult.Saved(16, wrote = true, mapOf(SENTINEL to 1L), mapOf(SENTINEL to 2L, "b" to 3L))

        assertEquals("Saved(noteId=16, wrote=true, createdPersons=1, createdProjects=2)", saved.toString())
    }

    @Test
    fun createdIds_printsCountsOnly() {
        assertEquals("CreatedIds(persons=1, projects=0)", CreatedIds(mapOf(SENTINEL to 1L), emptyMap()).toString())
    }

    @Test
    fun saveProbe_printsClassNameOnly() {
        assertEquals("SaveProbe", SaveProbe(SENTINEL, 20_000).toString())
    }

    @Test
    fun noteDetail_printsIdOnly() {
        val links = listOf(EntityRef(1, SENTINEL))
        val detail = NoteDetail(
            id = 17,
            content = SENTINEL,
            date = 20_000,
            checked = true,
            checkedAt = 3,
            createdAt = 1,
            updatedAt = 2,
            persons = links,
            projects = links,
        )

        assertEquals("NoteDetail(id=17)", detail.toString())
    }

    @Test
    fun appError_printsTheCaseOnly() {
        assertEquals("InvalidInput(rule=BlankName)", AppError.InvalidInput(InputRule.BlankName).toString())
    }

    @Test
    fun batchResult_printsCounts() {
        assertEquals("BatchResult(selected=3, changed=2)", BatchResult(3, 2).toString())
    }

    @Test
    fun entityOption_printsIdOnly() {
        assertEquals("EntityOption(id=18)", EntityOption(18, SENTINEL, 1, 3, coLinked = true).toString())
    }

    @Test
    fun deleteImpact_printsCounts() {
        assertEquals("DeleteImpact(n=3, m=1)", DeleteImpact(3, 1).toString())
    }

    @Test
    fun entityDeleteResult_printsCounts() {
        assertEquals("EntityDeleteResult(deleted=true, n=3, m=1)", EntityDeleteResult(true, 3, 1).toString())
    }

    @Test
    fun draftInsert_printsNoteIdOnly() {
        val record =
            DraftInsert(noteId = 4, createdPersons = mapOf(SENTINEL to 5L), createdProjects = mapOf(SENTINEL to 6L))

        assertEquals("DraftInsert(noteId=4)", record.toString())
    }

    @Test
    fun holderTypes_neverPrintAKey() {
        val key = SENTINEL.toByteArray()

        assertEquals("ExclusiveScope(generation=3)", ExclusiveScope(3, key).toString())
        assertEquals("Generation(generation=4, newData=true)", Reopen.Generation(4, key, newData = true).toString())
    }

    @Test
    fun keyStateTypes_printTheModeOnly() {
        val secret = SENTINEL.toByteArray()
        val slot = KeySlot(KeyMode.PASSWORD, secret, secret, secret, Kdf(600_000, secret))
        val printed = listOf(
            slot,
            slot.kdf,
            BioSlot(TEST_SLOT_KEY_ALIAS, secret, secret),
            KeyState(4, slot, BioSlot(TEST_SLOT_KEY_ALIAS, secret, secret)),
            PreparedKey(slot, secret),
            UnlockResult.Key(secret),
        ).map { it.toString() }

        assertEquals(
            listOf("KeySlot(mode=PASSWORD)", "Kdf", "BioSlot", "KeyState(mode=PASSWORD)", "PreparedKey(mode=PASSWORD)",
                "Key(kekBroken=false)"),
            printed,
        )
    }

    @Test
    fun passwordCheck_neverPrintsThePassword() {
        assertEquals("Ok", PasswordCheck.Ok(SENTINEL.toByteArray()).toString())
        assertEquals("Rejected(rule=PasswordTooShort)", PasswordCheck.Rejected(InputRule.PasswordTooShort).toString())
    }

    @Test
    fun appErrors_ofDbMaintenance_printTheCaseOnly() {
        val printed = listOf(
            AppError.Throttled(5_000),
            AppError.InvalidInput(InputRule.PasswordMalformed),
            AppError.InvalidInput(InputRule.PasswordMismatch),
            AppError.StorageFull,
            AppError.FileAccess,
            AppError.KeyUnavailable,
            AppError.AuthFailed,
            AppError.Busy,
            AppError.OutcomeUnknown,
            AppError.Unexpected,
        ).map { it.toString() }

        assertEquals(
            listOf("Throttled(retryAtElapsedMs=5000)", "InvalidInput(rule=PasswordMalformed)",
                "InvalidInput(rule=PasswordMismatch)", "StorageFull", "FileAccess", "KeyUnavailable", "AuthFailed", "Busy",
                "OutcomeUnknown", "Unexpected"),
            printed,
        )
    }

    @Test
    fun maintenanceTypes_printEnumsAndIdsOnly() {
        val secret = SENTINEL.toByteArray()
        val bio = BioSlot(TEST_SLOT_KEY_ALIAS, secret, secret)
        val state = KeyState(4, KeySlot(KeyMode.PASSWORD, secret, secret, secret, Kdf(600_000, secret)), bio)
        val printed = listOf(
            MaintenanceStatus.Running(MaintenanceOp.Backup, PipelineStep.WRITE_DOCUMENT, 0.5f),
            MaintenanceStatus.Finished(MaintenanceOp.KeyChange, AppResult.Ok(MaintenanceResult.KeyChanged(KeyMode.PLAIN, KeyMode.PASSWORD))),
            MaintenanceStatus.Finished(MaintenanceOp.Backup, AppResult.Err(AppError.FileAccess), previousContentLost = true),
            MaintenanceOutcome.ReopenFailed(OpenFailureKind.Corrupt),
            StartupDecision.Proceed(4, KeyMode.DEVICE),
            StartupDecision.AdoptPlain(2),
            CommitResult(CommitOutcome.Unknown, IOException(SENTINEL)),
            KeyStateRead.Valid(state),
            ExclusiveExport.Failed(IOException(SENTINEL)),
            OperationRun(MaintenanceOp.Restore, MaintenanceFaultHook.None, {}, {}).apply { own(secret) },
        ).map { it.toString() }

        assertEquals(
            listOf(
                "Running(op=Backup, step=WRITE_DOCUMENT)",
                "Finished(op=KeyChange, result=Ok(value=KeyChanged(from=PLAIN, to=PASSWORD)), previousContentLost=false)",
                "Finished(op=Backup, result=Err(error=FileAccess), previousContentLost=true)",
                "ReopenFailed(kind=Corrupt)",
                "Proceed(generation=4, mode=DEVICE)",
                "AdoptPlain(generation=2)",
                "CommitResult(outcome=Unknown)",
                "Valid(KeyState(mode=PASSWORD))",
                "Failed",
                "OperationRun(op=Restore)",
            ),
            printed,
        )
    }

    @Test
    fun keyVaultException_hasAConstantMessageAndNoCause() {
        val e = KeyVaultException(KeyFailure.InvalidState)

        assertEquals("InvalidState", e.message)
        assertEquals(null, e.cause)
        assertFalse(e.toString().contains(SENTINEL))
    }

    @Test
    fun listUiTypes_printIdsAndCountsOnly() {
        val row = NoteRowUi(
            id = 21,
            title = SENTINEL,
            body = SENTINEL,
            checked = false,
            date = null,
            overdue = false,
            people = listOf(SENTINEL),
            projects = listOf(SENTINEL),
            orphan = false,
        )
        val chip = FilterChipUi(FilterDimension.Person, ChipValue.Entity(3, SENTINEL))
        val state = NoteListUiState(
            spec = PerspectiveSpec.of(Perspective.Individual),
            loading = false,
            primary = Primary.Entity(2, SENTINEL),
            rows = listOf(row),
            today = LocalDate.of(2026, 11, 17),
            chips = listOf(chip),
            filtersActive = true,
            searchOpen = true,
            dateFilter = DateFilter.Undated,
            selection = setOf(21),
            batchRunning = false,
            calendar = null,
            dialog = null,
            message = null,
        )
        val picker = EntityPickerUiState(
            loading = false,
            anyEntity = true,
            rows = listOf(EntityRowUi(4, SENTINEL, 1, LocalDate.of(2026, 11, 17))),
        )

        val printed = listOf(row, chip, state, state.primary, picker, picker.rows.single()).map { it.toString() }

        assertEquals(
            listOf(
                "NoteRowUi(id=21)",
                "FilterChipUi(Person, Entity(id=3))",
                "NoteListUiState(Individual, rows=1)",
                "Entity(id=2)",
                "EntityPickerUiState(loading=false, rows=1)",
                "EntityRowUi(id=4)",
            ),
            printed,
        )
    }
}
