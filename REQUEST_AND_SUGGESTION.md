# Request and Suggestion Compilation

**Status:** working input for the next planning session — not a decision record, not a spec, not approved scope.
**Compiled:** 2026-11-22 (UTC), from `HEAD 2b9a95a` and the latest backup dated 2026-11-23 local (UTC+7).
**Owner:** SnakeEve
**Sources:**
- Five notes in `doro-organizer-backup-2026-11-23 (1).db`, all linked to the project `DoroOrganizer` and the person `Doro AI Dev`.
- Read-only code and design-doc review of this repository.

**Purpose:** collect the owner's requests and the reviewer's recommendations in one place, so that on Tuesday the assistant can finish the pending bug fix, then turn this into a decision-ready draft and a set of open questions for the owner.

**Precedence:** `docs/decisions.md` > `stakeholder-notepad-spec.md` > the other docs > `SPEC.md`. This document is input only. Every behaviour change needs a `DEC-nn` first; every new implementation task needs a SPEC row. Nothing here is approved until the owner says so.

**Privacy note:** only the five app-feedback notes are quoted below. No other note content, person names, project names, search text or passwords from the backup are reproduced here. The aggregate usage figures are counts only.

---

## 1. Context

The v1 scope is complete on paper: notes, five perspectives, links, checklist, dates, search, backup/restore, encryption, fingerprint lock and release gates. The release app is in real use, and the database schema version 1 is released and frozen. Any new table or column needs `AppDatabase.VERSION` 2, a migration and a migration test.

The backup was a short first-use sample. It is useful as a signal of how the app is being used, not as a long-term usage study.

### 1.1 Aggregate usage signals

| Signal | Latest backup | Interpretation |
|---|---:|---|
| Notes | 56 | small first-use sample |
| People / projects | 24 / 17 | both dimensions are actively used |
| Person links | 189 | 3.4 per note |
| Project links | 95 | 1.7 per note |
| Median people per note | 4 (range 1–6) | multi-person entry is the main friction |
| Median projects per note | 2 (range 1–3) | link sets repeat across notes |
| Notes with a person | 55 | one note is project-only |
| Orphans | 0 | orphan cleanup is not a current pain |
| Dated notes | 0 | calendar and reminders are unvalidated |
| Checked notes | 5 | checklist is secondary |
| Content length | median 48 chars, 8 words, max 154 | short notes |
| Multiline notes | 7 of 56 | mostly one-liners |
| Exact duplicate content | 0 | duplication should copy links, not text |
| Case-insensitive duplicate project names | 1 pair | entity hygiene matters |
| Non-ASCII / URLs / emails / money / tags / emoji | all ASCII; none of the others | rich text and attachments are not needed yet |

**Conclusion:** the observed workload is a *linking* workload, not a calendar or long-form writing workload. The highest-value near-term work is therefore:

1. making entities easier to manage and correct,
2. making repeated link entry faster,
3. making a note visible immediately after it is created,
4. eventually importing and exporting structured data.

The sample also shows that a full-text, rich-text or attachment feature is not justified by usage yet.

---

## 2. Owner requests from the backup

These are the five notes, quoted verbatim, with the reviewer's reading and current-code status.

| ID | Backup note | Owner text | Type | Current code |
|---|---:|---|---|---|
| R-1 | 2 | "No menu to edit/delete People and project" | UX gap / feature | Delete exists only in the note editor's overflow; no edit or rename exists |
| R-2 | 3 | "When selecting people/project/date menu, there is no All selection to see all available People/Project" | UX gap / feature | `AnyEntity` exists only in the secondary-filter picker; the primary picker has no "All" |
| R-3 | 37 | "When adding new notes, it doesn't bump to most top" | Bug / UX | Sorting is correct; the list keeps its scroll position and has no scroll-to-top after a new note |
| R-4 | 55 | "When adding people from the perspective of known project, the names of people that involved in the very project is sorted on the top" | Feature / UX | Co-linked ordering exists only for secondary filters, not for the editor's Add-person picker |
| R-5 | 56 | "Import new data feature via csv or json" / "Download template menu for csv and json" | Feature (large) | No import or template path exists |

### R-1 — Entity edit/delete menu

**Owner text:** "No menu to edit/delete People and project"

**What exists today:** deleting a person or project is possible from the note editor's overflow menu (`Delete person…` / `Delete project…`), and the delete-impact dialog already counts the affected notes and the notes that would become orphans. There is no rename or edit action anywhere. `EntityRepository` only observes and deletes (`app/src/main/java/com/doro/organizer/data/EntityRepository.kt:16`). The schema already has `persons.role`, `persons.notes`, `projects.description` and `projects.status`, but they are always NULL in v1 (`PersonEntity.kt:13`, `ProjectEntity.kt:13`, DEC-31).

**Why it matters:** the backup already contains one case-insensitive duplicate project name after a few hours. Today the only correction path is delete-and-recreate, which is a hard delete and can orphan notes. That is more destructive than the problem warrants.

**Recommendation:** treat this as an entity-lifecycle feature, not only a menu:
- an entity action sheet or directory entry with **Rename**, **Details**, **Delete**;
- reachable from the People/Projects title and from a picker row;
- existing delete-impact flow reused for Delete;
- zero-note entities reachable too.

**Scope:** no schema change for rename or for using the dormant columns. New repository update methods, validation, UI, tests. A merge-duplicates action is a later extension.

**Acceptance sketch:**
- Rename trims only through `model.TextWhitespace`, rejects blank, caps at 200 characters and allows duplicates (DEC-32).
- All open lists, chips, pickers and remembered selections reflect the new name without a restart.
- Rename does not change any note's `updated_at`.
- Details fields are capped, redacted in `toString()` and logs, and survive backup/restore.
- Delete still shows the impact dialog and remains a hard delete.
- Any typo fix no longer requires deleting the entity.

### R-2 — “All” selection in the entity picker

**Owner text:** "When selecting people/project/date menu, there is no All selection to see all available People/Project"

**Interpretation:** the primary picker for Individual/Project (`PickerPurpose.PerspectivePrimary`) has no "Any/All" row. The secondary filter picker has one (`AnyEntity` in `PickerItems.kt`), but the primary picker only lists entities. The All tab is a note list, not a people/projects directory, so there is no single place that answers "show me all people" or "show me all projects" after a primary entity has been chosen.

**Recommendation:** add a browseable **People / Projects directory** and put an "All people" / "All projects" entry at the top of the primary picker. Tapping it opens the directory (or resets to the full picker, depending on the owner's answer). The All tab remains "all notes"; the directory is about entities, not notes.

**Scope:** mostly UI and read-only queries; no schema change. Needs a product decision because the current spec has no dedicated management screen.

**Acceptance sketch:**
- From the title picker, one tap reaches every person/project, including zero-note entities.
- Each row keeps the existing "N notes · added date" metadata.
- The directory is searchable and has the same name order as other pickers.
- Returning from the directory preserves the chosen primary or clearly resets it.

**Open point:** "All" could mean "all entities" or "all notes for all people/projects". Those are different screens. The owner should choose.

### R-3 — New notes do not appear at the top

**Owner text:** "When adding new notes, it doesn't bump to most top"

**What the code does:** the canonical order is correct; a new note receives `updated_at = now` and sorts first among unchecked notes (`NoteSql.kt:33`). The likely causes are:
1. The list keeps `rememberLazyListState()` and only scrolls to top when the current tab is tapped again (`NoteListScreen.kt:123`, `ReselectEffect`). If the owner was scrolled down, the new note is above the viewport, not missing.
2. If the list has a secondary person/project filter, a new note is prefilled only from the perspective's primary, never from secondary filters (DEC-38, `ListDestinations.kt`). Such a note may not match the active filter at all.

**Recommendation:**
- Return a "new note saved" result from the editor and scroll the list to the top for *new* notes.
- Do **not** jump on ordinary edits; preserving scroll there is better.
- If the new note is hidden by active filters, show a short message with a "Show" action that clears the conflicting filters, or explain why it is not visible.
- Reproduce both cases on the phone before choosing the final fix: scrolled list and filtered All.

**Scope:** small UI/navigation change plus tests. No schema change.

**Acceptance sketch:**
- Create a note from a scrolled list in any perspective; after save, the new note is visible at the top.
- Create a note under a secondary filter; the app either shows it and explains the filter, or offers a one-tap way to see it.
- Editing an existing note does not forcibly scroll the list.

### R-4 — Prioritize people already involved in the project

**Owner text:** "When adding people from the perspective of known project, the names of people that involved in the very project is sorted on the top"

**What exists today:** co-linked ordering is implemented for the *filter chip* picker: `SecondaryFilter` carries `coLinkedTo`, and the picker puts co-linked entities first (DEC-28). The editor's Add-person picker uses `EditorLinks(selectedIds)` and has no `coLinkedTo`; therefore it does not prioritize project members (`PickerPurpose.kt`).

**Recommendation:** pass the draft's project id (or the union of its projects) to `EditorLinks` as `coLinkedTo`, extend `EntityPickerViewModel` and `PickerItems` to use it, and reuse the existing "People on this project's notes" section. Apply the symmetric rule when adding projects from a person's perspective if the owner wants it.

**Scope:** small/medium. No schema change. Needs a rule for multiple projects and for a note that has both people and projects already.

**Acceptance sketch:**
- A note created from a project perspective opens Add person with that project's people first.
- People outside the project remain reachable in the second section; nothing is hidden.
- Within each section, the existing `Collator` order applies.
- The behaviour is covered by a UI test and a picker-order unit test.

### R-5 — CSV/JSON import and templates

**Owner text:** "Import new data feature via csv or json" / "Download template menu for csv and json"

**What exists today:** no import path. Settings has backup, restore, share-backup, encryption, password, biometric, screenshot, encrypted-backup and About. The backup is a whole-database file for restore, not a data-entry format.

**Recommendation:** treat this as a separate, larger feature:
- **JSON first**, CSV second. JSON can represent structured links; CSV needs an explicit encoding for people/projects and careful RFC 4180 handling.
- **Append-only** by default. "Replace all" is already Restore's job; import should not silently delete.
- Preview before writing: N notes, P people, R projects, orphan notes, invalid rows.
- One transaction through `AppDatabase.transaction`; all-or-nothing.
- Enforce existing limits: 200,000 characters, 100,000 lines, 200-character names, date range 1900-01-01 to 2100-12-31, `TextWhitespace` rules, checked/checked_at consistency.
- Do not import database IDs; refer to entities by name and define the matching rule.
- Report invalid rows by row number only; never log field content.
- Generate a template through SAF with no user data in it.
- Consider an export counterpart using the same format, so the feature round-trips.

**Scope:** large. No schema change, but a new file parser, a validation layer, a preview UI, a transactional writer, tests and docs. This needs its own DEC and SPEC task.

**Acceptance sketch:**
- A valid JSON file creates notes, new people/projects and links in one transaction.
- An invalid file produces no partial writes and names only the row numbers that failed.
- Imported content obeys the same caps and whitespace rules as typed content.
- The template downloads and opens without exposing any existing notes.
- The feature has no network access and works with encryption on.
- A backup is offered or required before a large import.

---

## 3. Recommended features

These are suggestions from the code and usage review. They are not all owner requests, and none is approved. Priority is relative, not a schedule.

### Near-term, no schema change

#### S-1 — Entity directory and lifecycle (covers R-1 and R-2)

Rename, details, delete and an "All" directory in one coherent feature. This is the highest-leverage near-term change: it fixes typos without hard deletes, makes zero-note entities manageable and gives People/Projects a real home.

Dormant schema fields:
- `persons.role` (free text)
- `persons.notes` (free text; column `notes`)
- `projects.description` (free text)
- `projects.status` (define whether this is a free-text label or a small enum; do not leave it ambiguous)

If status becomes "Active/Archived", hide archived entities from ordinary pickers and show them in the directory. That is a product decision, not a schema change.

Later extension: **merge duplicate entities**. Move all links to a target, delete the source in one transaction, show the impact, keep hard-delete semantics. Only if the owner actually creates duplicates.

#### S-2 — Reuse link sets

The median note has 4 people and 2 projects, while note content is unique. Add:
- "Copy people/projects from another note"
- "New note with the same links"
- optionally "Duplicate note" for full copies

This attacks the real data-entry cost without duplicating text. No schema change. Needs decisions: does copying links bump `updated_at`; does a duplicated note start unchecked; is the date copied?

#### S-3 — Batch unlink

Batch mode can add associations, set dates and delete, but cannot remove a person/project from several notes. Add "Remove person/project…" with the existing impact wording, one transaction and an `updated_at` rule. This is the natural counterpart to batch associate. No schema change; destructive, so it needs confirmation and orphan-impact counts.

#### S-4 — People × Projects matrix

A read-only grid of people against projects with note counts. Tapping a cell opens the notes already filtered by that person and project. It fits the analyst/team-lead use case and needs no schema change. Put it behind Settings or an overflow action rather than adding a sixth tab, so the five-perspective model stays intact.

#### S-5 — Share/export a note or dossier

Backups are for restore; they are not readable handoff material. Add:
- "Share note as text" (plain text or Markdown)
- "Export notes for this person/project"
- optionally CSV/JSON export, sharing the import format

Use SAF or the share sheet; warn that the exported file is no longer protected by the app; keep content out of logs. No schema change.

#### S-6 — Recent and contextual entity suggestions

With 24 people and 17 projects, the fixed alphabetical picker already requires search. Add a "Recent" section based on the latest linked note, and/or suggestions derived from co-occurrence. This is an exception to the current picker order (DEC-32), so it needs a decision. R-4 is the first, narrow version of this idea and should be implemented first.

#### S-7 — Import/export formats (covers R-5)

See R-5. Split implementation:
1. JSON model + parser + template + append-only import.
2. CSV parser (RFC 4180, quoted fields, CRLF, BOM) + template.
3. Export using the same model.
4. Optional round-trip tests and a "backup before import" offer.

### Conditional or later

#### S-8 — Teams / saved groups of people

A saved set ("my team") would collapse repeated 4–6 person selection into one tap. High potential value, but it needs a new table, `VERSION 2`, a migration and tests. Do not pay that cost until the owner confirms recurring groups.

#### S-9 — Date quick actions and reminders

The sample has zero dated notes. Before building notifications, confirm that dates are intended for deadlines. If yes, start with editor chips (Today / Tomorrow / Next week) and an overdue count/badge. Local notifications would add `POST_NOTIFICATIONS` and scheduling; still fully offline, but a new permission and manifest surface.

#### S-10 — Share-in and home-screen capture

Receiving text from another app or a quick-capture widget is attractive for an analyst. Both add exported components or new manifest surface and need a security review. Later.

#### S-11 — Backup hygiene

Possible small improvements: show the last backup time, prompt for an encrypted backup when the database is plaintext, and explain what a backup file contains. The latest backup is plaintext SQLite in Downloads; that is a deliberate setting, not a bug, but it is worth making the consequence visible. No schema change.

### Explicit non-goals for now

Do not build these without a new owner decision:
- rich text or Markdown rendering;
- attachments;
- cloud sync, accounts or network anything;
- note history, undo, trash or soft delete (`V31`);
- manual sort or pinning (conflicts with the canonical order);
- batch check/uncheck (excluded by the stakeholder spec);
- full-text search / FTS (deferred by the spec);
- automatic backup (excluded by DEC-69).

---

## 4. Mapping and suggested order

| Item | Owner request | Suggested feature | Size | Schema |
|---|---|---|---|---|
| R-3 | New note not at top | immediate bug/UX fix | S | none |
| R-4 | Project members first | first slice of S-6 | S–M | none |
| R-2 | No “All” selection | part of S-1 directory | M | none |
| R-1 | No edit/delete menu | S-1 entity lifecycle | M–L | none |
| R-5 | CSV/JSON import | S-7 | L | none |
| S-2 | — | reuse link sets | M | none |
| S-3 | — | batch unlink | M | none |
| S-5 | — | share/export | M | none |
| S-4 | — | people × projects matrix | M–L | none |
| S-8 | — | teams/groups | L | v2 |
| S-9 | — | date actions/reminders | M–L | none, new manifest surface |
| S-10 | — | share-in/widget | L | none, new components |
| S-11 | — | backup hygiene | S–M | none |

Suggested sequence after Tuesday's bug fix:

1. **Fix and verify the pending bug first.** Do not mix it with feature work.
2. **Draft the decisions and SPEC rows from this document.** Resolve the open questions with the owner before writing code.
3. **R-3** (new-note visibility) and **R-4** (project members first): small, immediate, low risk.
4. **S-1** including R-1 and R-2: entity lifecycle and directory.
5. **S-2** and **S-3**: link reuse and bulk unlink.
6. **S-5** and **S-4**: export and analyst overview.
7. **S-7 / R-5**: import, JSON first, as a separate project.
8. Conditional: S-8, S-9, S-10, S-11.

This order is a suggestion, not a commitment. The owner's priority wins.

---

## 5. Open questions to resolve before implementation

These are candidate questions for the Tuesday drafting session. Each has a proposed default so the work can start even if the owner does not answer immediately. The owner may replace any default.

### Entity lifecycle (R-1, R-2, S-1)

- **OQ-1:** Should entity editing be rename only, or rename plus `role` / `remarks` / `description` / `status`?  
  *Default:* rename plus details, with the fields as free text and strict caps.
- **OQ-2:** Where should entity actions live?  
  *Default:* title action sheet, picker-row action, and the existing editor overflow for delete.
- **OQ-3:** Does “All” mean an entity directory, or leaving the Individual/Project perspective?  
  *Default:* an entity directory; the All tab stays “all notes”.
- **OQ-4:** Should a merge-duplicates action be part of the first version?  
  *Default:* no; rename and delete first, merge only if duplicates keep appearing.
- **OQ-5:** Is `projects.status` a free-text label or a small set such as Active/Archived?  
  *Default:* start with a free-text label; add archive semantics only after an owner decision.

### New-note visibility (R-3)

- **OQ-6:** After saving a new note, should the list always scroll to it?  
  *Default:* yes, for new notes only; edits keep the current position.
- **OQ-7:** If a secondary filter hides the new note, should the app clear the filter, offer a snackbar action, or do nothing?  
  *Default:* keep the filter and show a short “Not shown by the current filter — Show” action.
- **OQ-8:** Should a new note created in All inherit secondary filters as links?  
  *Default:* no, because DEC-38 says prefill only from the primary. Revisit only if the owner wants it.

### Picker priority (R-4)

- **OQ-9:** When a note has several projects, which people are “involved”?  
  *Default:* the union of people linked to any of the note's projects, then the normal name order.
- **OQ-10:** Should the symmetric rule apply when adding projects from a person's perspective?  
  *Default:* yes.
- **OQ-11:** Should the priority section be a separate header or just a sort order?  
  *Default:* a separate “People on this project's notes” header, as the filter picker already does.

### Import/export (R-5, S-7)

- **OQ-12:** JSON first or CSV first?  
  *Default:* JSON first; CSV next.
- **OQ-13:** Append-only, or also “replace all”?  
  *Default:* append-only; replacement remains Restore's job.
- **OQ-14:** Should an imported name reuse an existing person/project?  
  *Default:* exact case-insensitive match reuses it; otherwise create a new entity. If several entities match, show a choice or import as a new entity.
- **OQ-15:** Should imported `created_at` / `updated_at` / `checked_at` be honored?  
  *Default:* no; use import time and the current AppTime, so the canonical order and invariants stay simple. Historical timestamps would need their own validation and decision.
- **OQ-16:** Should export use the same format as import?  
  *Default:* yes, after import works.
- **OQ-17:** Which spreadsheet format does the owner actually use?  
  *Default:* CSV with one row per note and `;`-separated links; JSON for structured backups.

### Other suggestions

- **OQ-18:** Should copying links or duplicating a note bump `updated_at`?  
  *Default:* the new note gets `now`; existing notes are untouched. A batch unlink bumps affected notes according to the DEC-21 matrix.
- **OQ-19:** Should the people × projects matrix be a screen behind Settings or an overflow action?  
  *Default:* an overflow action in People/Projects; no sixth tab.
- **OQ-20:** Are dates intended for real use?  
  *Default:* no reminders until the owner says dates matter.
- **OQ-21:** Is a share/export warning enough, or should every export be encrypted?  
  *Default:* warn when plaintext; offer encrypted export when the database is encrypted.
- **OQ-22:** Should imported/exported files be deleted automatically after use?  
  *Default:* follow the existing share-file sweep policy; do not leave copies longer than necessary.

---

## 6. Implementation guardrails

These are not negotiable without a decision record:

- **Decision first.** Add or amend `DEC-nn` before implementing any user-visible behaviour change.
- **Schema v1 is frozen.** Near-term features must use existing tables and columns. Teams or any new table need version 2, a migration and a migration test.
- **Hard deletes only.** No undo, trash, history or soft delete. Destructive bulk actions need explicit confirmation and impact counts.
- **Canonical order.** Every note list uses the one `ORDER BY`; new ordering ideas need a decision. This includes any “recent” picker order, which is a separate concern from note order.
- **Transactions.** Multi-row writes run through `AppDatabase.transaction`.
- **No user data in logs or exceptions.** Import/export errors name row numbers, not field values. Entities and UI models keep redacted `toString()`.
- **No network.** No internet permission, no analytics, no cloud. Import/export is local file I/O.
- **All text in `strings.xml`.** New UI needs strings, content descriptions and 200% font checks.
- **Tests.** A bug fix ships with a test that fails without it. New SQLCipher paths need instrumented tests on the owner's phone. Pure parsers and formatters can use JVM tests.
- **No phone action without the owner.** Tuesday's bug fix and any device test runs follow `dev/claude/HANDOFF.md` § 4.

---

## 7. Tuesday drafting checklist

1. Finish the pending bug fix and run the standard verification; do not start features before it is green.
2. Read this document with `docs/decisions.md` and `SPEC.md` §T open.
3. Classify every R item as bug, UX gap or feature; check whether the bug fix has already changed the answer (especially R-3).
4. Turn the confirmed owner answers into `DEC-nn` entries. Do not implement while a question is open; use the default above only to keep work unblocked.
5. Produce a decision-ready draft with:
   - one row per accepted request/suggestion,
   - the user-visible behaviour,
   - schema/migration impact,
   - affected docs and invariants,
   - test plan and device-test needs.
6. Add SPEC tasks only for approved work; keep the source-of-truth precedence.
7. Re-read the five owner notes before closing the draft, so no wording is lost in translation.

---

## 8. Traceability

| Source | Location |
|---|---|
| Five owner notes | Backup `doro-organizer-backup-2026-11-23 (1).db`, note ids 2, 3, 37, 55, 56 |
| Code baseline | `2b9a95a` |
| Entity repository | `app/src/main/java/com/doro/organizer/data/EntityRepository.kt` |
| Dormant entity columns | `PersonEntity.kt:13`, `ProjectEntity.kt:13`, DEC-31 |
| Picker purposes | `app/src/main/java/com/doro/organizer/ui/navigation/PickerPurpose.kt` |
| Picker “Any” row | `app/src/main/java/com/doro/organizer/ui/common/PickerItems.kt` |
| New-note prefill | `app/src/main/java/com/doro/organizer/ui/notes/ListDestinations.kt`, DEC-38 |
| List scroll state | `app/src/main/java/com/doro/organizer/ui/notes/NoteListScreen.kt:123` |
| Canonical note order | `app/src/main/java/com/doro/organizer/data/db/NoteSql.kt:33`, DEC-23 |
| Existing delete impact | `app/src/main/java/com/doro/organizer/ui/editor/EditorDialogs.kt`, DEC-34 |
| Backups | `docs/db-lifecycle.md`, DEC-83 |
| Test rules | `docs/testing.md`, `dev/claude/HANDOFF.md` § 3, § 4 |
