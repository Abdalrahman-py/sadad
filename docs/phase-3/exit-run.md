# Phase 3 exit run (ticket 65) — results

Recorded as the run goes (power can cut at any time). Device runs on the emulator are a dry run:
ticket 65 asks for a real phone, so every target below still needs the phone run.

## Dry run on the emulator — 2026-10-24

- **Device:** Android Studio emulator `Medium_Phone_API_37.0` (x86_64, API 37), debug build
  `com.sadad.app.debug` 3.0.0 from `main`, against project `vhftjmiltqfwmrszdtgf` (after the
  ticket 27 cutover).
- **Watching:** full `logcat` (main, system, crash) captured for the whole run, plus a live filter
  for crashes, StrictMode, ANRs and network errors.

### Findings

Severity: **crash** (the app dies), **bug** (wrong result), **gap** (missing or weak), **env**
(this machine or emulator only).

| # | Sev | Where | What happened | Status |
| --- | --- | --- | --- | --- |
| 1 | crash | first launch | StrictMode `DiskReadViolation`: `ThemePreference.get()` built `File(filesDir, …)` on the main thread during the first frame; `AppLock`, `DueReminders` and `TrashHidden` did the same. | fixed in `2394da7` |
| 2 | crash | sign-in | StrictMode slow call: `Sessions.newAccount` generated the installation key (`AndroidKeyStore generateKeyPair`) on Main; `registerAgain` too. | fixed in `bbac32f` |
| 3 | crash | home after sign-in | StrictMode `DiskReadViolation`: `PhotoStore` resolved `filesDir` in its constructor, built by the top bar on the main thread. | fixed in `1540448` |
| 4 | crash | typing in any field | VM policy `LeakedClosableViolation` for a platform `SurfaceControl` (`SurfacePackage(Parcel)`, keyboard autofill); framework frames only, but `penaltyDeath` killed the app. | fixed in `08db311` (dies only when Sadad code is named; all logged) |
| 5 | gap | whole app | `app/src/main` has no `Log` calls: a failed sync, refused operation or network error leaves nothing in logcat. Sync state is visible only in the UI. Crash reporting is ticket 70. | open |
| 6 | env | unit tests on Windows | Three DataStore tests failed on Windows (rename over an open file); moved to Robolectric. | fixed in `7d4afa1` |
| 8 | bug | People | No screen could create a Contact: nothing navigated to `ContactForm()` without an id, although the form and `ic_person_add` existed. | fixed in `5fc7bf0` (extended FAB «إضافة شخص») |
| 9 | bug | shell wiring | `SettingsScreen()`, `HistoryScreen()` and `PaymentFormScreen()` were called without their callbacks: Trash, Recovery, Notifications, activity log, change password and add store did nothing; History could not open a Contact or Reports; the payment form stayed open after a save. | fixed in `5fc7bf0` |
| 10 | a11y | People | The add button was an unlabeled clickable (`NAF`) in the accessibility tree; TalkBack would read nothing. | fixed in `3ceb998` |
| 11 | bug | People header | «1 أشخاص مسجلين»: the plural form is used for every count (`contacts_registered` is one string, not a plurals resource). | open |
| 12 | ux | money everywhere | `formatCents` drops a trailing zero: ₪125.50 shows as «125.5 ₪». On purpose in the code, but a ledger usually shows two decimals. | open (decision) |
| 13 | ok | online write | Contact + first Debt saved from the form were `accepted` on the server 4 s later; `SyncWorker` SUCCESS; the pending mark cleared; the payment form returned to the Contact with the right balance (125.50 + 40 − 50 = 115.50). | pass |
| 14 | ui | theme | `SadadTheme` sets colors only (48 lines): no typography, fonts or shapes, and no container roles, so Material's baseline purple shows through (the add FAB, the selected sort chip and the bottom-bar indicator are lavender in a green app). The UI is to be brought back to the Stitch design. | open |
| 7 | env | build on an Arabic-locale PC | Room/KSP generated `var _argIndex: Int = ١` (Arabic-Indic digits) and failed to compile; fixed per machine with an English JVM locale in `~/.gradle/gradle.properties`. A repo-level fix (`gradle.properties` `-Duser.language=en`) would spare every Arabic-locale teammate. | open |
