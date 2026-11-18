# Doro Organizer — Specification

**Project name**: Doro Organizer  
**Target users**: System Analyst, Business Analyst, Team Lead, Solution Architect  
**Platform**: Android (Kotlin + SQLite / Room)  
**SDK**: minSdk 28 (Android 9), targetSdk / compileSdk 35 (Android 15)  
**Core idea**: A notepad where notes can be freely linked to Individuals and/or Interests (Projects). Users switch between multiple perspectives on the same data. Fully offline.

## 1. Core Concepts

### Entities
- **Note**: Free-form text content + optional date (stored as INTEGER epoch days; null allowed) + a simple boolean checklist flag (checked/unchecked).  
  - Notes with a null date appear in All / Individual / Project / Orphan views but do not appear on any calendar day. An optional “Undated” filter can be offered in non-calendar views.
  - The checklist flag is independent of all other fields and perspectives. It can be toggled directly from any list or detail view.
- **Person** (Individual): A stakeholder / colleague / interviewee.
- **Project** (Interest / Context): A project, initiative, client, workstream, etc.

### Relationships
- A Note can be linked to:
  - Zero or more Persons
  - Zero or more Projects
  - Both, either, or neither
- Notes with **zero** Persons **and** zero Projects are called **Orphaned Notes**.

### Constraints
- No deduplication of notes, persons, or projects.
- No history / versioning / audit log. All edits overwrite in place.
- Deletes are hard deletes (recovery only via backup).

## 2. Five Perspectives

| Perspective       | Primary Filter                  | Purpose                                      | Secondary Filters Allowed      |
|-------------------|---------------------------------|----------------------------------------------|--------------------------------|
| **All**           | None                            | Global list / search / timeline              | Person, Project, Date range    |
| **Individual**    | One Person                      | Everything related to a specific person      | Project, Date range            |
| **Interest/Project** | One Project                  | Everyone and everything related to a project | Person, Date range             |
| **Date**          | One Date or Date Range          | Calendar-first view                          | Person, Project                |
| **Orphan**        | No Person AND No Project        | Clean-up after parent deletion               | Date range, content search     |

### Date Perspective UX
1. Shows a calendar overview (month view recommended).
2. Days containing notes are visually marked.
3. Selecting a day shows the list of notes for that day.
4. From the day list, user can further filter by Person and/or Project.

All other perspectives can also apply Date as a secondary filter.

## 3. CRUD Behavior

### Notes
- Create, Read, Update, Delete freely.
- Empty content is allowed (user may prepare a note in advance).
- Delete note = immediate hard delete (no orphaning).

### Note Creation & Editing UX
- New note: floating **+** button at lower right.
- Creation/editing screen layout (email-inspired but simpler):
  - Title / content field at the top
  - Below it: association area for Persons and Projects (multi-select, create-on-the-fly allowed)
  - Date picker (optional)
  - Checklist toggle
  - Icon menu (overflow / three-dot) for managing associations
- User can set date and checklist flag during creation or later edit.
- Persons and Projects have no dedicated management list screens. They are created and associated only from the note screen.
- From the note’s icon menu the user can:
  - Unlink a person or project from this note (if the note then has zero links it becomes orphaned)
  - Delete a person or project entirely (with the existing impact warning)
- No post-creation editing of person/project details is required; the menu exists for unlink and delete.

### Persons & Projects
- Created on the fly from the note association UI.
- **Unlink** from a note removes only that association. If the note ends up with zero person and project links, it becomes orphaned.
- **Delete Person or Project** (via the note’s icon menu):
  1. Show warning with impact:
     - Number of notes that will be unlinked
     - Number of notes that will become fully orphaned
  2. On confirmation → remove the entity and all its association rows.
  3. Notes that now have zero remaining associations become Orphaned.

## 4. Sorting & Checklist Behavior

All list views (All, Individual, Interest/Project, Date day-list, Orphan) use the same sorting rules below.

### Overall order
1. **Unchecked notes** appear first (top).
2. **Checked notes** appear after them (bottom).

### Unchecked notes sort
- Primary: `updated_at` descending (most recently updated first)
- Secondary: `created_at` descending
- The optional calendar `date` field is never used for sorting.

### Checked notes sort
- Sorted by `checked_at` ascending (oldest checked note at the very bottom, most recently checked note just above it).
- Checking a note:
  - Sets `checked = 1`
  - Sets `checked_at = now`
  - Moves the note into the checked group (bottom)
- Unchecking a note:
  - Sets `checked = 0`
  - Clears `checked_at` (set to null)
  - Updates `updated_at = now`
  - Moves the note back into the unchecked group (near the top, because of the fresh `updated_at`)

### Checklist UI
- The checkbox is always visible and tappable in every list row and in the note detail view.
- Content descriptions:
  - “+” button: “Create new note”
  - Checkbox: “Mark note as done” / “Mark note as not done”
  - Person/Project association area: “Associate persons and projects”
  - Date field: “Set or change date”
  - Backup/Restore actions: “Backup all data” / “Restore from backup”

### Date field display (independent of sorting)
- Stored as epoch days.
- Shown in UI with a common local format (e.g. “17 Nov 2026” or the system short date).
- Future dates are allowed (for meetings or deadlines).
- If today is after the note’s designated date, display a distinct indicator (e.g. “Overdue” label or different styling).

## 5. Empty States & Error Handling

- Any list or perspective with no notes shows the text “(No data)”.
- This applies even if a note exists but has null or empty-string content.
- Completely empty database on first launch is expected and fine.
- If the database is corrupted:
  - Show a clear message that the database is corrupted.
  - Delete the corrupted database file.
  - Let the user start from scratch.
  - If the user has a backup, they can restore it via Settings.

## 6. Orphan Perspective (Detailed)

- Lists all notes that currently have no Person and no Project links.
- Uses the same sorting rules as other perspectives (unchecked by updated_at, checked notes at bottom by checked_at).
- Each item shows content preview, date (if any), checklist flag, and “Orphaned” indicator.
- Actions on a note:
  - Associate with one or more Persons
  - Associate with one or more Projects
  - Edit content / date
  - Delete
- Once a note receives at least one Person or Project link, it immediately leaves the Orphan list.
- Supports multi-select for batch operations (see below).

## 7. Batch Re-association Workflows

Primarily used in **Orphan** (also available in Date and All when useful).

### Batch Associate
1. Multi-select notes.
2. Choose “Associate with Person(s)” and/or “Associate with Project(s)”.
3. Multi-select picker (existing items + ability to create new).
4. Confirm → all selected notes receive the chosen links (additive).
5. Affected notes leave Orphan view if they now have ≥1 link.

### Other Batch Actions
- Batch delete (with confirmation)
- Batch set/change date
- Select all (on current filtered list)

No batch check/uncheck — checklist is toggled per note only.  
After batch associate or batch delete, show success count. No undo.

## 8. Settings & Navigation

### Navigation
- Primary switcher for the five perspectives (bottom navigation, navigation rail, or top tabs — implementer’s choice, must be always reachable).
- From Individual and Interest/Project perspectives, user picks the specific person or project from a list/searchable picker.

### Settings (v1)
- **Backup everything**: Export the database file (see Security section).
- **Restore from backup**: Replace current database (with confirmation and validation).
- **Security options** (all optional, off by default):
  - Enable database encryption and set password
  - Change encryption password
  - Enable/disable biometric app lock (hidden/disabled if no sensor or none enrolled)
  - Enable/disable screenshot protection
  - Option to encrypt backup file on export
- Forgotten encryption password: no recovery — user must clear app data and start over.
- (Optional later) Clear all data.

### Search
- Simple case-insensitive substring search on note `content` (SQL `LIKE`).  
- Searches text only; ignores checked status.
- Checked notes still follow the normal sort rules (appear at the bottom).
- Available in All, Orphan, and as a filter inside other perspectives.  
- No full-text indexing required for v1.

## 9. Data Model (SQLite)

```sql
CREATE TABLE persons (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    role TEXT,
    notes TEXT,
    created_at INTEGER,
    updated_at INTEGER
);

CREATE TABLE projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT,
    created_at INTEGER,
    updated_at INTEGER
);

CREATE TABLE notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    content TEXT NOT NULL,
    date INTEGER,               -- nullable, stored as epoch days (days since 1970-01-01)
    checked INTEGER NOT NULL DEFAULT 0,  -- simple checklist flag: 0 = unchecked, 1 = checked
    checked_at INTEGER,                 -- timestamp when the note was last checked (null if unchecked)
    created_at INTEGER,
    updated_at INTEGER
);

CREATE TABLE note_persons (
    note_id INTEGER NOT NULL,
    person_id INTEGER NOT NULL,
    PRIMARY KEY (note_id, person_id),
    FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
    FOREIGN KEY (person_id) REFERENCES persons(id) ON DELETE CASCADE
);

CREATE TABLE note_projects (
    note_id INTEGER NOT NULL,
    project_id INTEGER NOT NULL,
    PRIMARY KEY (note_id, project_id),
    FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

-- Performance indexes
CREATE INDEX idx_note_persons_note ON note_persons(note_id);
CREATE INDEX idx_note_persons_person ON note_persons(person_id);
CREATE INDEX idx_note_projects_note ON note_projects(note_id);
CREATE INDEX idx_note_projects_project ON note_projects(project_id);
CREATE INDEX idx_notes_date ON notes(date);
CREATE INDEX idx_notes_created ON notes(created_at);
CREATE INDEX idx_notes_updated ON notes(updated_at);
CREATE INDEX idx_notes_checked_at ON notes(checked_at);
```

### Key Queries
- **Orphan**: Notes with no rows in either junction table.
- **Individual**: Notes joined via `note_persons` for a given `person_id`.
- **Project**: Notes joined via `note_projects` for a given `project_id`.
- **Date**: Notes filtered by `date` (or range).

### Stable list ordering (all perspectives)
```sql
ORDER BY
  checked ASC,                                          -- unchecked (0) first
  CASE WHEN checked = 0 THEN updated_at END DESC,       -- unchecked: newest update first
  CASE WHEN checked = 0 THEN created_at END DESC,
  CASE WHEN checked = 1 THEN checked_at END ASC,        -- checked: oldest check at bottom
  id DESC                                               -- deterministic tie-breaker
```

All write operations that touch multiple rows (especially batch associate/delete) must run inside a transaction.

## 10. Non-Functional Requirements

- **Fully offline**. No network access, no accounts, no cloud sync, no telemetry.
- All data stored in local SQLite only.
- Backup/restore is manual file-based (export/import the raw SQLite `.db` file).
- Fast switching between the five perspectives.
- Smooth multi-select and batch operations.
- Simple, low-friction note capture.
- See Section 11 for security and robustness measures appropriate to a fully offline personal app.

## 11. Security & Robustness (Offline Personal App)

Even though the app is fully offline, the following measures are implemented. Password and fingerprint/biometric protection are **optional** (user can enable them in Settings).

### Data at Rest
- Database stored in Android app-private storage (not world-readable).
- **Optional database encryption** (SQLCipher or equivalent, fixed algorithm — AES-256):
  - When enabled, the entire SQLite database is encrypted.
  - User sets a password; the password is used to derive/protect the encryption key (via Android Keystore).
  - If the user forgets the password there is no recovery. They must clear app data and start over (or restore from an unencrypted backup if they have one).
  - Password can be set or changed from Settings (re-encrypts the database).

### App Lock (Optional)
- Optional biometric app lock before the app shows any notes.
- Implemented with Android `BiometricPrompt` (system UI).
- Automatically supports whatever strong biometrics the device offers (fingerprint, face, etc.). No need to handle methods separately.
- If the device has no biometric sensor or none is enrolled, the Settings option is simply hidden or disabled.
- Can be toggled independently of database encryption.
- When enabled, the app requires successful authentication on launch and when returning from background.
- Device-credential fallback (PIN/pattern/password) is not required.
- For v1 this is an app lock only; it does not need to be tied to the database encryption key.

### Initial State
- On first run, no security features are enabled.
- User can turn on encryption, password, biometric lock, or screenshot protection later in Settings.

### Key Management
- Encryption keys (when used) are stored in the Android Keystore.
- Keys are never hard-coded or stored in plain SharedPreferences.
- For v1 the biometric app lock is independent of the encryption key.

### Backup / Restore
- Backup produces a raw `.db` file (or the encrypted database file if encryption is enabled).
- Close or checkpoint the database before copying so the backup is consistent (handles WAL).
- When sharing/exporting, use Android’s share sheet or Storage Access Framework.
- Suggest a clear default filename (e.g. `doro-organizer-backup-YYYY-MM-DD.db`).
- **Optional**: allow the user to encrypt the exported file with a separate password before sharing.
- Restore must:
  - Require explicit user confirmation.
  - Validate the file is a valid SQLite database (or valid encrypted database) containing the expected tables.
  - Perform an atomic replace.
  - If the backup is encrypted, prompt for the password.
  - Reject invalid files with a clear error message.
- Treat all exported files as sensitive.

### Additional Hardening
- No network permission requested.
- No logging of note content in production builds.
- Optional `FLAG_SECURE` on note screens (prevents screenshots and recent-apps thumbnails) — can be toggled in Settings.
- Parameterized queries / Room only.

### Device Assumptions
- Strongest protection still depends on the device having a screen lock and full-disk encryption enabled.
- If the user exports an unencrypted backup to an insecure location, that file is exposed.

### Settings Controls (Security)
User can independently enable/disable:
- Database encryption + optional password
- Biometric / fingerprint app lock
- Screenshot protection (`FLAG_SECURE`)
- Encrypted backup export

### Out of Scope for Security (v1)
- Multi-user access control
- Per-note encryption (full-database encryption is used instead)
- Remote wipe
- Network-related security (no network)

## 12. Out of Scope (v1)

- Deduplication
- Note history / versioning
- Multi-user / sync
- Rich text / attachments (plain text or Markdown is sufficient)
- Advanced full-text search (can be added later with FTS5)

## 13. Success Criteria

A user can:
1. Create notes (via + button) linked to any combination of persons and projects (or none), with optional date and checklist flag. Empty content is allowed.
2. Switch fluidly between Individual, Project, Date, All, and Orphan views.
3. Delete a person or project and later find + re-associate the resulting orphaned notes (including in batch).
4. Use the calendar (Date perspective) and combine it with person/project filters. Future dates and overdue indication work correctly.
5. Toggle the checklist flag; checked notes sink to the bottom sorted by check time; unchecking returns them to normal sort.
6. See “(No data)” in empty perspectives and recover cleanly from a corrupted database (delete + optional restore).
7. Backup and restore the entire dataset (with optional encryption).
8. Optionally protect the app with a password and/or fingerprint, and optionally enable database encryption and screenshot blocking.

---

## Optional Visual Polish (later)

- A background image may be added for visual personality (e.g. a soft, elegant illustration).  
- This is purely cosmetic and does not affect functionality.  
- Implementation can load it from assets or allow the user to pick their own image.

---

**End of Specification**
                                                                                                                