# UI and interaction

Purpose: every screen, layout, interaction, dialog text, string resource and accessibility rule of Doro Organizer, including the UI of the gate screens and the Settings screen.

Related: [decisions.md](decisions.md) · [architecture.md](architecture.md) · [data-layer.md](data-layer.md) · [security.md](security.md) · [db-lifecycle.md](db-lifecycle.md) · [testing.md](testing.md) · [spec](../stakeholder-notepad-spec.md)

Conventions used in this document:
- "§n" always cites the [spec](../stakeholder-notepad-spec.md); "section n" cites this document.
- Every user-visible text has a resource key, written like `no_data`. [Section 11](#11-strings) is the only list of texts; other sections quote texts for readability, and section 11 wins if a quote ever differs. Texts marked "verbatim" there are fixed by the spec or by decisions.md and must not be reworded (DEC-55).
- Gate states (`Starting`, `NeedsPassword`, `Locked`, `Opening`, `Ready`, `Maintenance`, `Corrupt`, `KeyUnavailable`) and their transitions are defined in [architecture.md § SessionGate](architecture.md#sessiongate). This document defines only what each state looks like.
- Navigation keys (`TabKey`, `EditorKey`, `SettingsKey`, `PickerKey`, `NotePrefill`) and stack mechanics are defined in [architecture.md § Navigation 3](architecture.md#navigation-3). This document defines `PickerPurpose` ([section 7.1](#71-purposes)), which architecture.md delegates here.
- Composables named here live in the packages given in [architecture.md § Package layout](architecture.md#package-layout): shared composables (`NoteRowItem`, `EntityPicker`, `MonthGrid`) in `ui/common`, gate screens in `ui/gate`, lists in `ui/notes`, the editor in `ui/editor`, Settings in `ui/settings`.
- Every `data class` in `ui` overrides `toString()` and prints ids, counts and enum names only, never names or text (DEC-70; [architecture.md § Layering](architecture.md#layering-and-data-flow), rule 4, testing.md I-39). The sketches in this document show the override only where it is not obvious. The exception is the key classes of `ui/navigation` (including the `PickerPurpose` cases), which keep the generated `toString()` ([architecture.md § Keys and stacks](architecture.md#keys-and-stacks)).

## 1. Screen map

```
MainActivity (FragmentActivity) -> DoroTheme -> DoroRoot: one screen per gate state (architecture.md § Rendering)
|
+-- Gate screens: no note-derived data (DEC-66), section 10
|   +-- Starting ............ held system splash, then a blank surface
|   +-- NeedsPassword ....... PasswordScreen --"Forgot password?"--> ForgotPasswordScreen --> EraseDialog (typed confirmation)
|   +-- Locked .............. LockScreen ("Unlock") + BiometricPrompt or device-credential screen (system UI)
|   +-- Opening ............. blank surface + delayed progress | PasswordScreen (busy) | OpenErrorScreen ("Try again")
|   +-- Corrupt ............. CorruptScreen --> Restore flow dialogs | StartFreshDialog
|   +-- KeyUnavailable ...... KeyUnavailableScreen --> Restore flow dialogs | StartFreshDialog
|   +-- Maintenance ......... origin Ready: MaintenanceOverlay over the shell; other origins: MaintenanceScreen
|
+-- Ready: app shell = NavigationSuiteScaffold (section 2)
    |
    +-- Tab "All" ........ NoteListScreen(All)
    +-- Tab "People" ..... NoteListScreen(Individual): EntityPicker body until a person is chosen
    +-- Tab "Projects" ... NoteListScreen(Project):    EntityPicker body until a project is chosen
    +-- Tab "Calendar" ... NoteListScreen(Date): MonthGrid + day or range list
    +-- Tab "Orphans" .... NoteListScreen(Orphan)
    |      every NoteListScreen: search bar, filter chips, date filter sheet, selection mode,
    |      batch dialogs, snackbars (sections 3-5)
    |
    +-- EditorKey ........ EditorScreen (section 6)
    |      date picker dialog, unlink sheet, delete-note dialog, entity delete impact dialog
    +-- PickerKey ........ EntityPicker, full screen (section 7)
    +-- SettingsKey ...... SettingsScreen (section 9)
           backup and restore dialogs, encryption and password dialogs, open-source notices
```

- The navigation bar or rail is shown only while the top entry is a `TabKey`; the editor, pickers and Settings cover the whole window (DEC-50).
- Editor, picker and Settings entries are pushed onto the stack of the tab they were opened from.
- Dialogs, sheets and menus are not navigation entries. They are composed by the screen that owns them, so they leave composition with it when the gate locks (DEC-66) and keep `SecureFlagPolicy.Inherit` (DEC-67).

## 2. App shell

The shell is what `DoroRoot` composes in `Ready` (and under the overlay in `Maintenance` with origin `Ready`): `NavigationSuiteScaffold` around `NavDisplay` ([architecture.md § Navigation 3](architecture.md#navigation-3)).

### 2.1 Navigation suite and tabs

- `NavigationSuiteScaffold` from `material3-adaptive-navigation-suite` with an explicit `layoutType` (DEC-50): `NavigationSuiteType.NavigationBar` when the window width class is compact, `NavigationSuiteType.NavigationRail` otherwise, and `NavigationSuiteType.None` whenever the top entry is not a `TabKey` (architecture.md). The width class comes from `currentWindowAdaptiveInfo().windowSizeClass`. The library default `NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo` is not used, because it also chooses the bar for compact height and tabletop posture, which DEC-50 does not (a phone in landscape shows the rail). Verify at setup: the `layoutType` parameter and the width-class API of the pinned material3-adaptive-navigation-suite.
- Five items in §2 order. Start destination All (DEC-50). The labels are the Q10 default; changing them is a string-only change.

| Order | `Perspective` | Label (key) | Icon, unselected / selected |
|---|---|---|---|
| 1 | `All` | "All" (`tab_all`) | `ic_notes` / `ic_notes_filled` |
| 2 | `Individual` | "People" (`tab_people`) | `ic_group` / `ic_group_filled` |
| 3 | `Project` | "Projects" (`tab_projects`) | `ic_folder` / `ic_folder_filled` |
| 4 | `Date` | "Calendar" (`tab_calendar`) | `ic_calendar_month` / `ic_calendar_month_filled` |
| 5 | `Orphan` | "Orphans" (`tab_orphans`) | `ic_link_off` / `ic_link_off` |

- Labels are always shown (`alwaysShowLabel = true` on the bar), single line, with ellipsis at large font sizes. Icons are decorative (`contentDescription = null`); the label names the item.
- Tapping another tab calls `Navigator.selectTab(p)`. Each tab keeps its own stack, ViewModel, list scroll position, filters and search text (DEC-50).
- Leaving a tab clears its selection (DEC-36, section 5.5). Tapping the tab that is already current scrolls its list to the top (animated) and changes nothing else. Neither event can be derived from composition or lifecycle: a tab that is not current is not passed to `NavDisplay`, and `onDispose` or `ON_STOP` also fire on rotation, on a lock and while a picker covers the list, all of which must keep the selection (section 5.5). Both are explicit counters:
  - `NavigationState` keeps two saveable `IntArray`s indexed by `Perspective`: `leaves` and `reselects`. `Navigator.selectTab(p)` increments `leaves[current]` when `p != current` and `reselects[p]` when `p == current`. Back from the root of a tab other than All increments `leaves[current]` ([architecture.md § Back behavior](architecture.md#back-behavior)).
  - The `TabKey` entry passes `leaveCount = leaves[p]` and `reselectCount = reselects[p]` to `NoteListScreen`.
  - `LaunchedEffect(leaveCount) { vm.onTabLeft(leaveCount) }`: the ViewModel keeps `handledLeaves` in its `SavedStateHandle` (section 3.10) and clears the selection only when `leaveCount > handledLeaves`.
  - `LaunchedEffect(reselectCount)`: when `reselectCount` exceeds a `rememberSaveable` handled value, the list (in Individual and Project without a chosen entity, the embedded picker list) animates to item 0.
  - Neither effect uses `onDispose` or `ON_STOP`. The counters are part of `NavigationState` ([architecture.md § Keys and stacks](architecture.md#keys-and-stacks)) and start again with it after a data reset.
- Icon assets: section 13.5.

### 2.2 Top app bar of a perspective

Every perspective screen has an M3 `TopAppBar` (small, pinned scroll behavior so the container tints while the list is scrolled) with a title, a Search action and a Settings action (DEC-51).

| Element | Content | Behavior |
|---|---|---|
| Title | Per perspective, section 4: "All notes", "People" or the chosen person's name, "Projects" or the chosen project's name, "Calendar", "Orphans" | One line, ellipsis. In Individual and Project with an entity chosen, the title is a button (section 4.2) |
| Search action | `ic_search`, content description "Search" (`action_search`) | Turns the bar into the search bar (section 3.3). In Individual and Project while the picker body is shown, it moves focus to the picker's own name search field instead (section 4.2), so the action exists on every perspective (DEC-51, testing.md I-38) |
| Settings action | `ic_settings`, content description "Settings" (`action_settings`) | `Navigator.open(SettingsKey)` |

- No navigation icon on a tab root.
- In selection mode the bar is replaced by the contextual bar (section 5.2); in search mode by the search bar (section 3.3).
- The screen root sets `Modifier.semantics { paneTitle = <title text> }`, so TalkBack announces the screen when it changes. Every full-screen destination (editor, picker, Settings, gate screens) does the same with its own title.

### 2.3 Create button

- An M3 `FloatingActionButton` (56 dp) with icon `ic_add` ("+", §3) and content description "Create new note" (`fab_create_note`, verbatim §4), in the end-bottom position of the list screen's inner `Scaffold` (`FabPosition.End`). The Scaffold places it above the navigation bar and the system navigation bar.
- Shown on all five perspectives, including the picker body of Individual and Project (where it creates a note without prefill). Hidden in selection mode (DEC-36). The editor, pickers and Settings have no create button.
- Tap: `Navigator.open(EditorKey(noteId = null, prefill = <prefill>))`, with the prefill rule of section 6.7 (DEC-38).
- Lists keep 88 dp of bottom content padding while the button is visible (56 dp button + 16 dp margin + 16 dp gap), so the last row can be scrolled clear of it. The embedded `EntityPicker` list of Individual and Project, where the button is shown, gets the same 88 dp.

### 2.4 Snackbars and notices

- The shell owns one `SnackbarHostState` (`appSnackbar`) and provides it through `LocalAppSnackbar`. Only the screen of the top entry renders the host: `NavigationState` provides `LocalIsTopEntry` in the content of every `NavEntry`, true only for the entry whose key equals `currentStack.last()`, and every screen's `Scaffold` uses `snackbarHost = { if (LocalIsTopEntry.current) SnackbarHost(appSnackbar) }`. A popped entry is already off the stack during its exit animation, and during an uncommitted predictive-back gesture the top entry is unchanged, so a snackbar is never drawn twice. Gate screens never show snackbars; they use inline text and dialogs.
- A screen's own messages are UiState fields (`message: UserMessage?`) cleared through `onMessageShown()` after `showSnackbar` returns (architecture.md § Layering, rule 7). If the screen leaves composition before that, the message is shown again when the screen returns.
- `UserMessage` is the only message type of UiStates and of `AppMessages` (package `core.messages`, [architecture.md § Package layout](architecture.md#package-layout)). It can carry resource-derived text only, so user data cannot enter a message (DEC-70):

  ```kotlin
  sealed interface UiText {
      data class Res(@StringRes val id: Int, val args: List<UiText> = emptyList()) : UiText  // args fill %1$s, %2$s in order
      data class Plural(@PluralsRes val id: Int, val count: Int) : UiText                     // the count is the only argument
      data class Error(val error: AppError) : UiText                                          // AppError.messageRes(), section 11.13
  }
  data class UserMessage(val id: Long, val text: UiText, val isError: Boolean) {
      override fun toString() = "UserMessage(id=$id)"                                         // DEC-70; every UiText case prints ids and counts only
  }
  ```

  `id` comes from a process-wide `AtomicLong`. The snackbar duration is `Long` when `isError`, else `Short`. Arguments are only nested `UiText`, never raw strings. The text is resolved with `stringResource` or `pluralStringResource` when it is shown, in the current locale.
- Messages that must outlive the screen that caused them go to `AppMessages`, an application-scoped member of `AppContainer` exposed through `UiGraph` and `GateGraph` ([architecture.md § Dependency injection](architecture.md#dependency-injection)): `val pending: StateFlow<List<UserMessage>>`, `fun post(message: UserMessage)`, `fun consume(id: Long)`. The shell shows the head of `pending` in `appSnackbar` and consumes it after `showSnackbar` returns. Users: the editor after it closed itself ("1 note deleted", "This note no longer exists.", a failed save on leave, section 6.8). It is state, not a `Channel`, so a message survives configuration changes (architecture.md § Layering, rule 7).
- Snackbars carry counts and fixed texts only, never note text, names, search text or file names ([security.md § 8](security.md#8-screen-protection-dec-66-dec-67)). They have no action button (no undo, §7, DEC-36). Success messages use `SnackbarDuration.Short`; error messages use `SnackbarDuration.Long`.
- Maintenance outcomes (backup, restore, key change, Start fresh) are published by `DbMaintenance.status` and rendered by the maintenance status host (section 8.12), not by the screen that started the operation, because a restore resets navigation (architecture.md § Threading). Outcome texts: section 9.6.
- Three notices need acknowledgement and therefore show as an `AlertDialog` on top of the shell, once, as soon as the shell is composed: the Start-fresh notice (section 10.7, DEC-85), the restore encryption-off notice (section 8.7, DEC-86) and the biometric-lock auto-off notice (section 9.4, [security.md § 7.6](security.md#76-device-credential-fallback-q7)). The first two are rendered by the maintenance status host (section 8.12) from `DbMaintenance.status`; the third by the shell from the settings flow that `MainContent` already reads for `dataEpoch` ([architecture.md § Data reset](architecture.md#data-reset)).

### 2.5 Back behavior

Stack-level Back (pop an entry, other tab to All, exit from the root of All) is defined in [architecture.md § Back behavior](architecture.md#back-behavior) (DEC-50). On a perspective screen, in-screen states take Back first, in this order; each uses `BackHandler(enabled = <only while that state is active>)`, so predictive back stays intact elsewhere (DEC-01, DEC-13):

| Priority | State | Back does |
|---|---|---|
| 1 | A dialog, sheet, menu or date picker is open | Closes it (Compose window default) |
| 2 | Selection mode (section 5) | Exits selection mode; nothing else changes |
| 3 | Search bar open (section 3.3) | Closes the search bar and clears the search text |
| 4 | none of the above | Stack-level behavior (architecture.md) |

- The Date perspective's range mode is not an in-screen Back state: Back from Calendar goes to All and the range stays selected (section 4.4).
- The editor closes on Back and saves, with no confirmation (section 6.8). A picker closes without returning a result. Settings closes. Gate screens: section 10.
- The maintenance overlay and the standalone maintenance screen consume Back (architecture.md § Rendering, section 10.6).

## 3. Shared note list

All five perspectives are one composable, `NoteListScreen`, driven by one `NoteListViewModel` per tab and parameterized by a `PerspectiveSpec` (component names: decisions.md). Every list shows rows from the single perspective query in the canonical order (DEC-23, [data-layer.md § Perspective query](data-layer.md#perspective-query)); the UI never sorts, groups or reorders rows.

### 3.1 Layout

Top to bottom inside the list screen's `Scaffold`:

1. Top app bar (section 2.2), or the search bar (section 3.3), or the contextual selection bar (section 5.2).
2. Filter chip row (section 3.4).
3. Date only: `MonthGrid` and the selection header (section 4.4).
4. The list area: loading, empty or rows (sections 3.6 and 3.7). A `LazyColumn` with items keyed by note id.
5. The create button, floating at the bottom end (section 2.3), and the snackbar host (section 2.4).

In Individual and Project, items 2–4 are replaced by the `EntityPicker` body until an entity is chosen (section 4.2).

### 3.2 PerspectiveSpec

`PerspectiveSpec` is an immutable value in `ui/notes` that holds everything that differs between perspectives. It contains no state; `NoteListViewModel` combines it with the saved state.

```kotlin
enum class EntityKind { Person, Project }            // model package; also used by PickerKey (architecture.md)
enum class FilterDimension { Person, Project, Date } // chip order follows the list order in the spec

data class PerspectiveSpec(
    val perspective: Perspective,
    @StringRes val tabLabel: Int,
    @StringRes val title: Int,             // shown when no entity name replaces it
    val primaryKind: EntityKind?,          // Individual: Person; Project: Project; others: null
    val filters: List<FilterDimension>,    // allowed secondary dimensions (§2, DEC-28)
    val offersUndated: Boolean,            // "Undated" in the date filter sheet (§1, DEC-28)
    val showsMonthGrid: Boolean,           // Date only (DEC-53)
) {
    override fun toString() = "PerspectiveSpec($perspective)"
    companion object { fun of(p: Perspective): PerspectiveSpec }
}
```

| `Perspective` | Title (key) | `primaryKind` | `filters` | `offersUndated` | Prefill of a new note (DEC-38) | Summary omits |
|---|---|---|---|---|---|---|
| `All` | "All notes" (`title_all`) | null | Person, Project, Date | yes | nothing | nothing |
| `Individual` | "People" (`tab_people`), or the person's name | `Person` | Project, Date | yes | the chosen person | the chosen person |
| `Project` | "Projects" (`tab_projects`), or the project's name | `Project` | Person, Date | yes | the chosen project | the chosen project |
| `Date` | "Calendar" (`tab_calendar`) | null | Person, Project | no Date chip at all | the selected day; nothing in range mode | nothing |
| `Orphan` | "Orphans" (`tab_orphans`) | null | Date | yes | nothing | nothing (rows show "Orphaned") |

- The table is the §2 "Secondary Filters Allowed" column. Search is offered in all five perspectives (DEC-28) through the app bar, never as a chip.
- `NoteFilter` assembly: the primary (remembered entity id, or the Date selection) goes into `primaryId` / `primaryDays`; the chips and the search text fill the secondary fields. Fields a perspective does not offer stay at their defaults, and `toQuery()` drops them anyway ([data-layer.md § Perspective to NoteQuery mapping](data-layer.md#perspective-to-notequery-mapping)).

### 3.3 Search bar

- The Search action (section 2.2) replaces the top app bar with a search bar. It is its own composable, not the title slot of a `TopAppBar`, whose fixed 64 dp content height would clip the field at 200 % font: `Surface(color = <top app bar container color>) { Row(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { <close button>; TextField(Modifier.weight(1f), <transparent container, no indicator>); <clear button> } }`. It is 64 dp high at 100 % font and grows with the font size. Content: navigation icon `ic_arrow_back` ("Close search", `action_close_search`), a single-line `TextField` with placeholder "Search notes" (`search_hint`), and a trailing `ic_close` icon button ("Clear search text", `action_clear_search`) while the text is not empty. The filter chips stay visible below.
- Opening the bar focuses the field and shows the keyboard. Keyboard options: `KeyboardType.Text`, `ImeAction.Search`, `autoCorrectEnabled = false`, `KeyboardCapitalization.None`. The field is always wrapped in `NoPersonalizedLearning(enabled = LocalKeyboardPrivacy.current)` ([security.md § 11](security.md#11-keyboard-and-autofill-dec-71-q11)). `MainActivity` provides `LocalKeyboardPrivacy` from the same `windowPolicy` flow that sets `FLAG_SECURE`, so composables never read `SettingsStore` (architecture.md § Layering, rule 1), and the wrapper never appears or disappears, which would move the field in the composition and lose its focus and state.
- The field shows what was typed, capped at `SearchText.MAX_TERM_CHARS` (1,000) characters, surrogate pairs kept whole and without a message ([data-layer.md § Search](data-layer.md#search); the cap keeps the saved state small, section 3.10). `NoteListViewModel` stores the raw text at once (`searchText`) and copies it into `NoteFilter.search` after 300 ms without further input (debounce). Trimming, case folding and wildcard escaping happen in `SearchText.toLikeTerm` ([data-layer.md § Search](data-layer.md#search)); input that is blank after trimming means no search filter (DEC-29). The ImeAction, the clear button and closing the bar apply at once, without the debounce.
- The ImeAction hides the keyboard and keeps the bar open. The clear button empties the field and keeps focus.
- Closing the bar (navigation icon or Back, section 2.5) clears the text and the search filter. A search filter applies only while the bar is open, or while selection mode has temporarily replaced an open bar (section 5.1), so the user always sees or returns to the active query.
- Search text and the open state are saved per perspective (section 3.10). After process death the bar reopens with its text, without focus or keyboard.
- Matching covers note content only, never person or project names, and checked notes stay in the results in canonical order (§8, DEC-29).

### 3.4 Filter chip row

- A horizontally scrollable `Row` directly below the app bar, 16 dp side padding, 8 dp between chips, one M3 `FilterChip` per dimension in `PerspectiveSpec.filters` order. It is hidden while the `EntityPicker` body is shown.
- Chip content:

| State | Label | Leading icon | Trailing icon |
|---|---|---|---|
| Not set | "Person" / "Project" / "Date" (`filter_person`, `filter_project`, `filter_date`) | none | `ic_arrow_drop_down` |
| Set | The person or project name; or the date text: "Undated" (`filter_date_undated`), "17 Nov 2026 – 23 Nov 2026" (`date_range_both`), "From 17 Nov 2026" (`date_range_from`), "Until 23 Nov 2026" (`date_range_until`) | `ic_person` / `ic_folder` / `ic_event` | `ic_arrow_drop_down` |

- `selected = <set>`, so a set chip uses the selected chip colors (container plus leading icon, not color alone). Labels are one line with ellipsis.
- Semantics: the chip replaces its default checkbox semantics with `Modifier.clearAndSetSemantics { role = Role.DropdownList; contentDescription = …; onClick(label = …) { … } }`. The content description is "Person filter: %1$s" (`a11y_filter_person`; `a11y_filter_project` and `a11y_filter_date` for the other chips) with the displayed value, or with "Any" (`filter_any`) when not set. The click label is "Change filter" (`a11y_change_filter`).
- Tap: Person and Project open the single-select `EntityPicker` (`PickerKey(kind, PickerPurpose.SecondaryFilter(...))`, section 7). Its first row, "Any person" / "Any project", clears the dimension. Date opens the date filter sheet (section 3.5).
- A filter whose person or project is deleted is cleared automatically (DEC-34; existence probe in [data-layer.md § Reactivity](data-layer.md#reactivity)).
- At most one person and one project (DEC-28). Every change clears the selection (DEC-36); in selection mode the chips are disabled (section 5.1).

### 3.5 Date filter sheet

A `ModalBottomSheet` (`skipPartiallyExpanded = true`) with the title "Filter by date" (`date_filter_title`, a heading) and these rows:

| Row | Control | Shown |
|---|---|---|
| "Any date" (`date_filter_any`) | `RadioButton` row | always |
| "Undated" (`filter_date_undated`) | `RadioButton` row | when `offersUndated` |
| "Date range" (`date_filter_range`) | `RadioButton` row | always |
| "From" (`date_filter_from`) | Clickable row showing the date (MEDIUM) or "Any" (`filter_any`); trailing `ic_close` "Clear start date" (`action_clear_from`) when set | always, indented under "Date range" |
| "Until" (`date_filter_until`) | Same, with "Clear end date" (`action_clear_until`) | always, indented under "Date range" |
| "Done" (`action_done`) | `TextButton`, bottom end | always |

Layout and semantics:
- The title and the rows sit in a `Column(Modifier.verticalScroll(rememberScrollState()))` so the sheet stays usable at 200 % font and in landscape; "Done" stays below the scrolling column.
- The three radio rows sit in a `Column(Modifier.selectableGroup())`. Each is `Modifier.selectable(selected, role = Role.RadioButton, onClick).heightIn(min = 56.dp)` with `RadioButton(selected, onClick = null)` and the label `Text`, so the whole row is the touch target and TalkBack announces one node. The "From" and "Until" rows are `clickable(role = Role.Button)` nodes whose state description is the date or "Any" (`filter_any`).

Rules:
1. Every change applies at once; the list behind the sheet updates. "Done", a swipe down, a scrim tap or Back closes the sheet.
2. Selecting "Any date" or "Undated" clears both range ends.
3. Tapping "From" or "Until" opens a `DatePickerDialog` with a `DatePicker` (`showModeToggle = true`), initially showing that end's date, else today. Buttons: "Cancel" (`action_cancel`) and "OK" (`action_ok`, enabled only while a day is selected). OK sets that end and selects "Date range".
4. Tapping the "Date range" radio while both ends are empty opens the "From" picker. If it is cancelled, the previous choice stays selected.
5. Clearing one end keeps an open-ended range (DEC-28). Clearing the last remaining end returns to "Any date".
6. The "From" picker disables days after the current "Until", and the "Until" picker disables days before the current "From" (`SelectableDates`), so a reversed range cannot be entered.
7. A range is inclusive at both ends and never contains undated notes (DEC-28).
8. Every date picker in the app converts with `ZoneOffset.UTC` (DEC-26): read `getSelectedDate()` (or `selectedDateMillis` through `Instant.atZone(ZoneOffset.UTC).toLocalDate()`), store `toEpochDay()`; initialise from `LocalDate.ofEpochDay(day)`. Year range: `DatePickerDefaults.YearRange` (1900–2100).

### 3.6 Loading, empty and error states

| State | Condition | List area shows |
|---|---|---|
| Loading | No emission yet for the first `NoteQuery` of this entry (`loading = true`) | Nothing for 500 ms, then a centered indeterminate `CircularProgressIndicator` with content description "Loading" (`a11y_loading`). Never "(No data)" (architecture.md § Layering, rule 2) |
| Rows | The latest emission has ≥ 1 row | The rows (section 3.7) |
| Empty | The latest emission has 0 rows | "(No data)" (`no_data`, verbatim §5) in `bodyLarge`, `onSurfaceVariant`, centered in the list area. Below it, only while `filtersActive`, a `TextButton` "Clear filters" (`action_clear_filters`) |

- `loading` is true before the first emission of a query for the current primary. When the primary changes (the Individual or Project entity, the Calendar day or the range), `rows` is cleared and `loading` becomes true until the new query's first emission, so rows of another entity or day are never shown under the new title or header. Only secondary filter and search changes keep the previous rows until the new emission arrives, so nothing flashes.
- "(No data)" depends only on the row count of the fully filtered query (DEC-25, Q4). A note with blank content is a row, not an empty state (section 3.7).
- `filtersActive` = a secondary person or project filter is set, the date filter is not "Any", or the search text is not blank. The primary selection (person, project, Calendar day or range) never counts.
- "Clear filters" clears the person, project and date filters, empties the search text and closes the search bar. It never touches the primary selection.
- In Date, the empty state is placed under the `MonthGrid` and the selection header, top-aligned (section 4.4).
- A failed write started from a list (done toggle, batch action) shows its `AppError` text (section 11.13) in a Long snackbar. The rows are untouched because the database is the only source of row state (architecture.md § Layering, rule 3).

### 3.7 Row anatomy (`NoteRowItem`)

```
+---------------------------------------------------------------------+
| [ ]  Title: first non-blank line of the preview            (o)/(v)  |
|      Body: next non-blank lines, at most 2 lines ...               |
|      17 Nov 2026   Overdue   (person) Alice, Bob  (folder) Alpha    |
+---------------------------------------------------------------------+
 checkbox                                         selection mark (selection mode only)
```

`NoteRowItem` is built on M3 `ListItem` (one-, two- or three-line layout chosen by its content) and receives a `NoteRowUi`:

```kotlin
data class NoteRowUi(
    val id: Long,
    val title: String?,          // null = blank content: render "(No data)" (DEC-25)
    val body: String?,           // null = no further lines
    val checked: Boolean,
    val date: LocalDate?,        // LocalDate.ofEpochDay(row.date)
    val overdue: Boolean,        // DEC-26, computed in the ViewModel with AppTime.todayFlow
    val people: List<String>,    // row.persons.filter { it.id != primaryPersonId }.map { it.name }, row order kept
    val projects: List<String>,  // row.projects.filter { it.id != primaryProjectId }.map { it.name }, row order kept
    val orphan: Boolean,         // row.orphan (DEC-27)
) { override fun toString() = "NoteRowUi(id=$id)" }   // DEC-70
```

| Part | Rule | Style |
|---|---|---|
| Checkbox (leading) | M3 `Checkbox(checked = row.checked, onCheckedChange = { vm.setChecked(row.id, it) }, modifier = Modifier.semantics { contentDescription = <a11y_mark_not_done if checked, else a11y_mark_done>; stateDescription = <state_done if checked, else state_not_done> })` in a 48 × 48 dp box, always visible and enabled in every mode (§4, DEC-54); top-aligned with the title. It is not part of the row's merged text node and sits outside the row's `combinedClickable` semantics (section 12.1) | default colors |
| Title (headline) | The first line of `preview` that is not blank, trimmed (DEC-39). If `preview.isBlank()`: "(No data)" (`no_data`, DEC-25). The preview starts at the first non-whitespace character of the content ([data-layer.md § Rows](data-layer.md#rows)), so `preview.isBlank()` holds exactly when the content is blank | `titleMedium`, 1 line, ellipsis. "(No data)" in `onSurfaceVariant`, italic. Checked: `onSurfaceVariant` with `TextDecoration.LineThrough` |
| Body (supporting) | The non-blank lines after the title line, each trimmed, joined with `\n`; omitted when there are none. Markdown is shown as typed (DEC-39) | `bodyMedium`, `onSurfaceVariant`, at most 2 lines, ellipsis |
| Date | `date` formatted with `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)` for the current configuration locale (DEC-26); omitted when undated | `labelLarge`, `onSurfaceVariant`; `error` color when overdue |
| Overdue | The text "Overdue" (`label_overdue`, verbatim §4) right after the date when `overdue`; never on checked or undated notes (DEC-26, Q5) | `labelLarge`, `error` color, preceded by `ic_error` (16 dp, decorative): text plus icon plus color, never color alone |
| Association summary | `people` joined with ", " (`names_separator`), then `projects` joined the same way. Each group is preceded by an inline 16 dp icon (`ic_person`, `ic_folder`; decorative). Omitted when both lists are empty | `labelLarge`, `onSurfaceVariant`, 1 line, ellipsis. Built as one `AnnotatedString` with inline icons so it ellipsizes as one text |
| Orphaned | Replaces the summary when `orphan` (DEC-27): `ic_link_off` (16 dp, decorative) plus "Orphaned" (`label_orphaned`, verbatim §6). Shown in every perspective where an unlinked row can appear (All, Date, Orphan) | `labelLarge`, `onSurfaceVariant` |
| Selection mark (trailing) | Selection mode only: `ic_check_circle_filled` in `primary` when selected, `ic_radio_button_unchecked` in `outline` when not (decorative) | 24 dp |

- Date, Overdue and the summary sit in one `FlowRow` (12 dp horizontal and 4 dp vertical spacing). A summary that does not fit next to the date wraps to its own line and ellipsizes there. The `FlowRow` is omitted when it would be empty.
- Individual and Project rows always carry their primary entity, which the summary omits. If nothing else remains, the row shows no summary and no "Orphaned" (the note is linked).
- Names in the summary keep the row order from the query (`Collator`, then id, DEC-32), people before projects. Duplicate names are shown as they are.
- Selected rows (selection mode) use `ListItemDefaults.colors(containerColor = secondaryContainer)` plus the filled check mark (DEC-54).
- Minimum row height 72 dp; no dividers.

### 3.8 Row interactions

The row container uses `Modifier.combinedClickable` (it merges the row's text into one accessibility node). The checkbox keeps its own node.

| Input | Normal mode | Selection mode |
|---|---|---|
| Tap on the row | Open the editor: `Navigator.open(EditorKey(noteId = row.id))`. `onClickLabel` "Open note" (`a11y_open_note`) | Toggle this row's selection (DEC-54). `onClickLabel` "Select note" / "Deselect note" (`a11y_select_note`, `a11y_deselect_note`) |
| Long-press on the row | Enter selection mode with this row selected, with `HapticFeedbackType.LongPress`. `onLongClickLabel` "Select note" (DEC-36) | Same as a tap, with `onLongClickLabel` "Select note" / "Deselect note" (`a11y_select_note`, `a11y_deselect_note`) following the row's selection state |
| Tap on the checkbox | Mark done or not done (section 3.9) | Same; it never changes the selection (DEC-54) |
| Accessibility custom action on the row node, labelled "Mark note as done" / "Mark note as not done" (`a11y_mark_done`, `a11y_mark_not_done`) | Same as the checkbox | Same as the checkbox |

- There is no swipe gesture on rows.
- A note that disappears while its row is being tapped (deleted elsewhere, a restore) is handled by the editor, which closes with "This note no longer exists." (`note_gone`, section 6.8).

### 3.9 Done toggle: write, move and announcement

1. The checkbox calls `NoteListViewModel.setChecked(row.id, checked = !row.checked)`, which calls `NoteRepository.setChecked(noteId, checked)` with the target state, never "toggle" (DEC-22, [data-layer.md § Check and uncheck](data-layer.md#check-and-uncheck-dec-22)). A double tap therefore sends the same target twice and changes the note once.
2. The UI does not change the row itself. Room re-emits the list and the checkbox, the styling and the position change from the new emission (architecture.md § Layering, rule 3).
3. Rows animate to their new place with `Modifier.animateItem()` (DEC-54): checking moves the note to the very bottom of the list (the last position of the checked group), unchecking moves it to the top of the list (§4, DEC-23).
4. Scroll anchoring: `LazyColumn` normally follows its first visible item by key, so toggling that item would scroll the viewport with it. When the toggled row is the first visible item, the screen records `firstVisibleItemIndex` and `firstVisibleItemScrollOffset` before the write. When the next `rows` value arrives it calls `listState.requestScrollToItem(index, offset)` in the same composition that hands the new rows to the `LazyColumn`: the call is non-suspending and overrides key anchoring for that one remeasure, so the data change and the restored position land in the same frame, with no jump. The viewport stays put and the row animates away. Verify at setup: `LazyListState.requestScrollToItem` in the pinned foundation, and that `animateItem()` still animates the moved row.
5. When `setChecked` returns true, the screen announces "Marked as done" or "Marked as not done" (`announce_marked_done`, `announce_marked_not_done`) through the `Announcer` helper in `ui/common`, which wraps `View.announceForAccessibility`. Verify at setup: `announceForAccessibility` is deprecated from API 36; on a targetSdk 36 build, switch the helper to a polite live region and keep the call sites unchanged (DEC-01).
6. There is no undo (§7, DEC-36). Unchecking always sets a fresh `updated_at` (DEC-21), so an undo could not restore the previous position.

### 3.10 List state and saved state

`NoteListViewModel` exposes one `StateFlow<NoteListUiState>` (architecture.md § Layering):

```kotlin
data class NoteListUiState(
    val spec: PerspectiveSpec,
    val loading: Boolean,
    val primary: Primary,                // Loading | None | Entity(id, name) | Day(epochDay) | Range(fromDay, toDay)
    val rows: List<NoteRowUi>,
    val chips: List<FilterChipUi>,       // one per spec.filters entry, with its display value
    val filtersActive: Boolean,
    val searchOpen: Boolean,
    val searchText: String,
    val selection: Set<Long>?,           // null = not in selection mode
    val calendar: CalendarUi?,           // Date only: displayed month, marked days, today, selection
    val dialog: ListDialog?,             // the open sheet or dialog of this screen
    val message: UserMessage?,
) { override fun toString() = "NoteListUiState(${spec.perspective}, rows=${rows.size})" }

enum class RangeEnd { From, Until }
sealed interface ListDialog {            // saved as the String key listDialog, below
    data object DateFilterSheet : ListDialog        // section 3.5
    data class DateEndPicker(val end: RangeEnd) : ListDialog   // the From or Until DatePickerDialog of section 3.5
    data object RangePicker : ListDialog            // the Calendar DateRangePicker, section 4.4.2
    data object SetDate : ListDialog                // batch date picker, section 5.3
    data object BatchDelete : ListDialog            // section 8.3
}
```

Saved per perspective in the entry's `SavedStateHandle` (DEC-28): it survives rotation and process death and starts empty on a cold start.

| Key | Type | Content |
|---|---|---|
| `personId`, `projectId` | `Long?` | Secondary person and project filters |
| `dateKind`, `dateFrom`, `dateTo` | `String`, `Long?`, `Long?` | Date filter: `"Any"`, `"Undated"` or `"Range"` with epoch days |
| `searchOpen`, `searchText` | `Boolean`, `String` | Search bar state and raw text |
| `selection` | `LongArray?` | Selected note ids; absent = not in selection mode. Written only while it holds at most 1,000 ids (budget below) |
| `day`, `rangeFrom`, `rangeTo`, `month` | `Long?` | Date only: selected day, or range ends; first day of the displayed month (epoch days) |
| `listDialog` | `String?` | The open `ListDialog`: its simple name, with the end for `DateEndPicker`, for example `"DateEndPicker:From"`; absent = none |
| `handledLeaves` | `Int` | The last `leaveCount` of section 2.1 this ViewModel has handled |

- The Individual and Project primary is not in the `SavedStateHandle`: it is `SettingsStore.rememberedPersonId` / `rememberedProjectId` (DEC-28, architecture.md § Dependency injection), cleared by the existence probe when the entity disappears.
- A restored `selection` is pruned to the ids of the first emission; if nothing remains, selection mode ends, and a restored `SetDate` or `BatchDelete` dialog is dropped with it.
- Every sheet and dialog of the list screen is a `ListDialog`, so each survives rotation and process death (section 8.1). The selection inside an open `DatePicker` is UI-local (`rememberDatePickerState` is saveable) and not part of the ViewModel. Overflow `DropdownMenu`s are UI-local and are not restored after process death.
- Saved-state budget: the whole activity state stays below 500 KB, because it crosses Binder in `onStop` and a transaction above about 1 MB raises `TransactionTooLargeException` (API 24+), which could lose the `ON_STOP` draft save. Hence the 1,000-id limit on `selection` (a larger selection survives rotation in the ViewModel but not process death) and the cap on the search text (section 3.3). The editor draft, the largest entry, is budgeted in section 6.14.

## 4. Perspective specifics

### 4.1 All

- Title "All notes" (`title_all`). Every note, in canonical order. §2 calls this a "timeline": it is the recency order of DEC-23; there is no date-ordered mode (spec errata).
- Chips: Person, Project, Date (with "Undated"). New notes start empty (DEC-38).

### 4.2 Individual and Project

The primary entity is `SettingsStore.rememberedPersonId` (Individual) or `rememberedProjectId` (Project), so it survives a cold start (DEC-28).

Before anything can be decided the primary is `Primary.Loading`. It holds until `SettingsStore.settings` has emitted (it is null until the first read) and, when an id is remembered, until the existence probe's first emission. While `Loading` the title is the tab title without a dropdown, the chips are hidden, and the body shows nothing for 500 ms and then the centered progress indicator (`a11y_loading`). The picker body below appears only when no id is remembered or the probe emitted null, so it never flashes before a remembered entity is known.

Nothing chosen (no remembered id, or the existence probe found the entity gone, [data-layer.md § Reactivity](data-layer.md#reactivity)):
- The title is "People" or "Projects", with no dropdown.
- The body below the app bar is the `EntityPicker` in its embedded single-select form (DEC-52, section 7): name search field, every entity including those with zero notes (DEC-33), each with "N notes · added <date>", sorted by `Collator`, then id (DEC-32). No "Any" row and no create row: persons and projects are created only from a note (§3).
- No entities at all: "(No data)" (`no_data`) and below it the hint "People are created in a note: tap + and use Add person." (`picker_empty_people_hint`) or "Projects are created in a note: tap + and use Add project." (`picker_empty_projects_hint`). A name search that matches nothing shows "(No data)" alone.
- The Search action focuses the picker's name search field (section 2.2). The filter chips are hidden. The create button is shown and creates a note without prefill.
- Tapping an entity stores its id in `SettingsStore` and shows its notes.

An entity chosen:
- The title is the entity name followed by `ic_arrow_drop_down`. The whole title is one button (`Modifier.clickable(role = Role.Button, onClickLabel = "Change person" / "Change project")`, keys `a11y_change_person`, `a11y_change_project`; at least 48 dp high) that opens `PickerKey(kind, PickerPurpose.PerspectivePrimary(selectedId))` (section 7). Picking another entity replaces the remembered id; Back keeps the current one (DEC-52).
- The list holds every note linked to the entity, with the secondary chips (Individual: Project, Date; Project: Person, Date, §2). Row summaries omit the primary entity (section 3.7).
- The secondary picker puts co-linked entities first: the Project chip of Individual passes `coLinkedTo = personId`, the Person chip of Project passes `coLinkedTo = projectId` (section 7.4, [data-layer.md § Editor loads, pickers and existence probes](data-layer.md#editor-loads-pickers-and-existence-probes)). This is how the Project perspective shows "everyone" related to a project (§2) without a stored person–project relation.
- Changing the entity keeps the secondary filters and search text and clears the selection (DEC-36).
- New notes are prefilled with the entity (DEC-38).
- When the entity is deleted from an editor menu (DEC-34) or replaced by a restore (DEC-84), the remembered id is cleared and the picker body returns.

### 4.3 Orphan

- Title "Orphans" (`tab_orphans`). Every row shows "Orphaned" in place of the association summary (§6, DEC-27).
- Chips: Date (with "Undated"). Search through the app bar (§2 "content search").
- A note leaves the list on the next emission after any link is committed, from the editor or from batch associate (§6; §7 Batch Associate step 5). The selection is pruned to the remaining rows (section 5.5).
- New notes start empty (DEC-38). This is the main home of batch associate (§7), available as in every perspective (section 5).

### 4.4 Date (Calendar)

Title "Calendar" (`tab_calendar`). The primary filter is one day (day mode) or one closed range (range mode) (§2). Chips: Person, Project; no Date chip and no "Undated" (DEC-28).

Layout:
- Single column (the default): one `LazyColumn` whose first item is the `MonthGrid` (centered, at most 480 dp wide), second item the selection header, then the rows or the empty state. The grid scrolls away with the list.
- Two panes below the chip row when the window width is at least the expanded breakpoint (840 dp), or at least 600 dp while the window height is below 480 dp. The second case is a phone in landscape (DEC-01 forbids an orientation lock): there a single column puts the grid above the fold and the day list entirely below it, so every day tap would need a scroll (§2 Date UX 3). The start pane is 360 dp wide and holds the `MonthGrid` and the selection header in a vertically scrollable column; the end pane holds the list.
- Breakpoints come from `currentWindowAdaptiveInfo().windowSizeClass`, never from `Configuration` ([architecture.md](architecture.md)). Verify at setup: the breakpoint API of the pinned material3-adaptive (`isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)`, `isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)` and `!isHeightAtLeastBreakpoint(HEIGHT_DP_MEDIUM_LOWER_BOUND)`, or the `windowWidthSizeClass` and `windowHeightSizeClass` equivalents).

Initial and saved state:
- A fresh entry (cold start, DEC-28) opens on the current month in day mode with today selected (DEC-53); that list may be "(No data)".
- `day`, `rangeFrom`/`rangeTo` and `month` are saved (section 3.10), so rotation and process death keep the selection. When the date changes at midnight or with the time zone (`AppTime.todayFlow`), the today marker moves and the selection stays.

#### 4.4.1 MonthGrid

`MonthGrid` (`ui/common`, DEC-11) holds no state except its own `PagerState` (see Behavior); the displayed month, the selection and the marks are parameters:

```kotlin
@Composable
fun MonthGrid(
    month: YearMonth,               // displayed month
    today: LocalDate,               // from AppTime.todayFlow
    selection: DaySelection,        // Day(epochDay) | Range(fromDay, toDay)
    markedDays: Set<Long>,          // epoch days of the displayed month with matching notes
    firstDayOfWeek: DayOfWeek,
    onMonthChange: (YearMonth) -> Unit,
    onDayClick: (Long) -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
)
```

| Part | Specification |
|---|---|
| Header | Month title from `DateFormat.getBestDateTimePattern(locale, "MMMMy")` (for example "November 2026"), `titleMedium`, a heading with `liveRegion = Polite` so a month change is announced. Then a "Today" `TextButton` (`action_today`), shown when the displayed month is not the current month or day mode does not have today selected. Then `IconButton`s `ic_chevron_left` "Previous month" and `ic_chevron_right` "Next month" (`a11y_previous_month`, `a11y_next_month`; auto-mirrored in RTL). The header is a `FlowRow`, so at large font sizes the buttons wrap below the title |
| Weekday row | Seven `DayOfWeek.getDisplayName(TextStyle.NARROW, locale)` labels starting at `firstDayOfWeek`, `labelMedium`, `onSurfaceVariant`. Hidden from accessibility (`clearAndSetSemantics {}`) because every day cell announces its weekday |
| First day of week | `androidx.core.text.util.LocalePreferences.getFirstDayOfWeek(locale, /* resolved = */ false)` with `locale = LocalConfiguration.current.locales[0]` (the locale every other formatter uses, section 11.14; it honors the Android 14 regional preference). It returns a String: `"sun"` → `SUNDAY`, `"mon"` → `MONDAY`, `"tue"`, `"wed"`, `"thu"`, `"fri"`, `"sat"`; an empty result (no preference) falls back to `WeekFields.of(locale).firstDayOfWeek`. Computed in `remember(locale)`, so it is re-read when the configuration changes. Verify at setup: the return values in the pinned androidx.core, and that the regional preference appears as the `fw` extension of the configuration locale |
| Grid | Always 6 rows × 7 columns, so the height does not jump between months. Only the displayed month's days have cells; the other positions are empty and not focusable. Column width = max(48 dp, available width / 7); 8 dp padding on each side while the available width is at least 352 dp, none below. Row height = max(48 dp, the day text height + 16 dp, min(column width, 56 dp)), so at 100 % font the grid is at most 6 × 56 dp tall. When the available width is below 336 dp (seven 48 dp columns) the grid scrolls horizontally inside its container instead of shrinking its cells below 48 dp (DEC-55) |
| Day cell | Day number (`DateTimeFormatter.ofPattern("d", locale)`), `bodyLarge`, centered in a 40 dp circle area. A 6 dp dot below the number when the day is in `markedDays` |
| Today | 1 dp `primary` ring around the 40 dp circle; number bold in `primary` |
| Selected day (day mode) | Filled `primary` circle; number and dot in `onPrimary` |
| Range mode | Start and end days as filled `primary` circles; the days between them on a 40 dp high `primaryContainer` band that runs through their cells and joins the circles; numbers on the band in `onPrimaryContainer` |
| Marked day | The dot in `primary` (in `onPrimary` on a filled circle). A dot is a shape, so marks never rely on color alone (DEC-55) |

Behavior:
- Navigation between months: the arrows, and horizontal swipes through a `HorizontalPager` with one page per month from January 1900 to December 2100 (the `DatePickerDefaults.YearRange` used by every picker, section 3.5). `beyondViewportPageCount = 0`. The settled page is the displayed month; the arrows call `animateScrollToPage(page ± 1)`. Arrows are disabled at the ends of the range.
- Pager and `month` stay in sync without a loop: `MonthGrid` owns `rememberPagerState(initialPage = pageOf(month)) { 2412 }` with `pageOf(m) = (m.year − 1900) × 12 + m.monthValue − 1` (201 years × 12 months). `LaunchedEffect(month) { if (pagerState.settledPage != pageOf(month)) pagerState.animateScrollToPage(pageOf(month)) }` follows changes made by "Today", "Select range" or restored state. `LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.drop(1).collect { if (it != pageOf(month)) onMonthChange(monthOf(it)) } }` reports swipes and arrows. Pages other than the one for `month` render with an empty `markedDays`, so a page that is only passing by during a swipe shows no marks until it settles.
- Marks: `NoteRepository` observes the marked days of the settled month with `fromDay`/`toDay` = its first and last day and the person, project and search values of the Date perspective's query ([data-layer.md § Perspective query](data-layer.md#perspective-query), DEC-53). A day is marked when at least one note dated that day matches those filters, checked notes included, so a marked day never opens an empty list.
- Tapping a day selects it in day mode (also from range mode). "Today" shows the current month and selects today in day mode.
- 200 % font: cells grow taller to fit the number; the grid never truncates its numbers. It scrolls horizontally only below 336 dp of available width (see Grid), the one place in the app where it does.

Accessibility of a day cell:
- `Modifier.selectable(selected = <selected day, or range start or end>, role = Role.Button, onClick = …)` with `onClickLabel` "Show notes for this day" (`a11y_day_click`).
- `contentDescription` = the date in `FormatStyle.FULL` (for example "Tuesday, 17 November 2026"), followed by the applicable parts in this order, each joined with ", " (`names_separator`): "today" (`a11y_day_today`), "has notes" (`a11y_day_has_notes`), "start of range" (`a11y_day_range_start`), "end of range" (`a11y_day_range_end`), "in range" (`a11y_day_in_range`). TalkBack adds "Selected" from the `selected` property.
- Grid semantics: `collectionInfo = CollectionInfo(rowCount = 6, columnCount = 7)` on the grid and `collectionItemInfo = CollectionItemInfo(rowIndex, 1, columnIndex, 1)` on each day cell.

#### 4.4.2 Selection header and range mode

The row below the grid labels the list (a heading):

| Mode | Text | Controls |
|---|---|---|
| Day | The selected day in `FormatStyle.FULL`; when it is today, followed by the label "Today" (`label_today`) | `TextButton` with `ic_date_range` "Select range" (`action_select_range`) |
| Range | "17 Nov 2026 – 23 Nov 2026" (`date_range_both`, MEDIUM dates) | "Select range" (reopens the picker with the current range) and an `IconButton` `ic_close` "Clear range" (`action_clear_range`) |

- "Select range" opens a `DatePickerDialog` that hosts a `DateRangePicker` (DEC-11, DEC-53) initialised with the current day as start and no end (day mode) or with the current range. Buttons "Cancel" and "OK"; OK is enabled only when both a start and an end are selected. Conversion follows section 3.5 rule 8. Verify at setup: `DateRangePicker` inside `DatePickerDialog` lays out at 200 % font on a compact phone.
- OK with start = end gives day mode on that day. Otherwise range mode; the displayed month becomes the month of the range start.
- "Clear range" returns to day mode with the range's first day selected; the displayed month stays.
- The list shows every note dated inside the range, inclusive, in canonical order, never grouped by day (DEC-23, DEC-53); each row shows its date.
- New notes are prefilled with the selected day in day mode and with nothing in range mode (DEC-38).

## 5. Selection mode

Batch mode exists in all five perspectives with one action set (DEC-36). It is a state of `NoteListViewModel` (`selection: Set<Long>?`), not a navigation entry.

### 5.1 Entering and what changes

- A long-press on a row enters selection mode with that row selected (section 3.8). There is no other entry point. Entering first applies a pending search debounce at once (`searchText` → `NoteFilter.search`) and then selects the long-pressed row; if that row is missing from the next emission, pruning ends selection mode (section 5.5 rule 4). While selection mode is on no debounce can fire, because the search field is not shown, so the search filter cannot end the selection a moment after it began.
- While selection mode is on:
  - the contextual bar (section 5.2) replaces the app bar or the search bar; an active search text keeps filtering the list and reappears in the search bar afterwards;
  - the create button is hidden;
  - the filter chips are disabled, and in Date the day cells, "Today", "Select range" and "Clear range" are disabled (month navigation still works), so no accidental filter change can drop the selection;
  - a tap on a row toggles its selection, the done checkbox still only marks done or not done (DEC-54), and selected rows are highlighted with a trailing check mark (section 3.7);
  - the navigation bar stays usable; switching tabs ends selection mode (section 5.5).
- The selection holds note ids only and is saved (section 3.10).

### 5.2 Contextual top app bar

| Element | Content |
|---|---|
| Navigation icon | `ic_close`, "Exit selection" (`action_exit_selection`) |
| Title | "N selected" (`selected_count`, plurals), `titleLarge`, one line, with `liveRegion = Polite` so count changes are announced |
| Action 1 | `ic_select_all`, "Select all" (`action_select_all`) |
| Action 2 | `ic_add_link`, "Associate" (`action_associate`) |
| Action 3 | `ic_more_vert`, "More options" (`action_more`), a `DropdownMenu` with "Set date…" (`action_set_date`, leading `ic_edit_calendar`) and "Delete" (`action_delete`, leading `ic_delete`) |

- Colors: `TopAppBarDefaults.topAppBarColors(containerColor = secondaryContainer)`, so the mode change is visible beyond the text.
- There is no batch check or uncheck action (§7, DEC-36).

### 5.3 Actions

All actions work on the current selection pruned to the ids of the latest list emission ([data-layer.md § Batch operations](data-layer.md#batch-operations)). N in every message is the number of selected notes, not the number of changed rows (DEC-36).

| Action | Flow | On success |
|---|---|---|
| Select all | `NoteRepository` returns the ids of the full current query (the same `NoteQuery`: primary, chips, search; checked and off-screen notes included; `selectIds` in [data-layer.md § Perspective query](data-layer.md#perspective-query)). They replace the selection | Selection mode stays; the title shows the new count |
| Associate | Opens `PickerKey(EntityKind.Person, PickerPurpose.BatchAssociate)`: the two-tab picker of section 7.6. Its "Associate" button returns the chosen persons and projects, including names created there (DEC-33). The ViewModel calls `NoteRepository.associate(noteIds, personIds, projectIds, newPersons, newProjects)`: one transaction, additive, existing links ignored (DEC-36). Back in the picker returns nothing and keeps the selection | Snackbar "N notes associated" (`batch_associated`); selection mode ends |
| Set date… | Opens a `DatePickerDialog` titled "Set date" (`dialog_set_date_title`), initially showing the date that every selected note shares, else no selection and the current month. Buttons, end-aligned: "Remove date" (`action_remove_date`), "Cancel", "OK" (enabled while a day is selected), mapped onto the two slots of `DatePickerDialog`: `confirmButton = { TextButton(onClick = ok, enabled = state.selectedDateMillis != null) { Text(action_ok) } }` and `dismissButton = { Row { TextButton(removeDate) { Text(action_remove_date) }; TextButton(cancel) { Text(action_cancel) } } }`. OK calls `NoteRepository.setDate(noteIds, day)`; "Remove date" calls it with `null` (DEC-36). Conversion as in section 3.5 rule 8 | "Date set on N notes" (`batch_date_set`) or "Date removed from N notes" (`batch_date_removed`); selection mode ends |
| Delete | Opens the batch delete dialog (section 8.3). Confirm calls `NoteRepository.deleteNotes(noteIds)`: one transaction (DEC-35, DEC-36) | "N notes deleted" (`batch_deleted`); selection mode ends |

- Every action is one repository call and one transaction (§9, DEC-36). The UI never loops over notes.
- While an action runs, the contextual bar's actions are disabled; a second tap does nothing.
- A failed action shows its `AppError` text (section 11.13) in a Long snackbar and keeps the selection, so the user can retry.
- Snackbars are Short, without an action: there is no undo (§7, DEC-36).

### 5.4 Selection in the pickers and dialogs

- The picker and the dialogs do not change the selection. The list screen below them keeps it, and the result arrives in its ViewModel through the Result API (section 7.7).
- If the list changes while the picker is open (for example a save on leave from an editor closed just before, still running in `applicationScope`, unlinks or re-dates a selected note), the ViewModel prunes the selection before calling the repository. A restore never reaches this point: it resets navigation and every ViewModel with it (DEC-84, section 5.5 rule 6).

### 5.5 Exit rules

Selection mode ends, and the selection is cleared, when:
1. the user taps "Exit selection" or presses Back (section 2.5);
2. an action succeeds (section 5.3);
3. the user deselects the last selected row;
4. pruning to a new list emission leaves no selected id (for example, every selected orphan received a link);
5. the user switches to another tab (DEC-36);
6. the list's filter changes in any other way: a filter cleared by an existence probe, the Individual or Project entity replaced or cleared, a restore (DEC-34, DEC-36, DEC-84).

A lock and unlock cycle, rotation and process death keep the selection (section 3.10).

## 6. Note editor

`EditorScreen` and `EditorViewModel` (`ui/editor`) create and edit one note. It is the only note screen: the §4 "note detail view" and the §3 "creation/editing screen" are the same destination, `EditorKey(noteId, prefill)`.

### 6.1 Layout

```
+-------------------------------------------------------------+
| <-  New note                                   (check) (:)  |  top app bar (6.2)
+-------------------------------------------------------------+
| Title / content                                             |  content field (6.3), grows with the text
|                                                             |
| People                                                      |  association area (6.4)
| (Alice  x) (Bob  x) (+ Add person)                          |
| Projects                                                    |
| (Alpha  x) (+ Add project)                                  |
|                                                             |
| (event) 17 Nov 2026   Overdue                         (x)   |  date field (6.5)
| [ ] Done                                                    |  done checkbox (6.6)
+-------------------------------------------------------------+
```

- The order is the §3 order: content, associations, date, checklist toggle; the icon menu is the top app bar's overflow.
- The body is one vertically scrolling column with 16 dp side padding, at most 720 dp wide and centered on wider windows. Sections are separated by 16 dp; the association area and the rows below it have a minimum height of 56 dp per row.
- The navigation bar is hidden (DEC-50).

### 6.2 Top app bar and overflow menu

| Element | Content | Behavior |
|---|---|---|
| Navigation icon | `ic_arrow_back`, "Back" (`action_back`) | Same as system Back: close the editor, which saves (section 6.8) |
| Title | "New note" (`editor_title_new`) when opened with `noteId = null`, else "Edit note" (`editor_title_edit`) | Stays the same for the whole visit, also after the first insert |
| Action | `ic_check`, "Save" (`action_save`) | Save now, even a blank new note, then close (section 6.8) |
| Overflow | `ic_more_vert`, "More options" (`action_more`) | `DropdownMenu`, items below |

Overflow items, in this order (§3 icon menu):

| Item (key) | Enabled | Opens |
|---|---|---|
| "Unlink person or project…" (`menu_unlink`) | the association area shows at least one chip (saved or created in this edit) | Unlink sheet (section 6.10) |
| "Delete person…" (`menu_delete_person`) | always | Delete picker for persons (section 6.11) |
| "Delete project…" (`menu_delete_project`) | always | Delete picker for projects (section 6.11) |
| "Delete note" (`menu_delete_note`) | always | Delete note dialog (section 6.12) |

There is no "Discard changes" item: the editor saves on leave (DEC-37).

### 6.3 Content field

- One multiline M3 `TextField` for title and content (DEC-39): transparent container, no indicator line, `bodyLarge`, `TextFieldLineLimits.MultiLine(minHeightInLines = 6)`, no internal scrolling (it grows and the page scrolls).
- Placeholder "Title / content" (`content_hint`, DEC-39) in `onSurfaceVariant`, shown while the field is empty. A blank existing note shows this placeholder, never "(No data)" (DEC-25).
- The text is plain and stored exactly as typed, Markdown included; nothing is rendered or trimmed (DEC-39, §12).
- Keyboard: `KeyboardType.Text`, `KeyboardCapitalization.Sentences`, autocorrect on, `ImeAction.Default` (Enter inserts a line break). The field is always wrapped in `NoPersonalizedLearning(enabled = LocalKeyboardPrivacy.current)`, as in section 3.3 ([security.md § 11](security.md#11-keyboard-and-autofill-dec-71-q11)).
- Focus: a new note (opened with `noteId = null`) requests focus once, on the first composition after `loading` becomes false (section 6.14), and shows the keyboard, for low-friction capture (§10). A `focusRequested` flag in the `SavedStateHandle` prevents a second request after rotation or process death. An existing note opens without focus, so the keyboard does not cover it.
- State: `EditorViewModel` owns a `TextFieldState` saved through `SavedStateHandle.saveable(key = "content", saver = TextFieldState.Saver)`. The field uses the `ContentCap` input transformation (section 6.9). Verify at setup: `SavedStateHandle.saveable` for `TextFieldState` in the pinned lifecycle-viewmodel-compose; the fallback is a value-based `TextField` whose `onValueChange` applies the same cap and writes the text to the `SavedStateHandle`.

### 6.4 Association area

The area that §3 calls "association area for Persons and Projects (multi-select, create-on-the-fly allowed)".

- Container: a `Column` with `Modifier.semantics { contentDescription = "Associate persons and projects"; isTraversalGroup = true }` (`a11y_associate`, verbatim §4), so TalkBack announces the area and then visits its chips.
- Two groups, people first. Each group has a label ("People", `assoc_people`; "Projects", `assoc_projects`; `labelLarge`, `onSurfaceVariant`) and a `FlowRow` (8 dp spacing) of chips:
  - One `InputChip` per linked entity: label = the name (one line, ellipsis, at most 240 dp wide), trailing `ic_close` (18 dp). A tap removes the link from the draft. Semantics: `Modifier.clearAndSetSemantics { role = Role.Button; contentDescription = …; onClick { … } }` with the content description "Remove %1$s" (`a11y_remove_link`), for example "Remove Alice".
  - Last in the row, an `AssistChip` "Add person" / "Add project" (`action_add_person`, `action_add_project`) with leading `ic_add`. It opens `PickerKey(kind, PickerPurpose.EditorLinks(selectedIds = <draft ids of that kind>))` (section 7).
- Chip order: saved links by `Collator` on the name, then id (DEC-32, like row summaries), then entities created in the picker during this edit, in creation order. A created entity looks like any other chip; it is inserted only with the note save (DEC-33).
- The picker result replaces the draft's ids of that kind and appends the newly created names (section 7.7). Removing a chip that stands for a created entity drops it; nothing is ever inserted for it (DEC-33).
- Link changes are draft changes, written by the next save (section 6.8); they bump `updated_at` (DEC-21).

### 6.5 Date field

- A row with leading `ic_event`, then the date in `FormatStyle.MEDIUM` or "No date" (`date_none`), then the "Overdue" label when the note is unchecked and its date is before today (DEC-26; same style as in rows, section 3.7), then, only while a date is set, a trailing `IconButton` `ic_close` "Remove date" (`action_remove_date`).
- The row except the remove button is one clickable node: `Modifier.clickable(role = Role.Button)` plus `Modifier.semantics { contentDescription = …; stateDescription = … }`. The content description is "Set or change date" (`a11y_set_date`, verbatim §4). The state description is the date text or "No date", followed by ", Overdue" when overdue.
- A tap opens a `DatePickerDialog` with a `DatePicker` (`showModeToggle = true`) showing the draft's date, else today. "Cancel" and "OK" (OK enabled while a day is selected). OK sets the draft date; conversion as in section 3.5 rule 8 (DEC-26).
- "Remove date" sets the draft date to none (the note becomes undated, §1). Future dates are allowed (§4).

### 6.6 Done checkbox

- A row with an M3 `Checkbox` and the text "Done" (`editor_done_label`). The row is one toggleable node: `Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = …)` plus `Modifier.semantics { contentDescription = …; stateDescription = … }`. The content description is "Mark note as done" (`a11y_mark_done`) while unchecked and "Mark note as not done" (`a11y_mark_not_done`) while checked (verbatim §4); the state description is "Not done" (`state_not_done`) or "Done" (`state_done`) (DEC-55). The inner `Checkbox` has `onCheckedChange = null`.
- Saved note (`noteId != null` at the moment of the tap): writes at once through `NoteRepository.setChecked(noteId, target)` (DEC-22, DEC-37) and announces like a list toggle (section 3.9 step 5). It is not part of the draft and never makes the draft "changed". Editing a checked note keeps it checked (DEC-22).
- New note not yet inserted: changes the draft's `checked`. That counts as a change (section 6.8), and the note is inserted already checked with `checked_at = created_at` (DEC-22).

### 6.7 Prefill

- The create button passes `NotePrefill(personId, projectId, epochDay)` in the `EditorKey`, filled from the perspective's primary only (DEC-38, table in section 3.2): Individual → the chosen person; Project → the chosen project; Date in day mode → the selected day; Date in range mode, All, Orphan, and Individual or Project without a chosen entity → nothing. Secondary filters and the search text never prefill.
- A prefilled entity appears as a normal chip and a prefilled date as a normal date; the user can remove both. The editor resolves names through the existence probes; a prefilled id that no longer exists is dropped without a message.
- Prefills are part of the draft's initial value, so a draft that holds only its prefill is unchanged and is not saved on leave (DEC-37, section 6.8).

### 6.8 Saving, as the user experiences it

The draft lives in `EditorViewModel` and its `SavedStateHandle` (DEC-37). Persistence mechanics: [architecture.md § Threading](architecture.md#threading) (save on the entry's `ON_STOP`, in `applicationScope`) and [data-layer.md § Note save](data-layer.md#note-save).

When the editor writes:

| Trigger | New note (never inserted) | Existing note |
|---|---|---|
| Save action | Always inserts, even a completely blank note (§3 "prepare a note in advance"), then closes | Writes if content, date or links differ from the stored note, then closes |
| Back (system or toolbar), leaving by navigation, the app going to the background (`ON_STOP`) | Inserts only if the draft differs from its initial value (the prefill is part of it, section 6.7) and is not blank | Writes if content, date or links differ from the stored note |
| Opening the note, scrolling, focusing | never | never (no `updated_at` change, DEC-21) |

- "Blank" means: empty content, no date, unchecked, no links and no created entities. This keeps "+ then Back" and "remove the prefill, then Back" from creating empty notes, while the Save action still creates a deliberately blank note. The "not blank" condition refines DEC-37 for a prefill that the user removed: such a draft differs from its initial value but holds nothing worth saving (pending a DEC-37 amendment that states it; testing.md I-36 pins it).
- Content compares by exact string equality, so whitespace edits count; a change that is reverted before leaving is no change (data-layer.md § Note save).
- Back is never intercepted: there is no "unsaved changes" prompt and no confirmation (DEC-37, architecture.md § Back behavior), so predictive back animates from the editor.
- There is no "Saved" message on success.
- The Save action awaits the write before closing. If it fails, the editor stays open with the draft and shows the `AppError` text (section 11.13) in a Long snackbar.
- A save on Back or `ON_STOP` runs after the editor has closed. If it fails, the save posts "The note could not be saved. %1$s" (`editor_save_failed`, the argument being the `AppError` text) to `AppMessages` (section 2.4), shown as a Long snackbar, because the editor's ViewModel is already gone.
- After the first insert the editor keeps the new `noteId`; later saves update that note and never insert it or its new entities twice (data-layer.md § Note save, caller rules).
- If the note no longer exists (`loadNote` returns null on open, or a save returns `NoteGone`), the editor closes without writing and posts "This note no longer exists." (`note_gone`) to `AppMessages`.
- After a restore, Start fresh or Erase, a draft opened before the data change is never written (architecture.md § Data reset).
- Entities created in a picker are inserted in the same transaction as the note save that uses them; a draft that is never saved creates none (DEC-33).
- Draft identity across process death. Android captures the saved state right after `onStop`, while the `ON_STOP` save in `applicationScope` may still be running, so a restored draft could insert the same note and its new entities a second time (no deduplication exists, §1). The draft therefore has a `draftKey`, a random UUID created with the draft and stored in the `SavedStateHandle` with the other `NoteDraft` fields (section 6.14). After the first `Saved` of an insert the editor first calls `SettingsStore.putDraftInsert(draftKey, DraftInsert(noteId, createdPersons, createdProjects))` ([architecture.md § SettingsStore](architecture.md#settingsstore)) and only afterwards updates its `SavedStateHandle` (the `noteId` is stored and the created entities move from the `new*` lists into the id sets), so the record exists whenever a restored state can predate the insert. A ViewModel restored with `noteId == null` calls `SettingsStore.draftInsert(draftKey)` before its first save and, on a hit, adopts the record exactly as after `Saved` ([data-layer.md § Note save](data-layer.md#note-save), caller rules). The record is dropped (`dropDraftInsert`) when the draft is finished: its last save has returned after the editor closed, the draft was discarded, or the note was deleted; a data reset drops every record. Accepted residual risk: the process dies between the database commit and the end of the DataStore write.
- After `deleteNotes` of the open note succeeds (section 6.12), the editor discards its draft: nothing is saved on leave and the draft-insert record is dropped. `NoteGone` stays the safety net for a save that races the delete.

### 6.9 Content limit

- The content holds at most 200,000 UTF-16 code units (DEC-24, INV-1 in [data-layer.md § Invariants](data-layer.md#invariants)).
- `ContentCap`, an `InputTransformation` on the content field, truncates every change that would exceed the limit (typing, paste, IME commit) to the first 200,000 units. If unit 200,000 would split a surrogate pair (the unit at index 199,999 is a high surrogate), it cuts at 199,999 instead (testing.md I-08).
- Each truncation shows "Notes are limited to 200,000 characters. The text was cut at that limit." (`content_truncated`) as a Long snackbar, once per truncating change.
- A stored note longer than the limit can only come from a backup written outside this app (restore check R-2 measures bytes, data-layer.md). The editor truncates it on load the same way and shows the same message. The truncated text becomes the draft's initial value, so leaving without editing writes nothing; any edit saves the truncated text.
- There is no visible character counter.

### 6.10 Unlink person or project

- "Unlink person or project…" opens a `ModalBottomSheet` titled "Unlink person or project" (`unlink_title`, a heading). It lists every chip of the association area (section 6.4), saved and created in this edit, in chip order, in two sections, "People" and "Projects" (`assoc_people`, `assoc_projects`; empty sections are omitted). Each row shows the name and a trailing `TextButton` "Unlink" (`action_unlink`) whose content description is "Unlink %1$s" (`a11y_unlink_entity`).
- "Unlink" removes that link from the draft at once, exactly like the chip's remove action, and the row disappears. On an entity created in this edit it discards the entity: nothing is inserted for it (DEC-33). It removes only this note's association; the person or project itself stays (§3).
- When no link remains, the sheet shows "This note has no person or project left and will move to Orphans." (`unlink_now_orphan`) and a "Done" button (`action_done`) (§3, DEC-27).
- The change is written with the draft (section 6.8).

### 6.11 Delete person or project

1. "Delete person…" or "Delete project…" opens `PickerKey(kind, PickerPurpose.DeleteEntity(linkedIds = <draft ids of that kind>))`: every entity of that kind, this note's links first (DEC-34, section 7.5).
2. When the picker returns an id, the editor first saves its draft if section 6.8 would save it on leave, so the counts include this note's link edits ([data-layer.md § Deletes](data-layer.md#deletes)).
3. It reads `EntityRepository.personDeleteImpact(id)` or `projectDeleteImpact(id)` and shows the impact dialog (section 8.4).
4. "Delete" calls `deletePerson(id)` or `deleteProject(id)`: one transaction that recomputes the counts, removes the links and the entity, and changes no `updated_at` (DEC-34). The editor removes the entity from its draft. Snackbar "Person deleted" or "Project deleted" (`person_deleted`, `project_deleted`); no message when the result says the entity was already gone.
5. "Cancel" changes nothing.
6. Perspectives and filters that referenced the entity reset by themselves (sections 3.4 and 4.2; DEC-34).

Entities created in a picker during this edit are not in the database yet, so they never appear in the delete picker; the user removes them with their chip.

### 6.12 Delete note

- "Delete note" opens the delete note dialog (section 8.2, DEC-35).
- "Delete" on a note that has been inserted calls `NoteRepository.deleteNotes(listOf(noteId))`, discards the draft (no save on leave, draft-insert record dropped, section 6.8), closes the editor, and posts "1 note deleted" (`batch_deleted` with count 1) to `AppMessages`, so it shows on the list.
- "Delete" on a draft that was never inserted discards it and closes the editor; nothing is written and no message is shown.
- "Cancel" changes nothing.

### 6.13 Keyboard and insets

- The body is `Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding().verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally) { Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) { /* sections of section 6.1 */ } }`: the scrolling viewport ends above the keyboard, the content is at most 720 dp wide and centered, the system-bar insets already applied by the `Scaffold` are not added twice, and the focused text field brings its caret into view while typing.
- `MainActivity` needs `android:windowSoftInputMode="adjustResize"` so IME insets reach Compose on every API level. The manifest itself is in [security.md § 9.1](security.md#91-manifest).
- Opening a dialog, sheet or picker from the editor hides the keyboard; returning does not reopen it.

### 6.14 Editor state

```kotlin
data class EditorUiState(
    val loading: Boolean,                  // true until loadNote (existing note) or the prefill names resolve
    val isNew: Boolean,                    // opened with noteId == null; drives the title only
    val noteId: Long?,                     // set after the first insert
    val date: LocalDate?, val overdue: Boolean,
    val checked: Boolean,
    val people: List<LinkChip>, val projects: List<LinkChip>,
    val dialog: EditorDialog?,             // DeleteNote | Unlink | DatePicker | DeleteImpact(kind, id, name, n, m)
    val message: UserMessage?,
) { override fun toString() = "EditorUiState(noteId=$noteId)" }   // DEC-70
data class LinkChip(val id: Long?, val newKey: String?, val name: String) {   // id for saved entities, newKey for created ones
    override fun toString() = "LinkChip(id=$id)"
}
```

- The content text is not in `EditorUiState`; it lives in the `TextFieldState` (section 6.3).
- While `loading` is true the body shows nothing for 500 ms and then a centered `CircularProgressIndicator` (`a11y_loading`). The content field, the chips, the date row and the checkbox are not composed, and Save and the overflow menu are disabled, so nothing typed can be overwritten by the loaded content and no action can run on a draft that is not loaded. Back still closes the editor, and nothing is saved because the draft equals its initial value.
- `EditorDialog` is saved as the String key `editorDialog` (its simple name). A restored `DeleteImpact` keeps only its kind and entity id and re-reads the name and counts with `personDeleteImpact` / `projectDeleteImpact`, so a restored dialog never shows stale counts. The selection inside an open `DatePicker` is UI-local, and the overflow menu is not restored after process death.
- Saved in the `SavedStateHandle`: the `NoteDraft` fields except `content` ([data-layer.md § Note save](data-layer.md#note-save)), which include `draftKey` (section 6.8) and the `epoch` of [architecture.md § Data reset](architecture.md#data-reset), the initial values of `date`, `checked` and the link sets, the content (`"content"`, section 6.3), `focusRequested` (section 6.3) and `editorDialog`. The initial content of an existing note is not saved: after process death it is reloaded with `loadNote`, which also tells whether an `ON_STOP` save already stored the draft.
- Verify at setup: an editor holding 200,000 characters (about 400 KB when parcelled) plus five tabs, each with Select all over the 10,000-note fixture (at most 1,000 saved ids each, section 3.10), must keep the whole saved state below 500 KB and must not raise `TransactionTooLargeException` when the activity saves its state on API 28 and 35 (testing.md I-38). If it does, raise a DEC-37 amendment before changing where the draft is kept.

## 7. EntityPicker

`EntityPicker` (`ui/common`) is the only way to choose persons and projects. It is a full-screen destination (`PickerKey(kind, purpose)`, DEC-50) or, in Individual and Project without a chosen entity, the embedded body of the perspective (DEC-52). Its state holder is `EntityPickerViewModel`; options come from `EntityRepository` (`observeOptions`, [data-layer.md § Editor loads, pickers and existence probes](data-layer.md#editor-loads-pickers-and-existence-probes)).

### 7.1 Purposes

`PickerKey(kind: EntityKind, purpose: PickerPurpose)` is defined in architecture.md; `PickerPurpose` is defined here. Payloads hold ids only, never names (architecture.md § Keys and stacks).

```kotlin
@Serializable
sealed interface PickerPurpose {
    @Serializable data class EditorLinks(val selectedIds: List<Long>) : PickerPurpose      // editor "Add person/project"
    @Serializable data object BatchAssociate : PickerPurpose                               // selection bar "Associate"
    @Serializable data class PerspectivePrimary(val selectedId: Long?) : PickerPurpose     // Individual/Project title
    @Serializable data class SecondaryFilter(val selectedId: Long?, val coLinkedTo: Long?) : PickerPurpose  // Person/Project chip
    @Serializable data class DeleteEntity(val linkedIds: List<Long>) : PickerPurpose       // editor "Delete person/project…"
}
```

| Purpose | Opened from | Mode | Title (Person / Project) | First rows | Confirm |
|---|---|---|---|---|---|
| `EditorLinks` | Editor "Add person" / "Add project" (section 6.4) | multi-select, one kind | "Add people" / "Add projects" (`picker_title_add_people`, `picker_title_add_projects`) | create row; entities created in this visit | "Done" (`action_done`) |
| `BatchAssociate` | Selection bar "Associate" (section 5.3) | multi-select, both kinds on two tabs; `kind` is the first tab (always `Person`) | "Associate" (`picker_title_associate`) | per tab: create row; entities created in this visit | "Associate" (`action_associate`) |
| `PerspectivePrimary` | Individual/Project title button (section 4.2) | single-select | "Choose a person" / "Choose a project" (`picker_title_choose_person`, `picker_title_choose_project`) | — | a tap |
| `SecondaryFilter` | Person/Project chip (section 3.4) | single-select | "Filter by person" / "Filter by project" (`picker_title_filter_person`, `picker_title_filter_project`) | "Any person" / "Any project" (`picker_any_person`, `picker_any_project`) | a tap |
| `DeleteEntity` | Editor "Delete person…" / "Delete project…" (section 6.11) | single-select | "Delete person" / "Delete project" (`picker_title_delete_person`, `picker_title_delete_project`) | — | a tap |
| (embedded body) | Individual/Project with nothing chosen (section 4.2) | single-select | the perspective's title | — | a tap |

A create row exists only in `EditorLinks` and `BatchAssociate`: a person or project can be created only where it is linked to a note at once (§3, DEC-33).

### 7.2 Layout

Full-screen form, top to bottom:
1. `TopAppBar`. Multi-select: navigation icon `ic_close` "Cancel" (`action_cancel`), title, and the confirm `TextButton` as the action. Single-select: navigation icon `ic_arrow_back` "Back" (`action_back`) and the title.
2. `BatchAssociate` only: a `PrimaryTabRow` with the tabs "People" and "Projects" (`tab_people`, `tab_projects`); while a tab has chosen entities its label reads "People (2)" (`pluralStringResource(R.plurals.tab_with_count, n, label, n)`).
3. The name search field: a single-line `TextField` with leading `ic_search`, placeholder "Search people" / "Search projects" (`picker_search_people`, `picker_search_projects`), trailing `ic_close` "Clear search text" (`action_clear_search`) while not empty, `ImeAction.Done`, no autofocus (the keyboard would hide the list). Input longer than `MAX_NAME_CHARS` (200) characters is cut without a message, surrogate pairs kept whole, so the create row never produces a name that `EntityRepository` rejects (data-layer.md INV-9; names that arrive longer through a restore are not cut and are shown with ellipsis). The field is wrapped in `NoPersonalizedLearning(enabled = LocalKeyboardPrivacy.current)` as in section 3.3 ([security.md § 11](security.md#11-keyboard-and-autofill-dec-71-q11)).
4. A `LazyColumn` with the rows of sections 7.3 and 7.4, keyed by `"any"`, `"create"`, `"new:<key>"`, `"header:<n>"` and the entity id.

The embedded form has no app bar and no tabs: the search field and the list fill the perspective body under the perspective's own app bar.

Name search is a case-insensitive substring match on the name, done in memory with `SearchText.fold` (data-layer.md § Editor loads, pickers and existence probes). It filters every section; empty sections and their headers are hidden. Blank input shows everything.

### 7.3 Rows and duplicate display

| Row | Content | Interaction and semantics |
|---|---|---|
| Entity, multi-select | Leading `Checkbox` (`onCheckedChange = null`); the name (`bodyLarge`, up to 2 lines, ellipsis); supporting text "N notes · added <date>" (`picker_entity_meta`, plurals) | The row is `Modifier.toggleable(role = Role.Checkbox)`; min height 56 dp |
| Entity, single-select | The name and the same supporting text; a trailing `ic_check` on the currently chosen entity (`selectedId`) | `Modifier.selectable(selected = id == selectedId, role = Role.Button)`; a tap returns the entity at once. `DeleteEntity` rows use `onClickLabel` "Choose" (`a11y_choose`) and have no check mark |
| Created in this visit | Like a multi-select entity row, checked, with the supporting text "New" (`picker_entity_new`) instead of the counts | Unchecking it discards it; it is never inserted (DEC-33) |
| Create row | Leading `ic_add`; `Create "X"` (`picker_create`), or `Create another "X"` (`picker_create_another`) when a case-insensitive exact match exists (DEC-32) | Section 7.6 |
| "Any person" / "Any project" | The text; trailing `ic_check` while no filter is set | `SecondaryFilter` only; returns "no filter" |
| Section header | Text (section 7.4), `titleSmall`, `primary`, 16 dp side padding | `Modifier.semantics { heading() }` |

- The supporting text is the duplicate display of DEC-32: `note_count` and the entity's `created_at` as a date (`AppTime.dateOf(createdAt)`, `FormatStyle.MEDIUM`), for example "3 notes · added 12 Nov 2026" or "0 notes · added 12 Nov 2026". Two entities with the same name stay two rows; nothing is merged (§1).
- Entities with zero notes are always listed (DEC-33).

### 7.4 Ordering and sections

`EntityRepository` delivers the options sorted by `Collator` on the name, then id (DEC-32), with co-linked entities first when requested ([data-layer.md § Editor loads, pickers and existence probes](data-layer.md#editor-loads-pickers-and-existence-probes)). The picker arranges them per purpose:

| Purpose | Arrangement, top to bottom |
|---|---|
| `EditorLinks`, `BatchAssociate` (per tab) | Create row; entities created in this visit (creation order); all entities in repository order. Checked and unchecked rows are not regrouped, so a row never jumps when it is ticked |
| `PerspectivePrimary`, embedded body | All entities in repository order |
| `SecondaryFilter` with `coLinkedTo = null` (All, Date) | "Any …"; all entities in repository order |
| `SecondaryFilter` with `coLinkedTo` set | "Any …"; header "People on this project's notes" (`picker_section_co_people`) or "Projects on this person's notes" (`picker_section_co_projects`) with the co-linked entities; header "Other people" / "Other projects" (`picker_section_other_people`, `picker_section_other_projects`) with the rest |
| `DeleteEntity` | Header "Linked to this note" (`picker_section_linked`) with the entities in `linkedIds`; header "Other people" / "Other projects" with the rest (DEC-34) |

Headers appear only when both of their groups are non-empty; otherwise the single group is listed without a header.

### 7.5 Single-select variant

- A tap on an entity returns it and closes the picker (`PerspectivePrimary`, `SecondaryFilter`, `DeleteEntity`). In the embedded body, a tap stores the remembered id (section 4.2).
- `SecondaryFilter`: "Any person" / "Any project" returns "no filter" and clears that dimension.
- `DeleteEntity`: the editor receives the id and continues with the impact dialog (section 6.11).
- Back returns nothing; the opener keeps its current state.

### 7.6 Multi-select variant and the create row

- `EditorLinks` starts with the draft's saved links of that kind checked (`selectedIds`). Unchecking one and confirming removes it from the draft. Entities created earlier in the same edit are not passed (keys carry no names) and are not listed; they stay as chips in the editor (section 6.4).
- `BatchAssociate` starts with nothing checked: associating is additive and existing links are ignored (DEC-36). The confirm button is enabled once at least one entity is checked or created on either tab, and the result carries both kinds, so the list makes one `associate` call and one transaction (§7 step 2 "and/or", DEC-36).
- Create row (DEC-32, DEC-33):
  - Shown while the trimmed search text is not blank (blank names are rejected).
  - It reads `Create another "X"` when the folded trimmed text equals the folded name of any listed entity or of an entity created in this visit (`SearchText.fold`), else `Create "X"`. X is the trimmed text. Entities created in an earlier visit of the same edit are not known to the picker (keys carry no names, DEC-70), so they do not trigger "Create another" in a later visit; duplicates stay allowed (§1).
  - A tap adds `NewEntity(key = <random UUID>, name = <trimmed text>)` to this visit's created list, checked, clears the search field and keeps focus in it. Nothing is written to the database (DEC-33).
  - Duplicates are allowed: the create row is offered even on an exact match (§1).
- Confirm returns the result (section 7.7) and closes. Cancel or Back discards the checks and the created entities.

### 7.7 Results and state

Results travel through the Navigation 3 Result API (architecture.md § Keys and stacks): the picker calls `sendResult(result)` and then `Navigator.back()`; the opener receives it with `ResultEffect`.

```kotlin
sealed interface PickerResult {
    data class Links(val kind: EntityKind, val ids: Set<Long>, val created: List<NewEntity>) : PickerResult        // EditorLinks
    data class Associate(val personIds: Set<Long>, val projectIds: Set<Long>,
        val newPersons: List<NewEntity>, val newProjects: List<NewEntity>) : PickerResult                        // BatchAssociate
    data class Primary(val kind: EntityKind, val id: Long) : PickerResult                                         // PerspectivePrimary
    data class Filter(val kind: EntityKind, val id: Long?) : PickerResult                                         // SecondaryFilter, null = any
    data class Delete(val kind: EntityKind, val id: Long) : PickerResult                                          // DeleteEntity
}
```

- Every result class overrides `toString()` to print kinds and counts only (DEC-70). `NewEntity` is the data-layer type (key plus name, [data-layer.md § Note save](data-layer.md#note-save)).
- The editor applies `Links` by replacing its ids of that kind with `ids` and appending `created` to its created entities. The list screen applies `Associate` (section 5.3), `Primary` (section 4.2) and `Filter` (section 3.4). Results only ever reach the entry that opened the picker.
- Checked ids that no longer exist when the result is built are dropped; the repositories drop vanished ids again inside their transactions (data-layer.md § Note save, § Batch operations).
- `EntityPickerViewModel` saves in its `SavedStateHandle`: the search text per kind, the checked ids per kind (`LongArray`), the created entities per kind (keys and names) and the current tab. Initial values from the purpose (`selectedIds`) are applied only when the entry is first created, so rotation and process death keep the user's changes.

### 7.8 Empty states

| Situation | Shows |
|---|---|
| No entity of this kind exists, purpose with a create row | "(No data)" (`no_data`) and "Type a name to create a person." / "Type a name to create a project." (`picker_create_hint_person`, `picker_create_hint_project`) |
| No entity exists, `PerspectivePrimary`, `SecondaryFilter` or the embedded body (no create row; reached from a list screen that has the + button) | "(No data)" and "People are created in a note: tap + and use Add person." / "Projects are created in a note: tap + and use Add project." (`picker_empty_people_hint`, `picker_empty_projects_hint`). `SecondaryFilter` still shows its "Any …" row above |
| No entity exists, `DeleteEntity` (opened from the editor, which has no + button and where Add person is right behind the picker) | "(No data)" alone; the user can only go Back |
| Entities exist but the name search matches none | "(No data)"; the create row stays above it where offered |

## 8. Dialogs

### 8.1 Common rules

- Every dialog is an M3 `AlertDialog` (Compose): title in `headlineSmall`, the whole `text` slot (body, fields, acknowledgement checkbox and error texts) is one `Column(Modifier.verticalScroll(rememberScrollState()))`, so a dialog with three password fields stays reachable with the keyboard open at 200 % font; only the title and the buttons stay fixed. Buttons are at the bottom end with the dismiss button before the confirm button.
- A destructive confirm button ("Delete", "Replace", "Turn off", "Delete and start fresh", "Erase all data") uses `ButtonDefaults.textButtonColors(contentColor = colorScheme.error)`.
- Dialogs with password fields set `DialogProperties(dismissOnClickOutside = false)`, so a stray tap does not lose typed input; Back and "Cancel" still close them. Other dialogs keep the defaults.
- `securePolicy` stays `SecureFlagPolicy.Inherit` on every dialog, sheet, menu and date picker (DEC-67).
- Every dialog and bottom sheet wraps its content in `SecureDialogWindow` (`ui/common`, [security.md § 11](security.md#11-keyboard-and-autofill-dec-71-q11)), which keeps autofill out of that window. The security dialogs (the key-change dialogs of section 8.8, the backup and restore password dialogs, the restore confirmation, Start fresh and Erase) pass `hideOverlays = true` ([security.md § 9.4](security.md#94-task-hijacking-and-overlays)).
- Which dialog is open is part of the owning ViewModel's UiState (`ListDialog`, section 3.10; `EditorDialog`, section 6.14; the Settings dialog, section 9.8), so it survives rotation and process death. Password field contents never are: they live in `remember { TextFieldState() }` and are empty after recreation ([security.md § 6.2](security.md#62-fields-and-state), testing.md M-09).
- While an operation started by a dialog runs, its buttons are disabled; long operations continue under the maintenance overlay (section 10.6).

Password fields (`PasswordField`, `ui/common`): an `OutlinedSecureTextField` with its defaults ([security.md § 6.2](security.md#62-fields-and-state)) and a label, plus a trailing `IconButton` that switches `textObfuscationMode` between `TextObfuscationMode.Hidden` (the initial value) and `TextObfuscationMode.Visible`, with the content descriptions "Show password" / "Hide password" (`password_show`, `password_hide`). The component's default, `RevealLastTyped`, is never used: it shows each typed character briefly, which a screen recording captures even with screenshot protection off ([security.md § 6.2](security.md#62-fields-and-state)). Errors appear as supporting text in `error` color with `liveRegion = Polite`. On confirm, the dialog first runs the password policy of [security.md § 6.1](security.md#61-policy) on the field contents; a violation keeps the text and shows the error under the offending field ("Use at least 8 characters." `password_too_short`; "The passwords do not match." `password_mismatch`). Then it copies the text into `CharArray`s, clears every field and starts the operation (security.md § 6.2). A new password is always typed twice and needs the acknowledgement checkbox "I understand this cannot be recovered" (`password_ack`, verbatim DEC-64); the confirm button is enabled only when every field is non-empty and the checkbox is ticked.

### 8.2 Delete note (DEC-35, Q6)

| Part | Text |
|---|---|
| Title | "Delete this note?" (`dialog_delete_note_title`) |
| Body | "This cannot be undone." (`dialog_irreversible`) |
| Buttons | "Cancel" (`action_cancel`) · "Delete" (`action_delete`, destructive) |

Outcome: section 6.12.

### 8.3 Batch delete (DEC-35, DEC-36)

| Part | Text |
|---|---|
| Title | "Delete 1 note?" / "Delete %d notes?" (`dialog_batch_delete_title`, plurals on the number of selected notes) |
| Body | "This cannot be undone." (`dialog_irreversible`) |
| Buttons | "Cancel" · "Delete" (destructive) |

Outcome: section 5.3.

### 8.4 Delete person or project: impact (DEC-34)

Shown by the editor after the delete picker (section 6.11), with N = notes linked to the entity and M = those of them that would be left with no person and no project (M ⊆ N, [data-layer.md § Deletes](data-layer.md#deletes)).

| Part | Text |
|---|---|
| Title | `Delete person "%1$s"?` (`dialog_delete_person_title`) / `Delete project "%1$s"?` (`dialog_delete_project_title`), with the entity name |
| Body | Sentence A, sentence B (only when N ≥ 1), then "This cannot be undone." (`dialog_irreversible`), joined with single spaces |
| Buttons | "Cancel" · "Delete" (destructive) |

Sentence A:

| Case | Text |
|---|---|
| N = 0 | "This person is not linked to any note." (`impact_person_not_linked`) / "This project is not linked to any note." (`impact_project_not_linked`) |
| N ≥ 1 | "This person is linked to 1 note." / "This person is linked to %d notes." (`impact_person_linked`, plurals on N); for projects `impact_project_linked` |

Sentence B (N ≥ 1 only):

| Case | Text |
|---|---|
| M = 0 | "That note keeps other links and stays out of Orphans." / "All of them keep other links and stay out of Orphans." (`impact_none_orphaned`, plurals on N) |
| M = N | "That note will have no person or project left and will move to Orphans." / "All of them will have no person or project left and will move to Orphans." (`impact_all_orphaned`, plurals on N) |
| 1 ≤ M < N | "1 of them will have no person or project left and will move to Orphans." / "%d of them will have no person or project left and will move to Orphans." (`impact_some_orphaned`, plurals on M) |

Example: `Delete person "Alice"?` — "This person is linked to 12 notes. 4 of them will have no person or project left and will move to Orphans. This cannot be undone."

The counts come from `personDeleteImpact` / `projectDeleteImpact`; the delete itself recomputes them (DEC-34), so a stale dialog never deletes the wrong thing.

### 8.5 Unencrypted backup warning (DEC-83)

Shown before every export that will be plaintext (database mode `PLAIN` and "Encrypt backups" off), before the location is chosen or the share sheet opens.

| Part | Text |
|---|---|
| Title | "Back up without encryption?" (`backup_plain_title`) |
| Body | "This backup will not be encrypted. Anyone who gets the file can read all your notes, people and projects. To protect backups with a password, turn on Encrypt backups in Settings." (`backup_plain_body`) |
| Buttons | "Cancel" · "Back up anyway" (`action_backup_anyway`) |

### 8.6 Backup password (DEC-83)

Shown for every encrypted backup ("Encrypt backups" on, or the database encrypted; [security.md § 2](security.md#2-security-settings)). Order with the SAF path: after the user chose the location, because a password is never kept across an external activity (DEC-64, architecture.md § Result launchers and process death). Order with the share path: before the share copy is written.

| Part | Text |
|---|---|
| Title | "Protect the backup" (`backup_password_title`) |
| Body | "The backup file will be encrypted with this password. You need it to restore the backup, and it cannot be recovered. A long passphrase of several words is safest." (`backup_password_body`) |
| Fields | "Backup password" (`backup_password_label`), "Repeat backup password" (`backup_password_repeat`), checkbox "I understand this cannot be recovered" (`password_ack`) |
| Buttons | "Cancel" · "Back up" (`action_backup`) on the SAF path, "Share" (`action_share`) on the share path |

- "Cancel" on the SAF path ends the backup and calls `DbMaintenance.discardDocument(uri)`, which deletes the document only when the picker created it (its size is known to be 0); a document that already holds data is left untouched ([db-lifecycle.md § To a document (SAF)](db-lifecycle.md#to-a-document-saf), step 4).
- The backup password is independent of the startup password and never becomes one (DEC-83, security.md § 4).

### 8.7 Restore (DEC-84)

The steps, the validation and the swap are in [db-lifecycle.md § Restore](db-lifecycle.md#restore). `DbMaintenance.restore(uri, origin)` (origin `Ready` from Settings, the screen's own state from the Corrupt and KeyUnavailable screens) drives the questions through `DbMaintenance.status`, and the maintenance status host (section 8.12) renders one dialog per status, on Settings and on the Corrupt and KeyUnavailable screens alike. The user answers through `answerPassword`, `answerConfirmation` and `cancelRestore`.

| `status` | Dialog |
|---|---|
| `Running(Restore, step)` while the gate is not in `Maintenance` (before the confirmation) | Title "Checking the backup…" (`restore_checking`), an indeterminate `LinearProgressIndicator` (determinate while `fraction` is known), button "Cancel" → `cancelRestore()`. Back does the same; no other dismissal |
| `NeedsBackupPassword(retry)` | Title "Encrypted backup" (`restore_password_title`); body "Enter the password that was set when this backup was made." (`restore_password_body`); a `PasswordField` "Backup password" (`backup_password_label`), empty; buttons "Cancel" → `answerPassword(null)` · "Continue" (`action_continue`) → `answerPassword(chars)`. With `retry = true` the field shows "Wrong password, or the file is not a Doro Organizer backup." (`restore_password_retry`): SQLCipher cannot tell the two apart. Attempts are unlimited and not throttled ([security.md § 5.3](security.md#53-backoff-dec-62)) |
| `NeedsDatabasePassword(error)` (restore from the Corrupt screen in `PASSWORD` mode) | Title "Enter your startup password" (`restore_db_password_title`); body "The restored data will be protected by your startup password. Enter it to continue." (`restore_db_password_body`); a `PasswordField` "Password" (`password_label`); "Cancel" → `answerPassword(null)` · "Continue" → `answerPassword(chars)`. `error` = `WrongPassword` shows "Wrong password."; `Throttled` disables the field and "Continue" and shows the countdown text, as on the password screen (section 10.2) |
| `NeedsConfirmation(preview)` | The confirmation below |
| `Running(Restore, step)` while the gate is in `Maintenance(Restore, origin)` (after the confirmation) | The gate shows the maintenance overlay or screen with "Restoring backup…" (section 10.6) |
| `Finished(Restore, result)` | `Ok(RestoreCancelled)`: nothing. `Ok(Restored)`: the success message below. `Err(…)`: the error dialog below |

Confirmation (DEC-84: counts, irreversible replacement, resulting encryption state; §11 "explicit user confirmation"):

| Part | Text |
|---|---|
| Title | "Restore this backup?" (`restore_confirm_title`) |
| Body, paragraph 1 | "The backup contains %1$s, %2$s and %3$s." (`restore_confirm_counts`) with `preview.notes`, `preview.persons`, `preview.projects` as "%d note(s)" (`count_notes`), "%d person / people" (`count_people`), "%d project(s)" (`count_projects`) |
| Body, paragraph 2 | "Everything now in the app will be replaced. This cannot be undone." (`restore_confirm_replace`) |
| Body, paragraph 3 | By `preview.resultingMode`: `PLAIN` "After the restore, your data will be stored unencrypted on this device." (`restore_result_plain`); `DEVICE` "After the restore, your data will be encrypted on this device." (`restore_result_device`); `PASSWORD` "After the restore, your data will be encrypted and the app will still ask for your current startup password." (`restore_result_password`). Then, when `preview.encryptionTurnsOff` is false, "Your security settings stay as they are." (`restore_settings_kept`); when it is true, instead "Database encryption will be turned off. You can turn it on again in Settings afterwards." (`restore_encryption_turns_off`) |
| Buttons | "Cancel" → `answerConfirmation(false)` · "Restore" (`action_restore`, destructive) → `answerConfirmation(true)` |

From the KeyUnavailable screen `resultingMode` is always `PLAIN`, and `preview.encryptionTurnsOff` is true when the replaced database was encrypted (the same condition as `Restored.encryptionTurnedOff`, [db-lifecycle.md § Interface and status](db-lifecycle.md#interface-and-status)). The dialog then says that the data will be stored unencrypted and that encryption will be turned off, and never that the security settings stay as they are: DEC-84 ("Restore never changes security settings") and DEC-86 meet here, and the confirmation must state the resulting encryption state truthfully.

Errors (the live data and the key state are untouched in every case):

| Part | Text |
|---|---|
| Title | "This file cannot be restored" (`restore_error_title`) |
| Body | The message for the result (table below), then "Your current data was not changed." (`restore_error_unchanged`) |
| Button | "OK" (`action_ok`) |

| Result | Message |
|---|---|
| `InvalidBackup(NotABackup)` | "This file is not a Doro Organizer backup." (`restore_error_not_backup`) |
| `InvalidBackup(NewerVersion)` | "This backup was made by a newer version of Doro Organizer. Update the app, then try again." (`restore_error_newer`) |
| `InvalidBackup(Incompatible)` | "This backup does not match this version of Doro Organizer." (`restore_error_incompatible`) |
| `InvalidBackup(Damaged)` | "This backup is damaged or incomplete." (`restore_error_damaged`) |
| `FileAccess` | "The selected file could not be read." (`restore_error_file_access`) |
| `StorageFull`, `KeyUnavailable`, `Unexpected` | The `AppError` text (section 11.13) |
| `DatabaseUnavailable` | "The operation could not run right now. Nothing was changed. Try again." (`maintenance_unavailable`) |

`Err(OutcomeUnknown)` never uses this dialog: the claim "Your current data was not changed." would be false, because the commit may have happened. It is shown by the dialog of section 9.6 ("Please check your data") after the restart.

`BackupRejection` has exactly the four values `NotABackup`, `NewerVersion`, `Incompatible` and `Damaged`, one message each; which check yields which value is defined in [db-lifecycle.md § Restore validation](db-lifecycle.md#restore-validation). A wrong backup password is not a rejection: the password question repeats.

Success: the gate goes through `Maintenance` and back, navigation resets to All (DEC-84) and the shell shows "Backup restored" (`restore_done`) as a snackbar. When `Restored.encryptionTurnedOff` is true (a restore from the KeyUnavailable screen that replaced encrypted data), it shows instead a notice dialog titled "Backup restored" with the body "Database encryption is now off. You can turn it on again in Settings." (`restore_encryption_off_body`) and "OK".

### 8.8 Encryption and startup password (DEC-61, DEC-64)

The flows, checks and key handling are in [security.md § 4](security.md#4-key-change-flows-dec-61); every flow collects its input in one dialog. Fields and confirmation rules: section 8.1. After `WrongPassword` or `Throttled` every password field of the dialog is empty (cleared on confirm, section 8.1), the acknowledgement checkbox keeps its state, focus moves to the current-password field, and the error shows under it, so the user types the new password twice again. A wrong current password shows "Wrong password." (`password_wrong`) and counts toward the backoff; while the backoff runs, the current-password field and the confirm button are disabled and "Too many attempts. Try again in %1$s." (`password_throttled`, `m:ss` countdown, remaining time as in section 10.2) replaces the error ([security.md § 5.3](security.md#53-backoff-dec-62)).

| Flow (security.md § 4) | Title | Body | Fields | Confirm |
|---|---|---|---|---|
| Turn on encryption, `PLAIN` → `DEVICE` | "Encrypt the database?" (`enc_on_title`) | "Your notes, people and projects will be encrypted with a key kept in this device's secure hardware. Backups and shared copies made earlier are not changed. You can add a startup password later." (`enc_on_body`) | — | "Encrypt" (`action_encrypt`) |
| Set startup password, `PLAIN` → `PASSWORD` | "Set a startup password" (`pw_set_title`) | "You will enter this password every time the app starts. It cannot be recovered: if you forget it, the only way back in is to erase all data." (`pw_set_body`), then "This also encrypts the database. Backups and shared copies made earlier are not changed." (`pw_set_body_plain`) | New password (`password_new`), Repeat new password (`password_repeat`), acknowledgement | "Set password" (`action_set_password`) |
| Set startup password, `DEVICE` → `PASSWORD` | as above | `pw_set_body` only | as above | as above |
| Change startup password, `PASSWORD` → `PASSWORD` | "Change the startup password" (`pw_change_title`) | `pw_set_body` | Current password (`password_current`), New password, Repeat new password, acknowledgement | "Change password" (`action_change_password`) |
| Remove startup password, `PASSWORD` → `DEVICE` | "Remove the startup password?" (`pw_remove_title`) | "The app will open without asking for a password. The database stays encrypted on this device." (`pw_remove_body`) | Current password | "Remove password" (`action_remove_password`) |
| Turn off encryption, `DEVICE` → `PLAIN` | "Turn off encryption?" (`enc_off_title`) | "Your notes, people and projects will be stored unencrypted on this device." (`enc_off_body`) | — | "Turn off" (`action_turn_off`, destructive) |
| Turn off encryption, `PASSWORD` → `PLAIN` | as above | `enc_off_body`, then "The startup password will be removed too." (`enc_off_body_password`) | Current password | as above |

- All dialogs have "Cancel" as the dismiss button.
- Confirm calls `DbMaintenance.changeKey(target, currentPassword, newPassword)` ([db-lifecycle.md § Key changes](db-lifecycle.md#key-changes)). The dialog stays open, with its buttons disabled, while the current password is checked. `Err(WrongPassword)` and `Err(Throttled)` come back before any change: the dialog shows them inline and calls `acknowledge()` itself.
- Any other outcome closes the dialog. The operation runs under the maintenance overlay with "Updating encryption…" (section 10.6). On success the maintenance status host shows the snackbar of the flow that ran, chosen from `KeyChanged(from, to)` (mapping in section 9.6).
- On failure the old database and key stay in force (security.md § 4 step 6) and the dialog "Encryption was not changed" (`key_change_failed_title`) shows the message of section 9.6.

Backup offer before a first startup password (DEC-73). The two "Set startup password" flows (`PLAIN` → `PASSWORD`, `DEVICE` → `PASSWORD`) start with this dialog when `NoteRepository.hasAnyData()` is true ([data-layer.md § Editor loads, pickers and existence probes](data-layer.md#editor-loads-pickers-and-existence-probes)); with an empty database they open "Set a startup password" directly. "Change startup password" and "Remove startup password" never show it.

| Part | Text |
|---|---|
| Title | "Back up first?" (`pw_backup_offer_title`) |
| Body | "If you ever forget the startup password, a backup is the only way back to your notes. Make one now?" (`pw_backup_offer_body`) |
| Buttons | "Skip" (`action_skip`) · "Back up now" (`action_backup_now`) |

- "Back up now" runs the normal backup flow of section 9.2, including the unencrypted-backup warning (section 8.5) and the backup password (section 8.6) wherever DEC-83 requires them. When the backup finishes with `Ok`, "Set a startup password" opens at once. When it is cancelled or fails (after the failure message of section 9.6), this dialog appears again, so the user can retry, skip or leave.
- "Skip" opens "Set a startup password". Back or a tap outside closes the dialog, and nothing changes.
- `SettingsViewModel` keeps the pending step in `SavedStateHandle`, so the flow survives the SAF picker, rotation and process death. No password exists yet at that point, so none crosses the external activity (DEC-64).

### 8.9 Start fresh (DEC-85, DEC-86)

Opened by "Start fresh" on the Corrupt and KeyUnavailable screens.

| Part | Text |
|---|---|
| Title | "Start fresh?" (`start_fresh_title`) |
| Body | "The current database and everything in it will be deleted from this device, and the app will start empty and unencrypted. This cannot be undone. If you have a backup, choose Restore from backup instead." (`start_fresh_body`) |
| Buttons | "Cancel" · "Delete and start fresh" (`action_start_fresh_confirm`, destructive) |

Afterwards the notice of section 10.7 appears once.

### 8.10 Erase all data (DEC-63)

Opened by "Erase all data and start over" on the Forgot password screen (section 10.3).

| Part | Text |
|---|---|
| Title | "Erase all data?" (`erase_title`) |
| Body | "All notes, people and projects, the encryption key and the security settings will be deleted from this device. This cannot be undone." (`erase_body`) |
| Field | Single-line `OutlinedTextField` labelled "Type ERASE to confirm" (`erase_prompt` "Type %1$s to confirm" with `erase_word` "ERASE"); `KeyboardCapitalization.Characters`, autocorrect off |
| Buttons | "Cancel" · "Erase all data" (`action_erase`, destructive), enabled only while the trimmed input equals `erase_word` ignoring case (`Locale.ROOT`) |

The erase itself: [security.md § 5.4](security.md#54-forgotten-password-erase-dec-63).

### 8.11 System authentication prompts (DEC-65)

`AppLockController` builds the prompts ([security.md § 7.2](security.md#72-promptinfo)) with these texts:

| Prompt | Title | Negative button |
|---|---|---|
| Unlock (strong biometric, API 30+ device credential, API 28–29 confirm-credential intent) | "Unlock Doro Organizer" (`biometric_unlock_title`) | "Cancel" (`action_cancel`); none on the device-credential prompt |
| Turning the lock on (also the test prompt for `BIOMETRIC_STATUS_UNKNOWN`) | "Turn on biometric lock" (`biometric_enable_title`) | "Cancel" |
| Turning the lock off | "Turn off biometric lock" (`biometric_disable_title`) | "Cancel"; none on the device-credential prompt |

No subtitle or description is set.

### 8.12 Maintenance status host

`MaintenanceStatusHost` (`ui/common`) is the only composable that renders `DbMaintenance.status` ([db-lifecycle.md § Interface and status](db-lifecycle.md#interface-and-status)). It is composed in the shell and on the Password, Forgot password, Corrupt and KeyUnavailable screens: the places where operations start, and where a failed operation returns the gate (an Erase that fails before it changes anything returns to `NeedsPassword`, architecture.md T31d). Nothing of it is composed while the gate is `Locked`; the status waits and its dialog returns after unlocking.

| `status` | Rendering |
|---|---|
| `Idle` | Nothing |
| `Running(op, step, fraction)` while the gate is in `Maintenance` | Nothing here: the gate shows the overlay or screen (section 10.6), which reads the same status |
| `Running(op, …)` with `op` other than `Restore` while the gate is not in `Maintenance` (the precheck and the current-password check run before `MaintenanceStarted`, db-lifecycle.md § Operation frame) | Nothing; the dialog that started the operation keeps its buttons disabled (section 8.1) |
| `Running(Restore, …)` while the gate is not in `Maintenance` (a restore raises `MaintenanceStarted` only after the confirmation), `NeedsBackupPassword`, `NeedsDatabasePassword`, `NeedsConfirmation` | The restore dialogs (section 8.7) |
| `Finished(op, result)` | The outcome of section 9.6, then `acknowledge()` |

- `acknowledge()` is called after `showSnackbar` returns, or when the user closes the outcome dialog. `Finished` therefore survives rotation, a lock and a navigation reset until the user has seen it.
- Exceptions: `Finished(KeyChange, Err(WrongPassword))` and `Finished(KeyChange, Err(Throttled))` belong to the key-change dialog, which shows them inline (section 8.8) and acknowledges them itself; the host ignores them. `Ok(RestoreCancelled)` and `Err(Busy)` are acknowledged without any message.
- `Finished(Backup, Ok(BackupShared(uri, displayName)))`: the host posts the share chooser as an `ExternalRequest` (chooser title "Share backup", `share_chooser_title`) and acknowledges; there is no message (section 9.2).

## 9. Settings screen

`SettingsScreen` and `SettingsViewModel` (`ui/settings`), opened from the gear action of any perspective (`SettingsKey`, DEC-51). What each setting does is defined in [security.md § 2](security.md#2-security-settings); this section defines the screen.

### 9.1 Layout

- `Scaffold` with a `TopAppBar`: navigation icon `ic_arrow_back` "Back" (`action_back`), title "Settings" (`settings_title`). The body is a vertically scrolling column, at most 720 dp wide and centered.
- Section headers: `titleSmall` in `primary`, 16 dp side padding, `Modifier.semantics { heading() }`.
- Rows are M3 `ListItem`s with a leading 24 dp icon (decorative), a headline and a supporting text. A switch row is one `Modifier.toggleable(value, role = Role.Switch)` node whose `Switch` has `onCheckedChange = null`; its accessible name is the headline and its state comes from the switch. An action row is one `Modifier.clickable(role = Role.Button)` node.

Order of sections and rows:

| # | Row | Kind | Visible | Icon |
|---|---|---|---|---|
| | **Backup and restore** (`settings_section_backup`) | header | always | |
| 1 | "Backup all data" (`backup_all_data`, verbatim §4) · "Save all notes, people and projects to a file" (`backup_all_data_summary`) | action | always | `ic_save` |
| 2 | "Share backup" (`share_backup`) · "Send a backup file to another app" (`share_backup_summary`) | action | always | `ic_share` |
| 3 | "Restore from backup" (`restore_from_backup`, verbatim §4) · "Replace all data with a backup file" (`restore_summary`) | action | always | `ic_restore` |
| | **Security** (`settings_section_security`) | header | always | |
| 4 | No-screen-lock hint (section 9.3) | info card | `KeyguardManager.isDeviceSecure()` is false (DEC-72) | `ic_warning` |
| 5 | "Database encryption" (`encryption_title`) | switch | always | `ic_lock` |
| 6 | "Startup password" (`startup_password_title`), or the two rows "Change startup password" and "Remove startup password" | action | section 9.3 | `ic_password` |
| 7 | "Biometric lock" (`biometric_title`) | switch | section 9.4 | `ic_fingerprint` |
| 8 | "Screenshot protection" (`screenshot_title`) | switch | always | `ic_screenshot_monitor` |
| 9 | "Encrypt backups" (`encrypt_backups_title`) | switch | always | `ic_enhanced_encryption` |
| | **About** (`settings_section_about`) | header | always | |
| 10 | "Version" (`about_version_title`) · the app's `versionName` | info | always | `ic_info` |
| 11 | "Open-source notices" (`open_source_notices`) · "Licenses of the libraries in this app, including SQLCipher" (`open_source_notices_summary`) | action | always | `ic_description` |

- The visible labels of rows 1 and 3 are the §4 texts, so no separate content description is set and TalkBack reads the label (DEC-55).
- No "Clear all data" row in v1 (§8 lists it as "optional later").
- While `DbMaintenance` runs an operation, the maintenance overlay covers the screen (section 10.6), so no row can start a second one.
- `SettingsViewModel` re-reads the device-dependent inputs (biometric availability, `isDeviceSecure()`) in a `LifecycleResumeEffect`, so returning from system settings updates the screen.

### 9.2 Backup and restore rows

The mechanics (export pipeline, share copies, validation, swap) are in [db-lifecycle.md](db-lifecycle.md); launchers are registered at activity level and every external activity runs under the re-lock exemption (architecture.md § Result launchers and process death; [security.md § 7.7](security.md#77-re-lock-rules)).

"Backup all data" (primary path, DEC-83; [db-lifecycle.md § Backup](db-lifecycle.md#backup)):
1. If `DbMaintenance.backupRequiresPassword()` is false, the backup will be plaintext: the warning of section 8.5 first. "Cancel" ends here.
2. Post the `CreateDocument("application/octet-stream")` request with the suggested name `doro-organizer-backup-YYYY-MM-DD.db`, the date being `AppTime.today()` in ISO form, never localized (§11, DEC-83). A cancelled picker (null Uri) ends here without a message.
3. If a password is required: the backup password dialog of section 8.6 with "Back up". Its "Cancel" calls `DbMaintenance.discardDocument(uri)`, which deletes the document only if the picker created it (known size 0) and leaves an existing file the user chose to overwrite untouched.
4. `DbMaintenance.backupToDocument(uri, password)`; the export runs under the maintenance overlay, "Creating backup…" (section 10.6).
5. Outcome: section 9.6.

"Share backup" (secondary path, DEC-83):
1. The plaintext warning, as above, when no password is required.
2. If a password is required: the backup password dialog with "Share".
3. `DbMaintenance.backupForShare(password)`; the share copy is written under the maintenance overlay.
4. On `Finished(Backup, Ok(BackupShared(uri, displayName)))` the maintenance status host posts the system share sheet (`Intent.createChooser`, title "Share backup", `share_chooser_title`; section 8.12). The app cannot know whether the receiving app finished, so success shows no message.

"Restore from backup" (DEC-84):
1. Post the `OpenDocument` request with `*/*`. A cancelled picker ends here without a message.
2. `DbMaintenance.restore(uri, MaintenanceOrigin.Ready)`; the dialogs of section 8.7 follow its status, then the swap runs under the maintenance overlay.

### 9.3 Database encryption and startup password

| Committed key mode | "Database encryption" switch | Supporting text | Startup password row(s) |
|---|---|---|---|
| `PLAIN` | off | "Off. Notes are stored unencrypted on this device." (`encryption_summary_off`) | "Startup password" · "Off" (`startup_password_off`): opens the backup offer, then "Set a startup password" (`PLAIN` → `PASSWORD`, section 8.8) |
| `DEVICE` | on | "On. Notes are encrypted with a key kept on this device." (`encryption_summary_on`) | "Startup password" · "Off": opens the backup offer, then "Set a startup password" (`DEVICE` → `PASSWORD`, section 8.8) |
| `PASSWORD` | on | `encryption_summary_on` | "Change startup password" (`startup_password_change`) · "Asked every time the app starts" (`startup_password_on`), opening the change dialog; and "Remove startup password" (`startup_password_remove`), opening the remove dialog |

- Tapping the switch never flips it directly: off → the "Encrypt the database?" dialog; on → the "Turn off encryption?" dialog of the current mode (section 8.8). The switch shows the committed mode, so it changes only when the key change succeeds.
- The mode is `DbMaintenance.keyMode`, the mode of the committed key-state slot ([db-lifecycle.md § Interface and status](db-lifecycle.md#interface-and-status)); `ui` never reads `KeyVault` (architecture.md § Import rules). It is never null in `Ready`.
- Every dialog of section 8.8 calls `DbMaintenance.changeKey(target, currentPassword, newPassword)` with the `CharArray`s of its fields (null where a field does not exist).
- No-screen-lock hint (DEC-72): an outlined card at the top of the section with `ic_warning` and "This device has no screen lock, so anyone holding it can open its apps. Set a screen lock in system settings, or use a startup password." (`security_no_screen_lock`). Informational only: no button, nothing is blocked.
- Recents hint for encryption (API 28–32 only): when the database is encrypted (`DEVICE` or `PASSWORD`), the biometric lock is off and screenshot protection is off, the "Database encryption" row shows a second supporting line, "On this Android version the recent apps screen can still show the app's last screen, even though the database is encrypted. Turn on screenshot protection to hide it." (`encryption_recents_hint`). Below API 33 no API hides only the Recents thumbnail, and with the lock off the recents hint of section 9.4 is not shown ([security.md § 12](security.md#12-residual-risks-and-documented-limitations)); from API 33 the thumbnail is hidden whenever encryption is on (DEC-66).

### 9.4 Biometric lock

Availability comes from `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`, read through `AppLockController` on every resume ([security.md § 7.1](security.md#71-availability-and-the-settings-row)):

| Result | Lock off | Lock on |
|---|---|---|
| `BIOMETRIC_SUCCESS` | Row shown, switch enabled | Row shown, switch enabled |
| `BIOMETRIC_STATUS_UNKNOWN` | Row shown, switch enabled; the turn-on prompt doubles as the test prompt | Row shown, switch enabled |
| `BIOMETRIC_ERROR_NONE_ENROLLED` | Row shown, switch disabled; supporting text "No fingerprint or face is set up for unlocking." (`biometric_none_enrolled`) and, below it in the `supportingContent`, a `TextButton` "Set up" (`action_enroll`) that opens the enrollment screen. The row's `toggleable` is disabled, the Switch stays in the trailing slot, and the button is its own focusable node | Row shown, switch enabled (turning off uses the device credential) |
| Any other code | Row hidden | Row shown, switch enabled |

- Supporting text in the normal case: "Ask for your fingerprint or face when the app opens or comes back from the background" (`biometric_summary`).
- On API 28–32, while the lock is on and screenshot protection is off, a second supporting line reads "On this Android version the recent apps screen can still show the app's last screen. Turn on screenshot protection to hide it." (`biometric_recents_hint`) ([security.md § 12](security.md#12-residual-risks-and-documented-limitations)).
- Turning on: the "Turn on biometric lock" prompt (section 8.11); success sets the switch. Turning off: the "Turn off biometric lock" prompt on the unlock route (strong biometric or the device-credential fallback); success clears it. A cancel leaves the switch unchanged without a message; any other failure shows "Authentication failed. The setting was not changed." (`error_auth_failed`) in a Long snackbar.
- "Set up" launches the enrollment intent of security.md § 7.1; returning re-reads the availability.
- Auto-off notice ([security.md § 7.6](security.md#76-device-credential-fallback-q7)): when `security.lock_auto_off_notice` is true, the shell (section 2.4) shows once: title "Biometric lock turned off" (`lock_auto_off_title`), body "This device no longer has a screen lock, so the biometric lock was turned off. Set a screen lock in system settings to use it again." (`lock_auto_off_body`), button "OK"; "OK" clears the flag.

### 9.5 Screenshot protection and Encrypt backups

| Row | Supporting text | Behavior |
|---|---|---|
| "Screenshot protection" | "Blocks screenshots and screen recording, shows a blank screen when sharing the screen, hides the app's content in recent apps, and asks the keyboard not to learn what you type" (`screenshot_summary`) | Toggles at once; the window flag changes live without recreating the activity (DEC-67, [security.md § 8](security.md#8-screen-protection-dec-66-dec-67)); keyboard learning follows DEC-71 |
| "Encrypt backups", database `PLAIN` | "Protect backup files with a separate password" (`encrypt_backups_summary`) | Toggles at once (DEC-83) |
| "Encrypt backups", database encrypted | "Always on while the database is encrypted" (`encrypt_backups_forced`) | Shown on and disabled; the stored value is kept and applies again after encryption is turned off (security.md § 2) |

### 9.6 Outcome messages

The maintenance status host (section 8.12) renders every `Finished(op, result)`:

| `op` | `Ok(…)` | `Err(…)` |
|---|---|---|
| `Backup` | `BackupSaved`: snackbar "Backup saved" (`backup_done`). `BackupShared`: the share sheet opens, no message | Dialog "Backup failed" (`backup_failed_title`) with the message below and "OK"; with `previousContentLost` the message is `backup_error_overwritten` whatever the error |
| `Restore` | `Restored`: snackbar "Backup restored" (`restore_done`) on All, or the encryption-off notice (section 8.7). `RestoreCancelled`: nothing | The restore error dialog (section 8.7) |
| `KeyChange` | `KeyChanged(from, to)`: the snackbar of the mapping below | `WrongPassword`, `Throttled`: inline in the dialog (section 8.8). `OutcomeUnknown`: the dialog below. Others: dialog "Encryption was not changed" (`key_change_failed_title`) with the message below and "OK" |
| `StartFresh` | `StartedFresh`: the Start-fresh notice (section 10.7) | Dialog on the Corrupt or KeyUnavailable screen: title "Start fresh failed" (`start_fresh_failed_title`), the message below, "OK" |
| `Erase` | `Erased`: nothing; the app is in the first-run state | Dialog on the Password or Forgot password screen, whichever is shown: title "Erase failed" (`erase_failed_title`), the message below, "OK" |

Key-change snackbars, by `KeyChanged(from, to)` (the target alone cannot tell "set" from "changed", nor "turned on" from "password removed"):

| `from` → `to` | Snackbar |
|---|---|
| `PLAIN` → `DEVICE` | "Database encryption turned on" (`enc_on_done`) |
| `PLAIN` → `PASSWORD`, `DEVICE` → `PASSWORD` | "Startup password set" (`pw_set_done`) |
| `PASSWORD` → `PASSWORD` | "Startup password changed" (`pw_changed_done`) |
| `PASSWORD` → `DEVICE` | "Startup password removed" (`pw_removed_done`) |
| `DEVICE` → `PLAIN`, `PASSWORD` → `PLAIN` | "Database encryption turned off" (`enc_off_done`) |

Message of a failure dialog:
- `FileAccess` from a backup: "The backup file could not be written. If an incomplete file remains at the chosen location, delete it." (`backup_error_file_access`); from a restore: section 8.7.
- `DatabaseUnavailable` while the gate shows the Corrupt screen (the live database failed `quick_check` before a backup or key change, so nothing was exported or changed; DEC-83, DEC-85): for a backup "The database is damaged, so no backup was made. Your earlier backups are not affected." (`backup_error_damaged`); for a key change "The database is damaged, so nothing was changed." (`key_change_error_damaged`).
- `previousContentLost` on a failed backup to a document (the chosen file already existed and the stream had been opened): "The backup could not be completed, and the previous content of the file you chose was lost. Delete the incomplete file if it remains, choose another location and try again." (`backup_error_overwritten`).
- `DatabaseUnavailable` of any other operation or screen (the frame could not clear its temporary files, an Erase aborted because a generation could not be deleted, the holder was no longer open): "The operation could not run right now. Nothing was changed. Try again." (`maintenance_unavailable`).
- `Err(OutcomeUnknown)` of any operation: the dialog "Please check your data" (`outcome_unknown_title`) with `error_outcome_unknown` and "OK". It replaces the dialogs above, so `restore_error_unchanged` and `key_change_failed_title` never appear with it. The status stays `Finished` through the `Restart` that follows (same process), so the host shows the dialog once the shell or the screen that follows is composed; the user must not be led to erase anything because of it (after a password change the app may ask for the new password or the old one; the text says to try the new one first).
- Anything else: the `AppError` text (section 11.13).

Outcomes of operations started in `Ready` are shown by the host in the shell; outcomes of Start fresh, Erase and of restores started on a gate screen are shown by the host on that screen, or by the shell's host when the operation ended in `Ready` after a restart.

### 9.7 Open-source notices

- "Open-source notices" opens a full-screen `Dialog` (`DialogProperties(usePlatformDefaultWidth = false)`) with its own `TopAppBar` (`ic_close` "Close" `action_close`, title "Open-source notices") and a scrolling text read from `res/raw/open_source_notices.txt` (UTF-8, `bodySmall`, selectable).
- The file lists, for every third-party component in the release APK, its name, copyright line and full license text. It must include SQLCipher for Android (Zetetic LLC, BSD-style license, the attribution SQLCipher requires), SQLite (public domain), the AndroidX, Jetpack Compose, Kotlin and kotlinx libraries (Apache License 2.0) and the Material Symbols icons (Apache License 2.0).
- Verify at setup: the exact license files shipped with `net.zetetic:sqlcipher-android` (including any bundled cryptography library) and the full `releaseRuntimeClasspath`; regenerate the file whenever a dependency changes. There is no licenses plugin: Google's `oss-licenses` plugin depends on Play Services, which DEC-68 bans.

### 9.8 Settings state

`SettingsViewModel` combines, without any database access:
- `SettingsStore.settings` (screenshot protection, biometric lock, encrypt backups, the lock auto-off notice, the unlock backoff values);
- the committed key mode (section 9.3);
- biometric availability and `isDeviceSecure()` from `AppLockController`, refreshed on resume;
- `DbMaintenance.status` for running operations, restore phases and outcomes (section 8.7).

The open dialog is part of its UiState, saved as a String key like `ListDialog` (section 3.10); password texts never are (section 8.1).

## 10. Gate screens

`GateScreen(gate, graph)` in `ui/gate` renders every gate state except `Ready` (architecture.md § Rendering). The logic behind them (when each state is entered, unlock, backoff, biometric routing, recovery) is in [architecture.md § SessionGate](architecture.md#sessiongate) and [security.md § 5 and § 7](security.md#5-unlock-errors-and-erase); this section defines only the screens.

### 10.1 Common rules

- No gate screen shows note-derived data: no previews, names, counts or dates (DEC-66). The only dynamic texts are the app's own messages, system biometric error strings and the backoff countdown.
- Layout: a centered column, at most 400 dp wide, padded by the safe-drawing insets and scrollable, so it works at 200 % font and in landscape. Top to bottom: the app logo (`R.drawable.ic_app_logo`, the launcher foreground as a vector, 72 dp, or 96 dp on the lock screen (section 10.4), decorative), the title (`headlineSmall`, a heading), the body (`bodyLarge`), controls, then secondary actions.
- The root sets `paneTitle` to the screen title.
- Messages are inline texts or dialogs; gate screens have no snackbar host.
- System Back on a gate screen is not intercepted, except on the Forgot password screen (section 10.3) and on the maintenance screen (section 10.6), which consume it with `BackHandler(enabled = true)` like the overlay. Uncaught, Back moves the task to the background on API 31+ and finishes `MainActivity` on API 28–30 ([architecture.md § Back behavior](architecture.md#back-behavior)); `SessionGate` is application-scoped, so reopening the app shows the same gate state.
- Overlay hiding on API 31+ is not done by the gate screens: `MainActivity` applies it from the window policy, which hides overlays in every gate state except `Ready` and `Maintenance` with origin `Ready` ([security.md § 9.4](security.md#94-task-hijacking-and-overlays), [architecture.md § Dependency injection](architecture.md#dependency-injection)). Security dialogs also set it on their own windows (section 8.1).

| Gate state | Screen |
|---|---|
| `Starting` | Section 10.8 |
| `NeedsPassword` | Password screen (section 10.2), Forgot password screen (section 10.3) |
| `Locked` (any `resume`) | Lock screen (section 10.4) |
| `Opening` | Section 10.5 |
| `Maintenance` | Overlay or screen (section 10.6) |
| `Corrupt`, `KeyUnavailable` | Section 10.7 |

### 10.2 Password screen

Shown in `NeedsPassword` and, busy, in `Opening(viaPassword = true)` (DEC-62).

| Element | Content |
|---|---|
| Title | "Enter your password" (`gate_password_title`) |
| Body | "Doro Organizer is protected by a startup password." (`gate_password_body`) |
| Field | `PasswordField` (section 8.1) labelled "Password" (`password_label`), with show/hide; focused with the keyboard open when the screen appears; `ImeAction.Done` submits |
| Primary button | Filled `Button` "Unlock" (`action_unlock`), full width, enabled while the field is not empty and no backoff runs |
| Secondary | `TextButton` "Forgot password?" (`gate_forgot`) |

| State (from `NeedsPassword(failures, retryAt, couldNotOpen)`) | Shows |
|---|---|
| First attempt | No message |
| After a wrong password | "Wrong password." (`password_wrong`) as the field's error text |
| Backoff running (`retryAt` in the future) | Field and "Unlock" disabled; "Too many attempts. Try again in %1$s." (`password_throttled`) as the field's supporting text, with the remaining time as `m:ss`: `retryAt` minus the current `AppTime` reading, recomputed every second. `retryAt` and `AppTime.elapsedMillis()` share one clock base, which [security.md § 5.3](security.md#53-backoff-dec-62) and [architecture.md § Error model](architecture.md#error-model) fix; the screen never converts between bases. The text is not a live region; when the backoff starts, the screen announces it once through `Announcer`. "Forgot password?" stays enabled ([security.md § 5.3](security.md#53-backoff-dec-62)) |
| `couldNotOpen` | Above the field, in `error` color: "Your password was accepted, but the database could not be opened. Enter it again to try again." (`gate_could_not_open`) |
| Busy (`Opening(viaPassword)`) | Field empty and disabled; in place of the button a 24 dp `CircularProgressIndicator` with "Unlocking…" (`gate_unlocking`); "Forgot password?" disabled |

- Submit copies the text into a `CharArray`, clears the field and dispatches `PasswordSubmitted` (architecture.md T7; [security.md § 6.2](security.md#62-fields-and-state)).
- Rotation and process death empty the field; the error, the backoff and the busy state come back from the gate state (`NeedsPassword(failures, retryAt, couldNotOpen)`, `Opening(viaPassword)`), which `SessionGate` fills from the persisted values. The screen reads nothing else (architecture.md § SessionGate, invariant 2).
- The screen composes the maintenance status host (section 8.12): a failed erase returns the gate to `NeedsPassword`, and its "Erase failed" dialog appears here or on the Forgot password screen.

### 10.3 Forgot password screen (DEC-63)

- Reached from "Forgot password?". The flag that selects it is a `rememberSaveable` of `GateScreen`, outside the per-state branches, so it survives the round trip `NeedsPassword` → `Maintenance(Erase)` → `Starting` → `NeedsPassword` of a failed erase (architecture.md T31d) and the user returns to this screen with the failure dialog. It holds no secret and is cleared when the gate reaches any state other than those. Back, or the navigation icon, returns to the password screen.
- `TopAppBar`: `ic_arrow_back` "Back", title "Forgot password?" (`forgot_title`).
- Body: "The password cannot be recovered or reset, and without it the data in this app cannot be opened.\n\nYou can erase all data and start over. Afterwards you can restore a backup from Settings; an encrypted backup needs the password it was made with." (`forgot_body`, §8).
- A filled `Button` in `error` colors: "Erase all data and start over" (`action_erase_start_over`). It opens the typed confirmation of section 8.10, whose confirm button calls `DbMaintenance.erase()`. No password or biometric is asked (security.md § 5.4).
- The screen composes the maintenance status host (section 8.12), which shows a failed erase.
- After the erase the app shows the maintenance screen "Erasing all data…" and then lands in the first-run state: an empty, unencrypted database on the All tab, with Restore available in Settings.

### 10.4 Lock screen

Shown in `Locked`, whatever the `resume` state (DEC-65, DEC-66).

| Element | Content |
|---|---|
| Logo | The app logo at 96 dp (the only exception to the 72 dp of section 10.1) |
| Title | "Doro Organizer" (`app_name`) |
| Body | "Doro Organizer is locked." (`gate_locked_body`) |
| Primary button | Filled `Button` "Unlock" (`action_unlock`): asks `AppLockController` to prompt now |
| Error text | Below the button, in `error` color with `liveRegion = Polite`, only for the codes that [security.md § 7.4](security.md#74-unlock-route-and-error-mapping) shows: the system's `errString` (already localized by the system) |

- The automatic prompt on entering `Locked` and on returning to the foreground is started by `AppLockController` from the activity's `ON_RESUME`, never from composition (security.md § 7.3). The screen only offers "Unlock" for a manual retry.
- The biometric prompt, the device-credential prompt and the API 28–29 confirm-credential screen are system UI with the titles of section 8.11.

### 10.5 Opening

| `Opening` variant | Screen |
|---|---|
| Plain, without error | Blank `surface`; after 500 ms a centered `CircularProgressIndicator` with content description "Loading" (`a11y_loading`) |
| `viaPassword` | The password screen, busy (section 10.2) |
| `transientError` | Open error screen: `ic_error` in `error` color, title "The database could not be opened" (`gate_open_error_title`), body "Something prevented opening your data, for example full storage, or the app was replaced by an older version. Your data was not changed. Free up space or install the latest version, then try again." (`gate_open_error_body`; an app downgrade, `user_version` above the app's, lands here too, [db-lifecycle.md § Failure classification](db-lifecycle.md#failure-classification)), filled `Button` "Try again" (`action_try_again`), which dispatches `Retry` (architecture.md T20) |

### 10.6 Maintenance overlay and screen

| `Maintenance` origin | Rendering |
|---|---|
| `Ready` | The shell stays composed and the overlay covers the whole window (architecture.md § Rendering) |
| `Corrupt`, `KeyUnavailable`, `NeedsPassword` | The same content as a standalone screen |

- The overlay is opaque (`surface` color), so no note is visible while an operation runs, also when the app is backgrounded during it ([security.md § 7.7](security.md#77-re-lock-rules)). It consumes all pointer input and Back (`BackHandler(enabled = true)`), and it has no cancel button: operations finish or roll back (DEC-87).
- Blocking pointer input is not enough: the shell stays composed under the overlay, and accessibility services and a hardware keyboard bypass pointer input, so TalkBack or Switch Access could still read note text and start a write while `DatabaseHolder` is in exclusive mode (`DatabaseUnavailable` errors under the overlay). While the gate is `Maintenance` with origin `Ready`, `DoroRoot` therefore applies `Modifier.clearAndSetSemantics {}` and `Modifier.focusProperties { canFocus = false }` to the container of `MainContent()` (a modifier on a container that is always present, never a wrapper that appears and disappears, so the single call site of [architecture.md § Rendering](architecture.md#rendering) keeps all state). The overlay root is `Modifier.fillMaxSize().focusRequester(r).focusable().onPreviewKeyEvent { true }` with `LaunchedEffect(Unit) { r.requestFocus() }`, and it sets `paneTitle` to the operation text. Verify at setup: that `focusProperties` on a container removes keyboard focus from every descendant in the pinned Compose, with TalkBack and a hardware keyboard (testing.md M-07).
- Content, centered: an indeterminate `CircularProgressIndicator` (or a determinate `LinearProgressIndicator` when `DbMaintenance.status` reports a fraction), the operation text in `titleMedium` with `liveRegion = Polite`, and "Keep the app open until this finishes." (`maintenance_keep_open`).

| Operation | Text |
|---|---|
| `Backup` (both paths) | "Creating backup…" (`maintenance_backup`) |
| `Restore` | "Restoring backup…" (`maintenance_restore`) |
| `KeyChange` | "Updating encryption…" (`maintenance_key_change`) |
| `StartFresh` | "Starting fresh…" (`maintenance_start_fresh`) |
| `Erase` | "Erasing all data…" (`maintenance_erase`) |

- Contract with [db-lifecycle.md](db-lifecycle.md): `MaintenanceStarted` is raised only after the user has answered every dialog of the operation (password, confirmation). A restore's staging, validation, password and confirmation phases run while the gate keeps its state, under the dialogs of section 8.7; only the swap runs under this overlay.

### 10.7 Corrupt and KeyUnavailable screens

| Element | `Corrupt` (DEC-85) | `KeyUnavailable` (DEC-86) |
|---|---|---|
| Icon | `ic_error`, `error` color | `ic_key_off`, `error` color |
| Title | "The database is damaged" (`gate_corrupt_title`) | "The database cannot be unlocked" (`gate_key_title`) |
| Body | "Doro Organizer cannot read its database. The file is kept until you choose what to do: restore a backup, or start fresh with an empty database." (`gate_corrupt_body`) | "The key that protects your data could not be read from this device's secure storage. This can happen after a system update or a security reset. Without the key the data cannot be opened. Restore a backup, or start fresh with an empty database." (`gate_key_body`) |
| Primary | Filled `Button` "Restore from backup" (`restore_from_backup`) | same |
| Secondary | `OutlinedButton` "Start fresh" (`action_start_fresh`), `error` content color | same |

- These screens appear only after the authentication the gate requires: the biometric lock when it is on (architecture.md T5, T13, T17, and T23 with `lockPending`; T34 locks them again after a return from the background), and in `PASSWORD` mode the startup password, except when the key state cannot be read (T6) (DEC-85; [db-lifecycle.md § The two screens](db-lifecycle.md#the-two-screens)).
- Both screens compose the maintenance status host (section 8.12), so restore dialogs and failure dialogs appear over them; the screen stays in place until the user confirms ([db-lifecycle.md § Corruption and KeyUnavailable recovery](db-lifecycle.md#corruption-and-keyunavailable-recovery)).
- "Restore from backup" runs the restore of section 9.2 from this screen: the same picker, `DbMaintenance.restore(uri, origin)` with the screen's own state (`Corrupt` or `KeyUnavailable`) and the dialogs of section 8.7 (from Corrupt in `PASSWORD` mode including the startup password question), then the maintenance screen, then a fresh start of the gate. The file is never deleted unless the user confirms a restore or "Start fresh" (DEC-85, DEC-86).
- "Start fresh" opens the confirmation of section 8.9, whose confirm button calls `DbMaintenance.startFresh(origin)` with the screen's own state (`Corrupt` or `KeyUnavailable`); the maintenance screen then shows "Starting fresh…".
- Start-fresh notice: on `Finished(StartFresh, Ok(StartedFresh))`, once the app is back in `Ready`, the shell's maintenance status host shows once: title "Started fresh" (`start_fresh_done_title`), body "You are starting with an empty database. Database encryption is off; you can turn it on in Settings." (`start_fresh_done_body`), button "OK", which calls `acknowledge()` (DEC-85). The wording is true whatever the previous mode was. The notice lives in memory: a process death right after Start fresh loses it, while Settings shows encryption off anyway.

### 10.8 Starting

- At activity creation the system splash screen stays up until the window policy is applied and the gate has left `Starting` ([architecture.md § MainActivity startup order](architecture.md#mainactivity-startup-order)). The splash shows the app logo on the theme background (section 13.6).
- If `Starting` is shown after the splash has gone (a restart after a recovery operation), the screen is a blank `surface` without progress or text.

## 11. Strings

Every user-visible text of the app. Rules (DEC-55):
- All texts live in `res/values/strings.xml` (strings and `<plurals>`); English only in v1. Composables never contain literal text, and counts always use plurals (`pluralStringResource`). Separators that appear between user data (", ", " · ", " – ") are resources too, so a later translation is resource-only.
- "Source" says where a text is fixed: "verbatim §n" or "verbatim DEC-nn" texts must stay exactly as written (testing.md I-34 asserts them as literals); "DEC-nn" texts implement a decision whose exact wording is set here; "design" texts are this document's choice. Tests copy literals from this table (testing.md § 1).
- XML escaping: write `\'` for an apostrophe and `\"` for a double quote; "…" is the single character U+2026, "–" is U+2013, "·" is U+00B7.
- In plurals, the `one` item also uses `%d` (or `%1$d`), never a hard-coded "1". The one exception is `impact_none_orphaned` and `impact_all_orphaned` (section 11.8), whose `one` items say "That note": they carry `tools:ignore="ImpliedQuantity"`, and a translation may use its `one` form only where the number is exactly 1.
- A text that starts or ends with a space is wrapped in double quotes in XML so the space is kept, for example `<string name="names_separator">", "</string>`.
- Texts provided by libraries or the system (M3 date picker labels, the system biometric prompt's own texts and `errString`) are not repeated here.

### 11.1 App, navigation and shared actions

| Key | Text | Used in | Source |
|---|---|---|---|
| `app_name` | Doro Organizer | Launcher label, lock screen title | spec title |
| `tab_all` | All | Navigation item | verbatim DEC-50 (Q10) |
| `tab_people` | People | Navigation item; Individual title; batch picker tab | verbatim DEC-50 (Q10) |
| `tab_projects` | Projects | Navigation item; Project title; batch picker tab | verbatim DEC-50 (Q10) |
| `tab_calendar` | Calendar | Navigation item; Date title | verbatim DEC-50 (Q10) |
| `tab_orphans` | Orphans | Navigation item; Orphan title | verbatim DEC-50 (Q10) |
| `tab_with_count` (plurals, on the count) | one: %1$s (%2$d) · other: %1$s (%2$d) | Batch picker tab with chosen entities | design |
| `title_all` | All notes | All title | design |
| `fab_create_note` | Create new note | Create button content description | verbatim §4 |
| `action_search` | Search | App bar action | DEC-51 |
| `action_settings` | Settings | App bar action | DEC-51 |
| `action_back` | Back | Navigation icons | design |
| `action_close` | Close | Open-source notices | design |
| `action_more` | More options | Overflow buttons | design |
| `action_cancel` | Cancel | Dialogs, pickers, biometric prompts | design |
| `action_ok` | OK | Dialogs, date pickers | design |
| `action_done` | Done | Pickers, sheets | design |
| `action_delete` | Delete | Selection menu, delete dialogs | design |
| `action_continue` | Continue | Restore password dialog | design |
| `a11y_loading` | Loading | Progress indicators | design |
| `names_separator` | `, ` | Joining names; joining accessibility description parts | design |

### 11.2 Lists and rows

| Key | Text | Used in | Source |
|---|---|---|---|
| `no_data` | (No data) | Empty lists and pickers; blank-note row title | verbatim §5 |
| `action_clear_filters` | Clear filters | Empty state with active filters | design |
| `label_orphaned` | Orphaned | Row indicator | verbatim §6 |
| `label_overdue` | Overdue | Row and editor indicator | verbatim §4 |
| `a11y_mark_done` | Mark note as done | Checkbox content description while unchecked; row custom action | verbatim §4 |
| `a11y_mark_not_done` | Mark note as not done | Checkbox content description while checked; row custom action | verbatim §4 |
| `state_done` | Done | State description of a checked note | DEC-55 |
| `state_not_done` | Not done | State description of an unchecked note | DEC-55 |
| `state_selected` | Selected | Row state in selection mode | design |
| `state_not_selected` | Not selected | Row state in selection mode | design |
| `announce_marked_done` | Marked as done | Announcement after checking | DEC-54 |
| `announce_marked_not_done` | Marked as not done | Announcement after unchecking | DEC-54 |
| `a11y_open_note` | Open note | Row click label | design |
| `a11y_select_note` | Select note | Long-click label of an unselected row; click label of an unselected row in selection mode | DEC-36 |
| `a11y_deselect_note` | Deselect note | Click and long-click label of a selected row in selection mode | design |
| `a11y_row_people` | People: %1$s | Row accessibility text | design |
| `a11y_row_projects` | Projects: %1$s | Row accessibility text | design |

### 11.3 Search and filters

| Key | Text | Used in | Source |
|---|---|---|---|
| `search_hint` | Search notes | Search bar placeholder | design |
| `action_close_search` | Close search | Search bar navigation icon | design |
| `action_clear_search` | Clear search text | Search bar and picker clear buttons | design |
| `filter_person` | Person | Person chip, not set | §2 |
| `filter_project` | Project | Project chip, not set | §2 |
| `filter_date` | Date | Date chip, not set | §2 |
| `filter_date_undated` | Undated | Date chip; date filter sheet | verbatim §1 |
| `filter_any` | Any | Filter accessibility value; open range end | design |
| `date_range_both` | %1$s – %2$s | Date chip; Calendar range header | design |
| `date_range_from` | From %1$s | Date chip, open end | design |
| `date_range_until` | Until %1$s | Date chip, open start | design |
| `a11y_filter_person` | Person filter: %1$s | Person chip content description | design |
| `a11y_filter_project` | Project filter: %1$s | Project chip content description | design |
| `a11y_filter_date` | Date filter: %1$s | Date chip content description | design |
| `a11y_change_filter` | Change filter | Chip click label | design |
| `date_filter_title` | Filter by date | Date filter sheet | design |
| `date_filter_any` | Any date | Date filter sheet | DEC-28 |
| `date_filter_range` | Date range | Date filter sheet | DEC-28 |
| `date_filter_from` | From | Date filter sheet | design |
| `date_filter_until` | Until | Date filter sheet | design |
| `action_clear_from` | Clear start date | Date filter sheet | design |
| `action_clear_until` | Clear end date | Date filter sheet | design |

### 11.4 Perspectives and calendar

| Key | Text | Used in | Source |
|---|---|---|---|
| `a11y_change_person` | Change person | Individual title button | DEC-52 |
| `a11y_change_project` | Change project | Project title button | DEC-52 |
| `action_today` | Today | MonthGrid header | design |
| `label_today` | Today | Calendar selection header | design |
| `a11y_previous_month` | Previous month | MonthGrid arrow | design |
| `a11y_next_month` | Next month | MonthGrid arrow | design |
| `action_select_range` | Select range | Calendar selection header | DEC-53 |
| `action_clear_range` | Clear range | Calendar selection header | design |
| `a11y_day_click` | Show notes for this day | Day cell click label | design |
| `a11y_day_today` | today | Day cell description part | design |
| `a11y_day_has_notes` | has notes | Day cell description part | design |
| `a11y_day_range_start` | start of range | Day cell description part | design |
| `a11y_day_range_end` | end of range | Day cell description part | design |
| `a11y_day_in_range` | in range | Day cell description part | design |

### 11.5 Selection mode and batch actions

| Key | Text | Used in | Source |
|---|---|---|---|
| `selected_count` (plurals) | one: %d selected · other: %d selected | Contextual bar title | DEC-36 |
| `action_exit_selection` | Exit selection | Contextual bar navigation icon | design |
| `action_select_all` | Select all | Contextual bar | §7 |
| `action_associate` | Associate | Contextual bar; batch picker confirm | §7 |
| `action_set_date` | Set date… | Contextual bar menu | §7 |
| `dialog_set_date_title` | Set date | Batch date picker title | design |
| `action_remove_date` | Remove date | Batch date picker; editor date field | DEC-36 |
| `batch_associated` (plurals) | one: %d note associated · other: %d notes associated | Snackbar | DEC-36 |
| `batch_date_set` (plurals) | one: Date set on %d note · other: Date set on %d notes | Snackbar | DEC-36 |
| `batch_date_removed` (plurals) | one: Date removed from %d note · other: Date removed from %d notes | Snackbar | DEC-36 |
| `batch_deleted` (plurals) | one: %d note deleted · other: %d notes deleted | Snackbar after batch delete and editor delete | DEC-36 |
| `dialog_batch_delete_title` (plurals) | one: Delete %d note? · other: Delete %d notes? | Batch delete dialog | DEC-35 |
| `dialog_irreversible` | This cannot be undone. | Delete dialogs, impact dialog | DEC-35 |

### 11.6 Editor

| Key | Text | Used in | Source |
|---|---|---|---|
| `editor_title_new` | New note | Editor title | design |
| `editor_title_edit` | Edit note | Editor title | design |
| `action_save` | Save | Editor action | DEC-37 |
| `content_hint` | Title / content | Content field placeholder | verbatim DEC-39 |
| `a11y_associate` | Associate persons and projects | Association area content description | verbatim §4 |
| `assoc_people` | People | Association group label; unlink sheet section | design |
| `assoc_projects` | Projects | Association group label; unlink sheet section | design |
| `action_add_person` | Add person | Association chip | design |
| `action_add_project` | Add project | Association chip | design |
| `a11y_remove_link` | Remove %1$s | Link chip content description | design |
| `a11y_set_date` | Set or change date | Date field content description | verbatim §4 |
| `date_none` | No date | Date field without a date | design |
| `editor_done_label` | Done | Label next to the editor checkbox | design |
| `menu_unlink` | Unlink person or project… | Overflow menu | §3 |
| `menu_delete_person` | Delete person… | Overflow menu | DEC-34 |
| `menu_delete_project` | Delete project… | Overflow menu | DEC-34 |
| `menu_delete_note` | Delete note | Overflow menu | DEC-35 |
| `unlink_title` | Unlink person or project | Unlink sheet title | §3 |
| `action_unlink` | Unlink | Unlink sheet rows | §3 |
| `a11y_unlink_entity` | Unlink %1$s | Unlink button content description | design |
| `unlink_now_orphan` | This note has no person or project left and will move to Orphans. | Unlink sheet | §3 |
| `dialog_delete_note_title` | Delete this note? | Delete note dialog | DEC-35 |
| `person_deleted` | Person deleted | Snackbar after a person delete | design |
| `project_deleted` | Project deleted | Snackbar after a project delete | design |
| `note_gone` | This note no longer exists. | Snackbar when the editor closes on a vanished note | design |
| `content_truncated` | Notes are limited to 200,000 characters. The text was cut at that limit. | Snackbar on truncation | DEC-24 |
| `editor_save_failed` | The note could not be saved. %1$s | Snackbar when a save on leave fails; argument: the `AppError` text | design |

### 11.7 EntityPicker

| Key | Text | Used in | Source |
|---|---|---|---|
| `picker_title_add_people` | Add people | `EditorLinks` for persons | design |
| `picker_title_add_projects` | Add projects | `EditorLinks` for projects | design |
| `picker_title_associate` | Associate | `BatchAssociate` | §7 |
| `picker_title_choose_person` | Choose a person | `PerspectivePrimary` for persons | §8 |
| `picker_title_choose_project` | Choose a project | `PerspectivePrimary` for projects | §8 |
| `picker_title_filter_person` | Filter by person | `SecondaryFilter` for persons | design |
| `picker_title_filter_project` | Filter by project | `SecondaryFilter` for projects | design |
| `picker_title_delete_person` | Delete person | `DeleteEntity` for persons | DEC-34 |
| `picker_title_delete_project` | Delete project | `DeleteEntity` for projects | DEC-34 |
| `picker_search_people` | Search people | Picker search placeholder | design |
| `picker_search_projects` | Search projects | Picker search placeholder | design |
| `picker_any_person` | Any person | `SecondaryFilter` first row | DEC-28 |
| `picker_any_project` | Any project | `SecondaryFilter` first row | DEC-28 |
| `picker_create` | Create "%1$s" | Create row without an exact match | DEC-32 |
| `picker_create_another` | Create another "%1$s" | Create row with a case-insensitive exact match | verbatim DEC-32 |
| `picker_entity_meta` (plurals, on the count) | one: %1$d note · added %2$s · other: %1$d notes · added %2$s | Entity row supporting text | verbatim DEC-32 pattern "N notes · added <date>" |
| `picker_entity_new` | New | Supporting text of an entity created in this visit | design |
| `picker_section_co_people` | People on this project's notes | `SecondaryFilter` header (Project perspective) | design |
| `picker_section_co_projects` | Projects on this person's notes | `SecondaryFilter` header (Individual perspective) | design |
| `picker_section_other_people` | Other people | `SecondaryFilter` and `DeleteEntity` header | design |
| `picker_section_other_projects` | Other projects | `SecondaryFilter` and `DeleteEntity` header | design |
| `picker_section_linked` | Linked to this note | `DeleteEntity` header | DEC-34 |
| `picker_create_hint_person` | Type a name to create a person. | Empty picker with a create row | design |
| `picker_create_hint_project` | Type a name to create a project. | Empty picker with a create row | design |
| `picker_empty_people_hint` | People are created in a note: tap + and use Add person. | Empty picker without a create row (not `DeleteEntity`, section 7.8); People body | §3 |
| `picker_empty_projects_hint` | Projects are created in a note: tap + and use Add project. | Empty picker without a create row (not `DeleteEntity`, section 7.8); Projects body | §3 |
| `a11y_choose` | Choose | `DeleteEntity` row click label | design |

In `picker_entity_meta` the separator " · " is part of the resource; `%2$s` is the creation date in `FormatStyle.MEDIUM`. In XML, the plural items are `%1$d note · added %2$s` and `%1$d notes · added %2$s`.

### 11.8 Delete person or project

| Key | Text | Used in | Source |
|---|---|---|---|
| `dialog_delete_person_title` | Delete person "%1$s"? | Impact dialog title | DEC-34 |
| `dialog_delete_project_title` | Delete project "%1$s"? | Impact dialog title | DEC-34 |
| `impact_person_not_linked` | This person is not linked to any note. | Impact sentence A, N = 0 | DEC-34 |
| `impact_project_not_linked` | This project is not linked to any note. | Impact sentence A, N = 0 | DEC-34 |
| `impact_person_linked` (plurals on N) | one: This person is linked to %d note. · other: This person is linked to %d notes. | Impact sentence A | DEC-34 |
| `impact_project_linked` (plurals on N) | one: This project is linked to %d note. · other: This project is linked to %d notes. | Impact sentence A | DEC-34 |
| `impact_none_orphaned` (plurals on N) | one: That note keeps other links and stays out of Orphans. · other: All of them keep other links and stay out of Orphans. | Impact sentence B, M = 0 | DEC-34 |
| `impact_all_orphaned` (plurals on N) | one: That note will have no person or project left and will move to Orphans. · other: All of them will have no person or project left and will move to Orphans. | Impact sentence B, M = N | DEC-34 |
| `impact_some_orphaned` (plurals on M) | one: %d of them will have no person or project left and will move to Orphans. · other: %d of them will have no person or project left and will move to Orphans. | Impact sentence B, 1 ≤ M < N | DEC-34 |

### 11.9 Settings

| Key | Text | Used in | Source |
|---|---|---|---|
| `settings_title` | Settings | Settings title | §8 |
| `settings_section_backup` | Backup and restore | Section header | §8 |
| `settings_section_security` | Security | Section header | §8 |
| `settings_section_about` | About | Section header | design |
| `backup_all_data` | Backup all data | Settings row; its accessible name | verbatim §4 |
| `backup_all_data_summary` | Save all notes, people and projects to a file | Settings row | design |
| `share_backup` | Share backup | Settings row | DEC-83 |
| `share_backup_summary` | Send a backup file to another app | Settings row | design |
| `restore_from_backup` | Restore from backup | Settings row and its accessible name; Corrupt and KeyUnavailable buttons | verbatim §4 |
| `restore_summary` | Replace all data with a backup file | Settings row | design |
| `security_no_screen_lock` | This device has no screen lock, so anyone holding it can open its apps. Set a screen lock in system settings, or use a startup password. | Security hint card | DEC-72 |
| `encryption_title` | Database encryption | Settings row | §8 |
| `encryption_summary_off` | Off. Notes are stored unencrypted on this device. | Settings row | design |
| `encryption_summary_on` | On. Notes are encrypted with a key kept on this device. | Settings row | design |
| `startup_password_title` | Startup password | Settings row | DEC-60 |
| `startup_password_off` | Off | Settings row | design |
| `startup_password_change` | Change startup password | Settings row | §8 |
| `startup_password_on` | Asked every time the app starts | Settings row | DEC-62 |
| `startup_password_remove` | Remove startup password | Settings row | DEC-61 |
| `biometric_title` | Biometric lock | Settings row | §8 |
| `biometric_summary` | Ask for your fingerprint or face when the app opens or comes back from the background | Settings row | §11 |
| `biometric_none_enrolled` | No fingerprint or face is set up for unlocking. | Settings row, `NONE_ENROLLED` | DEC-65 |
| `action_enroll` | Set up | Settings row, `NONE_ENROLLED` | DEC-65 |
| `biometric_recents_hint` | On this Android version the recent apps screen can still show the app's last screen. Turn on screenshot protection to hide it. | Settings row, API 28–32 | security.md § 12 |
| `encryption_recents_hint` | On this Android version the recent apps screen can still show the app's last screen, even though the database is encrypted. Turn on screenshot protection to hide it. | Settings encryption row, API 28–32, encrypted, lock off, screenshot protection off | security.md § 12 |
| `screenshot_title` | Screenshot protection | Settings row | §8 |
| `screenshot_summary` | Blocks screenshots and screen recording, shows a blank screen when sharing the screen, hides the app's content in recent apps, and asks the keyboard not to learn what you type | Settings row | DEC-67, DEC-71 |
| `encrypt_backups_title` | Encrypt backups | Settings row | §8 |
| `encrypt_backups_summary` | Protect backup files with a separate password | Settings row | DEC-83 |
| `encrypt_backups_forced` | Always on while the database is encrypted | Settings row | DEC-83 |
| `about_version_title` | Version | Settings row | design |
| `open_source_notices` | Open-source notices | Settings row; notices title | design |
| `open_source_notices_summary` | Licenses of the libraries in this app, including SQLCipher | Settings row | design |
| `lock_auto_off_title` | Biometric lock turned off | Auto-off notice | DEC-65 |
| `lock_auto_off_body` | This device no longer has a screen lock, so the biometric lock was turned off. Set a screen lock in system settings to use it again. | Auto-off notice | DEC-65 |

### 11.10 Backup and restore

| Key | Text | Used in | Source |
|---|---|---|---|
| `backup_plain_title` | Back up without encryption? | Plaintext warning | DEC-83 |
| `backup_plain_body` | This backup will not be encrypted. Anyone who gets the file can read all your notes, people and projects. To protect backups with a password, turn on Encrypt backups in Settings. | Plaintext warning | DEC-83 |
| `action_backup_anyway` | Back up anyway | Plaintext warning | DEC-83 |
| `backup_password_title` | Protect the backup | Backup password dialog | DEC-83 |
| `backup_password_body` | The backup file will be encrypted with this password. You need it to restore the backup, and it cannot be recovered. A long passphrase of several words is safest. | Backup password dialog | DEC-83, security.md § 12 |
| `backup_password_label` | Backup password | Backup and restore password fields | DEC-83 |
| `backup_password_repeat` | Repeat backup password | Backup password dialog | DEC-64 |
| `action_backup` | Back up | Backup password dialog (SAF path) | design |
| `action_share` | Share | Backup password dialog (share path) | design |
| `share_chooser_title` | Share backup | Share sheet title | design |
| `backup_done` | Backup saved | Snackbar | design |
| `backup_failed_title` | Backup failed | Backup error dialog | design |
| `backup_error_damaged` | The database is damaged, so no backup was made. Your earlier backups are not affected. | Backup error dialog | DEC-83 |
| `backup_error_file_access` | The backup file could not be written. If an incomplete file remains at the chosen location, delete it. | Backup error dialog, `FileAccess` | DEC-83 |
| `backup_error_overwritten` | The backup could not be completed, and the previous content of the file you chose was lost. Delete the incomplete file if it remains, choose another location and try again. | Backup error dialog when `previousContentLost` | DEC-83, db-lifecycle.md § Backup |
| `outcome_unknown_title` | Please check your data | `Err(OutcomeUnknown)` dialog, any operation | db-lifecycle.md § Generation commit |
| `maintenance_unavailable` | The operation could not run right now. Nothing was changed. Try again. | Error dialog, `DatabaseUnavailable` of a maintenance operation | db-lifecycle.md § User-facing messages |
| `restore_checking` | Checking the backup… | Restore progress dialog | DEC-84 |
| `restore_password_title` | Encrypted backup | Restore password dialog | §11 |
| `restore_password_body` | Enter the password that was set when this backup was made. | Restore password dialog | §11 |
| `restore_password_retry` | Wrong password, or the file is not a Doro Organizer backup. | Restore password dialog after a rejected password | DEC-84 |
| `restore_db_password_title` | Enter your startup password | Restore from the Corrupt screen in `PASSWORD` mode | DEC-84 |
| `restore_db_password_body` | The restored data will be protected by your startup password. Enter it to continue. | Restore from the Corrupt screen in `PASSWORD` mode | DEC-84 |
| `restore_confirm_title` | Restore this backup? | Restore confirmation | DEC-84 |
| `restore_confirm_counts` | The backup contains %1$s, %2$s and %3$s. | Restore confirmation | DEC-84 |
| `count_notes` (plurals) | one: %d note · other: %d notes | Restore confirmation | DEC-84 |
| `count_people` (plurals) | one: %d person · other: %d people | Restore confirmation | DEC-84 |
| `count_projects` (plurals) | one: %d project · other: %d projects | Restore confirmation | DEC-84 |
| `restore_confirm_replace` | Everything now in the app will be replaced. This cannot be undone. | Restore confirmation | DEC-84 |
| `restore_result_plain` | After the restore, your data will be stored unencrypted on this device. | Restore confirmation, mode `PLAIN` | DEC-84 |
| `restore_result_device` | After the restore, your data will be encrypted on this device. | Restore confirmation, mode `DEVICE` | DEC-84 |
| `restore_result_password` | After the restore, your data will be encrypted and the app will still ask for your current startup password. | Restore confirmation, mode `PASSWORD` | DEC-84 |
| `restore_settings_kept` | Your security settings stay as they are. | Restore confirmation, when `preview.encryptionTurnsOff` is false | DEC-84 |
| `restore_encryption_turns_off` | Database encryption will be turned off. You can turn it on again in Settings afterwards. | Restore confirmation, when `preview.encryptionTurnsOff` is true (restore from the KeyUnavailable screen over encrypted data) | DEC-84, DEC-86 |
| `action_restore` | Restore | Restore confirmation | DEC-84 |
| `restore_error_title` | This file cannot be restored | Restore error dialog | §11 |
| `restore_error_file_access` | The selected file could not be read. | Restore error, `FileAccess` | DEC-84 |
| `restore_error_not_backup` | This file is not a Doro Organizer backup. | Restore error, `NotABackup` | §11 |
| `restore_error_newer` | This backup was made by a newer version of Doro Organizer. Update the app, then try again. | Restore error, `NewerVersion` | DEC-84 |
| `restore_error_incompatible` | This backup does not match this version of Doro Organizer. | Restore error, `Incompatible` | DEC-84 |
| `restore_error_damaged` | This backup is damaged or incomplete. | Restore error, `Damaged` | DEC-84 |
| `restore_error_unchanged` | Your current data was not changed. | Restore error dialog | DEC-84 |
| `restore_done` | Backup restored | Snackbar on All; title of the encryption-off notice | design |
| `restore_encryption_off_body` | Database encryption is now off. You can turn it on again in Settings. | Notice after a restore from the KeyUnavailable screen that replaced encrypted data | DEC-84, DEC-86 |

### 11.11 Passwords, encryption and authentication prompts

| Key | Text | Used in | Source |
|---|---|---|---|
| `password_label` | Password | Password screen field | design |
| `password_current` | Current password | Key-change dialogs | security.md § 4 |
| `password_new` | New password | Key-change dialogs | DEC-64 |
| `password_repeat` | Repeat new password | Key-change dialogs | DEC-64 |
| `password_ack` | I understand this cannot be recovered | Acknowledgement checkbox | verbatim DEC-64 |
| `password_show` | Show password | Password field toggle | design |
| `password_hide` | Hide password | Password field toggle | design |
| `password_too_short` | Use at least 8 characters. | Password field error | DEC-64 |
| `password_mismatch` | The passwords do not match. | Repeat field error | DEC-64 |
| `password_malformed` | This password contains characters that cannot be used. | Password field error (a lone surrogate, security.md § 6.1) | security.md § 6.1 |
| `password_wrong` | Wrong password. | Password screen, key-change and restore dialogs | DEC-62 |
| `password_throttled` | Too many attempts. Try again in %1$s. | Password screen, key-change dialogs; argument `m:ss` | DEC-62 |
| `enc_on_title` | Encrypt the database? | Key-change dialog | DEC-61 |
| `enc_on_body` | Your notes, people and projects will be encrypted with a key kept in this device's secure hardware. Backups and shared copies made earlier are not changed. You can add a startup password later. | Key-change dialog | security.md § 4 |
| `action_encrypt` | Encrypt | Key-change dialog | design |
| `pw_set_title` | Set a startup password | Key-change dialog | DEC-62 |
| `pw_set_body` | You will enter this password every time the app starts. It cannot be recovered: if you forget it, the only way back in is to erase all data. | Key-change dialogs | §8, DEC-63 |
| `pw_set_body_plain` | This also encrypts the database. Backups and shared copies made earlier are not changed. | Key-change dialog from `PLAIN` | security.md § 4 |
| `pw_backup_offer_title` | Back up first? | Backup offer before a first startup password | DEC-73 |
| `pw_backup_offer_body` | If you ever forget the startup password, a backup is the only way back to your notes. Make one now? | Backup offer | DEC-73 |
| `action_backup_now` | Back up now | Backup offer | DEC-73 |
| `action_skip` | Skip | Backup offer | DEC-73 |
| `action_set_password` | Set password | Key-change dialog | design |
| `pw_change_title` | Change the startup password | Key-change dialog | §8 |
| `action_change_password` | Change password | Key-change dialog | design |
| `pw_remove_title` | Remove the startup password? | Key-change dialog | DEC-61 |
| `pw_remove_body` | The app will open without asking for a password. The database stays encrypted on this device. | Key-change dialog | DEC-60 |
| `action_remove_password` | Remove password | Key-change dialog | design |
| `enc_off_title` | Turn off encryption? | Key-change dialog | DEC-61 |
| `enc_off_body` | Your notes, people and projects will be stored unencrypted on this device. | Key-change dialog | security.md § 4 |
| `enc_off_body_password` | The startup password will be removed too. | Key-change dialog from `PASSWORD` | DEC-60 |
| `action_turn_off` | Turn off | Key-change dialog | design |
| `enc_on_done` | Database encryption turned on | Snackbar | design |
| `pw_set_done` | Startup password set | Snackbar | design |
| `pw_changed_done` | Startup password changed | Snackbar | design |
| `pw_removed_done` | Startup password removed | Snackbar | design |
| `enc_off_done` | Database encryption turned off | Snackbar | design |
| `key_change_failed_title` | Encryption was not changed | Key-change error dialog | security.md § 4 |
| `key_change_error_damaged` | The database is damaged, so nothing was changed. | Key-change error dialog on the Corrupt screen | DEC-85 |
| `biometric_unlock_title` | Unlock Doro Organizer | Unlock prompts | DEC-65 |
| `biometric_enable_title` | Turn on biometric lock | Prompt when turning the lock on | DEC-65 |
| `biometric_disable_title` | Turn off biometric lock | Prompt when turning the lock off | DEC-65 |

### 11.12 Gate screens and notices

| Key | Text | Used in | Source |
|---|---|---|---|
| `gate_password_title` | Enter your password | Password screen | DEC-62 |
| `gate_password_body` | Doro Organizer is protected by a startup password. | Password screen | DEC-62 |
| `action_unlock` | Unlock | Password and lock screens | DEC-65 |
| `gate_forgot` | Forgot password? | Password screen | verbatim DEC-63 |
| `gate_could_not_open` | Your password was accepted, but the database could not be opened. Enter it again to try again. | Password screen, `couldNotOpen` | architecture.md T18 |
| `gate_unlocking` | Unlocking… | Password screen, busy | design |
| `forgot_title` | Forgot password? | Forgot password screen title | verbatim DEC-63 |
| `forgot_body` | The password cannot be recovered or reset, and without it the data in this app cannot be opened.\n\nYou can erase all data and start over. Afterwards you can restore a backup from Settings; an encrypted backup needs the password it was made with. | Forgot password screen | §8, DEC-63 |
| `action_erase_start_over` | Erase all data and start over | Forgot password screen | verbatim DEC-63 |
| `erase_title` | Erase all data? | Erase confirmation | DEC-63 |
| `erase_body` | All notes, people and projects, the encryption key and the security settings will be deleted from this device. This cannot be undone. | Erase confirmation | DEC-63 |
| `erase_prompt` | Type %1$s to confirm | Erase confirmation field label | DEC-63 |
| `erase_word` | ERASE | The word to type | DEC-63 |
| `action_erase` | Erase all data | Erase confirmation | DEC-63 |
| `gate_locked_body` | Doro Organizer is locked. | Lock screen | DEC-65 |
| `gate_open_error_title` | The database could not be opened | Open error screen | architecture.md T19 |
| `gate_open_error_body` | Something prevented opening your data, for example full storage, or the app was replaced by an older version. Your data was not changed. Free up space or install the latest version, then try again. | Open error screen | design |
| `action_try_again` | Try again | Open error screen | architecture.md T20 |
| `maintenance_backup` | Creating backup… | Maintenance overlay | design |
| `maintenance_restore` | Restoring backup… | Maintenance overlay | design |
| `maintenance_key_change` | Updating encryption… | Maintenance overlay | design |
| `maintenance_start_fresh` | Starting fresh… | Maintenance screen | design |
| `maintenance_erase` | Erasing all data… | Maintenance screen | design |
| `maintenance_keep_open` | Keep the app open until this finishes. | Maintenance overlay and screen | design |
| `gate_corrupt_title` | The database is damaged | Corrupt screen | §5 |
| `gate_corrupt_body` | Doro Organizer cannot read its database. The file is kept until you choose what to do: restore a backup, or start fresh with an empty database. | Corrupt screen | §5, DEC-85 |
| `gate_key_title` | The database cannot be unlocked | KeyUnavailable screen | DEC-86 |
| `gate_key_body` | The key that protects your data could not be read from this device's secure storage. This can happen after a system update or a security reset. Without the key the data cannot be opened. Restore a backup, or start fresh with an empty database. | KeyUnavailable screen | DEC-86 |
| `action_start_fresh` | Start fresh | Corrupt and KeyUnavailable screens | DEC-85 |
| `start_fresh_title` | Start fresh? | Start fresh confirmation | DEC-85 |
| `start_fresh_body` | The current database and everything in it will be deleted from this device, and the app will start empty and unencrypted. This cannot be undone. If you have a backup, choose Restore from backup instead. | Start fresh confirmation | DEC-85 |
| `action_start_fresh_confirm` | Delete and start fresh | Start fresh confirmation | DEC-85 |
| `start_fresh_failed_title` | Start fresh failed | Start fresh error dialog | DEC-85 |
| `erase_failed_title` | Erase failed | Erase error dialog | DEC-63 |
| `start_fresh_done_title` | Started fresh | Start-fresh notice | DEC-85 |
| `start_fresh_done_body` | You are starting with an empty database. Database encryption is off; you can turn it on in Settings. | Start-fresh notice | DEC-85 |

### 11.13 Errors

`ui/common` maps every `AppError` ([architecture.md § Error model](architecture.md#error-model)) to one string resource through `AppError.messageRes()`. Exception messages never reach the UI (DEC-70).

| `AppError` | Key | Text |
|---|---|---|
| `WrongPassword` | `password_wrong` | Wrong password. |
| `Throttled` | `password_throttled` | Too many attempts. Try again in %1$s. |
| `InvalidInput(rule)`: password shorter than 8 code points | `password_too_short` | Use at least 8 characters. |
| `InvalidInput(rule)`: the two new passwords differ | `password_mismatch` | The passwords do not match. |
| `InvalidInput(rule)`: malformed password | `password_malformed` | This password contains characters that cannot be used. |
| `InvalidInput(rule)`: acknowledgement missing | `error_ack_missing` | Confirm that you understand the password cannot be recovered. |
| `InvalidInput(rule)`: blank person or project name | `error_blank_name` | Enter a name. |
| `InvalidBackup(reason)` | per category | Section 8.7 |
| `StorageFull` | `error_storage_full` | Not enough free storage, so nothing was changed. Free up some space and try again. |
| `FileAccess` | `error_file_access` | The file could not be read or written. Try again, or choose another location. |
| `KeyUnavailable` | `error_key_unavailable` | The encryption key could not be accessed, so nothing was changed. Try again later. |
| `AuthFailed` | `error_auth_failed` | Authentication failed. The setting was not changed. |
| `Busy` | `error_busy` | Another operation is still running. Try again when it has finished. |
| `DatabaseUnavailable` | `error_database_unavailable` | Your data is not available right now. Try again. |
| `OutcomeUnknown` | `error_outcome_unknown` | The app could not confirm whether this change was applied, so it reloaded your data. Check that your notes are as you expect. If the app asks for a password after a password change, try the new password first, then the old one. |
| `Unexpected` | `error_unexpected` | Something went wrong. Your data was not changed. |

- Backup and restore use the more specific `backup_error_file_access` and `restore_error_file_access` for `FileAccess` (sections 9.6 and 8.7); `error_file_access` serves every other caller.
- The UI prevents the acknowledgement and blank-name cases (disabled confirm button, no create row for blank text); their strings exist so that the mapping is total.
- `InputRule` has one value per `InvalidInput` row above (`PasswordTooShort`, `PasswordMismatch`, `PasswordMalformed`, `AcknowledgementMissing`, `BlankName`); the enum and its declaration order are in [architecture.md § Error model](architecture.md#error-model).
- `AppError` has no `NotFound`: a vanished row is a success value, never an error ([architecture.md § Error model](architecture.md#error-model)).
- `Throttled(retryAtElapsedMs)` carries a time on the `AppTime.elapsedMillis()` base; the countdown text is the remaining time (`retryAtElapsedMs − AppTime.elapsedMillis()`) as `m:ss`, recomputed every second ([security.md § 5.3](security.md#53-backoff-dec-62)).

### 11.14 Formats

| Value | Format | Example (en-GB / en-US) |
|---|---|---|
| Note date in rows, editor, chips, picker meta | `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)` with the current configuration locale (DEC-26) | 17 Nov 2026 / Nov 17, 2026 |
| Calendar header date and day-cell descriptions | `FormatStyle.FULL` | Tuesday, 17 November 2026 / Tuesday, November 17, 2026 |
| Month title | `DateFormat.getBestDateTimePattern(locale, "MMMMy")` | November 2026 |
| Backup file name | `doro-organizer-backup-` + ISO `yyyy-MM-dd` of `AppTime.today()` + `.db`, never localized (DEC-83) | doro-organizer-backup-2026-11-17.db |
| Backoff countdown | `m:ss` (minutes without padding, seconds two digits) | 4:05 |
| Counts | `%d` through plurals | 12 notes |

Formatters are created per locale and cached keyed on `LocalConfiguration.current.locales[0]`; they carry no zone (architecture.md § Cross-cutting conventions).

## 12. Accessibility

Targets: TalkBack, Switch Access and hardware keyboards work on every screen; text scales to 200 %; nothing is conveyed by color alone (DEC-54, DEC-55). Strings are in section 11.

### 12.1 Note row

| Node | Semantics |
|---|---|
| Row (merged) | The row container carries `combinedClickable(...)` and `Modifier.semantics { contentDescription = rowDescription; stateDescription = …; customActions = listOf(…) }`, so it is one node (testing.md I-35). The headline `Text`, the supporting `Text`, the metadata `FlowRow` and the trailing selection mark each carry `Modifier.clearAndSetSemantics {}`, so TalkBack reads `rowDescription` and not the merged texts and the alternate texts of the inline icons. The leading checkbox keeps its own toggleable semantics, which stay a separate node. Click and long-click labels as in section 3.8. `stateDescription`: "Done" (`state_done`) for a checked note; in selection mode "Selected" or "Not selected" (`state_selected`, `state_not_selected`), followed by ", Done" for a checked note. One custom action: "Mark note as done" or "Mark note as not done" (`a11y_mark_done`, `a11y_mark_not_done`), doing what the checkbox does |
| Checkbox | A separate node (it merges its own descendants, so the row does not absorb it): `Role.Checkbox` and the toggle state from M3 `Checkbox`; `contentDescription` "Mark note as done" while unchecked and "Mark note as not done" while checked (verbatim §4); `stateDescription` "Not done" / "Done" (DEC-55) |

`rowDescription` joins these parts with ", " (`names_separator`), skipping absent ones: the title (or "(No data)"), the body text, the date (`FormatStyle.MEDIUM`), "Overdue", "People: …" (`a11y_row_people`, names joined with ", "), "Projects: …" (`a11y_row_projects`), or "Orphaned" instead of both. Example: "Weekly sync, Budget and hiring, 17 Nov 2026, Overdue, People: Alice, Bob, Projects: Alpha".

TalkBack visits the row node first and then its checkbox, so the checkbox is always heard right after the note it belongs to (testing.md M-07).

### 12.2 Other controls

| Control | Semantics |
|---|---|
| Create button | `contentDescription` "Create new note" (verbatim §4) |
| Navigation items | Label text; selected state from `NavigationSuiteScaffold` |
| App bar actions, icon buttons | `contentDescription` from section 11; decorative icons have `contentDescription = null` |
| Individual/Project title | `Role.Button`, click label "Change person" / "Change project" |
| Filter chips | Section 3.4: `Role.DropdownList`, "Person filter: Alice", click label "Change filter" |
| Association area | Group with `contentDescription` "Associate persons and projects" (verbatim §4) and `isTraversalGroup = true`; each link chip is a button "Remove Alice" |
| Date field | `Role.Button`, `contentDescription` "Set or change date" (verbatim §4), `stateDescription` = the date or "No date", plus ", Overdue" |
| Editor checkbox row | As the row checkbox (section 6.6) |
| Day cells | Section 4.4.1: full date plus "today", "has notes" and range parts; `selected`; collection item info |
| Picker rows | `Role.Checkbox` (multi-select) or `selectable` (single-select); section headers are headings |
| Settings switches | `Role.Switch` on the whole row; the headline is the name |
| Backup and restore rows | Accessible names "Backup all data" and "Restore from backup" (verbatim §4) |
| Password fields | `OutlinedSecureTextField` exposes `SemanticsProperties.Password` (testing.md L-25); the show/hide button has its own description |
| Screens | Root `paneTitle` = screen title (section 2.2); dialog titles are headings (M3 default) |

### 12.3 Announcements

| Event | Mechanism |
|---|---|
| Done toggled in a list or the editor | `Announcer`: "Marked as done" / "Marked as not done" (section 3.9) |
| Selection count changes | Contextual bar title with `liveRegion = Polite` |
| Displayed month changes | `MonthGrid` title with `liveRegion = Polite` |
| Field errors (passwords, wrong password) | Supporting text with `liveRegion = Polite` |
| Backoff starts | One `Announcer` call with the first countdown text; the per-second countdown is not a live region |
| Lock screen error | Error text with `liveRegion = Polite` |
| Maintenance operation | Operation text with `liveRegion = Polite` |
| Snackbars | M3 `Snackbar` semantics. Verify at setup: TalkBack reads a snackbar without moving focus |

### 12.4 Touch targets

- Every interactive element has a touch target of at least 48 × 48 dp (DEC-55, testing.md I-35): M3 components keep `minimumInteractiveComponentSize()`, custom clickables add it, rows are at least 56 dp (pickers, Settings) or 72 dp (notes) high.
- There is no exception (DEC-55): `MonthGrid` day columns never fall below 48 dp, so below 336 dp of available width the grid scrolls horizontally instead (section 4.4.1).
- No two touch targets overlap: the link chip as a whole is the remove target (no nested close button), and the row checkbox sits outside the row's text area.

### 12.5 Font scale

Layouts hold at 200 % font scale, including Android 14+ non-linear scaling (DEC-55, testing.md I-35, M-08):
- Text sizes come from the M3 type scale in `sp`; no code multiplies by `fontScale` or converts text to `px`, so the system's non-linear curve applies.
- Containers that hold text use minimum heights only (`heightIn(min = …)`), never fixed heights.
- One-line elements (titles, navigation labels, chip labels, the contextual title) ellipsize; body texts wrap.
- Row metadata wraps (`FlowRow`); the chip row scrolls horizontally; dialog bodies and gate screens scroll vertically; the `MonthGrid` header wraps and its cells grow.

### 12.6 Color independence and contrast

| Meaning | Non-color carrier |
|---|---|
| Overdue | The word "Overdue" plus `ic_error` (section 3.7) |
| Orphaned | The word "Orphaned" plus `ic_link_off` |
| Day has notes | A dot shape below the day number |
| Today | A ring around the day number, plus bold text |
| Selected day or range | A filled circle or band, plus `selected` semantics |
| Selected row | A trailing check mark, plus `stateDescription` |
| Checked note | Line-through title, plus the checkbox state |
| Active filter chip | The value replaces the dimension name and a leading icon appears |
| Errors | Error text, never only a red outline |

- Text uses M3 color roles on their matching surfaces (`onSurface`, `onSurfaceVariant`, `onPrimary`, `onPrimaryContainer`, `onSecondaryContainer`, `error`), which meet 4.5:1 in the baseline and dynamic schemes. Enabled text never gets an extra alpha.

### 12.7 Focus and keyboard

- Default Compose focus order (reading order). With a hardware keyboard, Tab and arrow keys reach every control; Enter activates rows and buttons; Space toggles checkboxes and switches.
- Automatic focus only in three places: the content field of a new note (section 6.3), the password field (section 10.2) and the search field when the search bar opens (section 3.3).

### 12.8 Test tags

UI tests find nodes by these `Modifier.testTag` values; texts are asserted as literals from section 11 (testing.md § 1). Tags carry ids and enum names only, never user text (DEC-70).

| Tag | Node |
|---|---|
| `tab_All`, `tab_Individual`, `tab_Project`, `tab_Date`, `tab_Orphan` | Navigation items, by `Perspective` name |
| `note_list` | The note `LazyColumn` |
| `note_row_<id>`, `note_checkbox_<id>` | A note row and its checkbox |
| `fab_create` | Create button |
| `search_field` | Search bar text field |
| `chip_Person`, `chip_Project`, `chip_Date` | Filter chips, by `FilterDimension` name |
| `month_grid`, `day_<epochDay>` | `MonthGrid` and a day cell |
| `editor_content`, `editor_people`, `editor_projects`, `editor_date`, `editor_done` | Editor parts |
| `picker_search`, `picker_create`, `picker_row_<id>`, `picker_new_<key>` | `EntityPicker` parts |
| `gate_password_field`, `gate_unlock`, `gate_forgot` | Password and lock screens |
| `maintenance_overlay` | Maintenance overlay or screen |

## 13. Visual system

### 13.1 Theme and color (DEC-56)

```kotlin
@Composable
fun DoroTheme(content: @Composable () -> Unit) {          // ui/theme
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()                          // static fallback: the M3 baseline scheme
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), shapes = Shapes(), content = content)
}
```

- The theme follows the system light/dark setting; there is no in-app theme switch in v1 (DEC-56).
- Dynamic color on API 31+; below, the M3 baseline schemes with their library default values. A brand palette later replaces only the two fallback schemes in `ui/theme`.
- Screens use color roles only (`primary`, `surface`, `onSurfaceVariant`, `error`, …), never hard-coded colors, so dynamic color, dark theme and contrast work everywhere.

### 13.2 Typography, shapes and spacing

- M3 default `Typography()` and `Shapes()`; the system font. The type roles each element uses are named in its section.
- Spacing grid of 4 dp; standard values 4, 8, 12, 16 and 24 dp. Screen side padding 16 dp.

### 13.3 Edge-to-edge and insets

- `MainActivity` calls `enableEdgeToEdge()` before `setContent` (DEC-13, architecture.md § Threading), on every API level, so API 28–34 look like API 35 and later. `windowOptOutEdgeToEdgeEnforcement` is never used (DEC-01).
- Every screen is a `Scaffold`; its top app bar takes the status-bar and cutout insets, and its content padding covers the rest (`ScaffoldDefaults.contentWindowInsets`).
- In the shell, the navigation bar or rail takes the insets on its side. Inner screen Scaffolds must not add them a second time. Verify at setup: whether the pinned `NavigationSuiteScaffold` consumes the insets its bar or rail uses; if it does not, inner Scaffolds pass `contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars)` while the bar is shown, and exclude the start side while the rail is shown. Test with gesture and 3-button navigation on API 28 and 35 (testing.md M-11).
- The create button and snackbars stay above the navigation bar and the gesture area; lists add bottom content padding so the last row scrolls clear of the button (section 2.3).
- The keyboard: `adjustResize` plus `imePadding()` in the editor (section 6.13); note lists and picker lists both apply `Modifier.imePadding()` to the list area, after `consumeWindowInsets(innerPadding)`, so every row can be scrolled above the keyboard (with the search field focused, the last rows would otherwise sit behind it); Compose dialogs keep their default handling.
- Landscape and display cutouts: content stays inside the safe-drawing insets. Verify at setup: whether `ScaffoldDefaults.contentWindowInsets` and `TopAppBarDefaults.windowInsets` of the pinned material3 include `WindowInsets.displayCutout`. If they do not, every Scaffold passes `contentWindowInsets = WindowInsets.safeDrawing` (with the same navigation-bar exclusion as above) and every `TopAppBar` passes `windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)`. Test in landscape on an emulator with a corner or side cutout.

### 13.4 Adaptive layout

- No orientation lock and no `screenOrientation` (DEC-01); the activity is resizable and works in multi-window and on foldables.
- Navigation: bar on compact width, rail otherwise, in every window height and posture (section 2.1, DEC-50).
- Maximum content widths, centered in wider windows: note lists 840 dp, editor 720 dp, Settings 720 dp, gate screens 400 dp, the `MonthGrid` 480 dp in the single-column Calendar layout.
- The Calendar uses two panes at expanded width, and at medium width with compact height (section 4.4). Every other screen is single-pane in v1.
- Layout decisions read `currentWindowAdaptiveInfo()`, never `Configuration.screenWidthDp`.
- All screen state (drafts, filters, search, selection, Calendar selection, dialogs) survives configuration changes through ViewModels and `SavedStateHandle`.

### 13.5 Icons

- Icons are Material Symbols (Outlined, weight 400, grade 0, optical size 24) imported as vector drawables `res/drawable/ic_<name>.xml`; filled variants are named `ic_<name>_filled`. There is no icon-font or icon-library dependency. Material Symbols are Apache License 2.0 and listed in the notices (section 9.7).
- Directional icons (`ic_arrow_back`, `ic_chevron_left`, `ic_chevron_right`) set `android:autoMirrored="true"` for right-to-left layouts.
- Decorative icons have `contentDescription = null`; icons that are the only content of a button take the description from section 11.

| Icon | Used for |
|---|---|
| `ic_notes`, `ic_group`, `ic_folder`, `ic_calendar_month`, `ic_link_off` (+ `_filled` where they exist) | Navigation items (section 2.1) |
| `ic_search`, `ic_settings`, `ic_arrow_back`, `ic_close`, `ic_more_vert`, `ic_check`, `ic_add` | App bars, search, pickers, create button |
| `ic_arrow_drop_down`, `ic_person`, `ic_folder`, `ic_event` | Filter chips, title button, row summaries, date field |
| `ic_error`, `ic_link_off` | Overdue and Orphaned indicators; error screens |
| `ic_check_circle_filled`, `ic_radio_button_unchecked` | Selection marks |
| `ic_select_all`, `ic_add_link`, `ic_edit_calendar`, `ic_delete` | Selection mode |
| `ic_chevron_left`, `ic_chevron_right`, `ic_date_range` | `MonthGrid` and Calendar header |
| `ic_save`, `ic_share`, `ic_restore`, `ic_warning`, `ic_lock`, `ic_password`, `ic_fingerprint`, `ic_screenshot_monitor`, `ic_enhanced_encryption`, `ic_info`, `ic_description` | Settings rows |
| `ic_key_off` | KeyUnavailable screen |

### 13.6 Launcher icon, label, splash and window theme

[security.md § 9.1](security.md#91-manifest) leaves these manifest attributes to this document:

| Where | Attribute | Value |
|---|---|---|
| `<application>` | `android:label` | `@string/app_name` |
| `<application>` | `android:icon`, `android:roundIcon` | `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round` |
| `<activity .MainActivity>` | `android:theme` | `@style/Theme.Doro.Starting` |
| `<activity .MainActivity>` | `android:windowSoftInputMode` | `adjustResize` (section 6.13) |

- Launcher icon: an adaptive icon (`res/mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml`; minSdk 28 needs no bitmap fallbacks) with `@drawable/ic_launcher_foreground` (a vector), `@color/ic_launcher_background` and a `<monochrome>` layer `@drawable/ic_launcher_monochrome` for themed icons on API 33+. Until the owner supplies artwork, the foreground is the Material Symbols `sticky_note_2` glyph on the baseline primary color. `R.drawable.ic_app_logo` (gate screens, splash) is the same glyph.
- `Theme.Doro.Starting`: parent `Theme.SplashScreen` (core-splashscreen), `windowSplashScreenBackground` = `@color/window_background`, `windowSplashScreenAnimatedIcon` = `@drawable/ic_launcher_foreground`, `postSplashScreenTheme` = `@style/Theme.Doro`.
- `Theme.Doro`: parent `android:Theme.Material.Light.NoActionBar` in `values/` and `android:Theme.Material.NoActionBar` in `values-night/`; `android:windowBackground` = `@color/window_background`. No View-based Material Components dependency is added for this.
- `@color/window_background`: `#FEF7FF` in `values/`, `#141218` in `values-night/`, the `background` of the M3 baseline light and dark schemes, so the window behind the first frame matches the fallback theme. Verify at setup: both values against `lightColorScheme().background` and `darkColorScheme().background` of the pinned material3.

### 13.7 Motion

- Navigation uses the default `NavDisplay` transitions and predictive back (DEC-01, DEC-13).
- List rows move with `Modifier.animateItem()` (section 3.9). Sheets, dialogs, menus and the snackbar use their M3 defaults.
- No custom animation is longer than 300 ms. The system "Remove animations" setting is honored automatically (animator duration scale 0).

### 13.8 Optional background image (later)

Not part of v1 (spec "Optional Visual Polish"). If it is added:
- It is decorative only: drawn behind the note lists, never behind the editor's text, the gate screens or dialogs, and with no semantics.
- A `surface` scrim at an alpha of at least 0.85 lies between the image and any content, so text keeps a contrast of at least 4.5:1 against the scrim in light and dark theme; it is checked with the darkest and the lightest region of the image.
- A bundled image comes from `assets/`. A user-picked image is copied through `OpenDocument("image/*")` into app-private storage and decoded off the main thread, downsampled to the window size; no URI is stored in `SettingsStore` ([security.md § 2](security.md#2-security-settings)), and the copy is excluded from backups like all app data (DEC-69).
- It is off by default, and screenshot protection covers it like everything else in the window (DEC-67).
