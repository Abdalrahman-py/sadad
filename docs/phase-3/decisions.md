> Phase 3 planning notes, copied from the maintainer's local planning folder on 2026-10-18 so contributors without it can read them. `plan.md`, `state.md` and `store-permissions.md` stay local.

# Answers to plan.md open questions (user, 2026-10-17)

- Q1: Delete the Java app first (ticket 39); tag `pre-kotlin-app-2026-10` keeps it.
- Q2: Approved: in-memory `LedgerState` saved as Room rows; screens read one `StateFlow<LedgerView>`;
  read model plaintext; only outbox payloads and refresh token encrypted. Record as ARCHITECTURE departure.
- Q3: minSdk 26; no desugaring.
- Q4: 34, 35, 36 block cutover 27.
- Q5: Robolectric, test-only; Claude audits a version (compileSdk 37 / AGP 9.3.3), user approves into versions.md.
- Q6: Cloud seed in Phase 3: seed a 5k-contact / 50k-op store through the API on the test project; the Phase 3 exit measures sync too (idle sync, sync time flat, Edge p95). Needs a seeding tool ticket + human run.
- Q7: Pinned lease public key (Gradle property, from smoke output after 27) + no lease survives a reboot.
- Q8: Drop nothing: port voice input, store photo editor, MonthlyProgressView art, and store-permission gating (gating needs a way for the app to read store permissions; clarifying).
- Q9: com.sadad.app release, com.sadad.app.debug debug; drop SADAD_TEST_APPLICATION_ID, SADAD_APPLICATION_ID, com.sadad.qa; version 3.0.0 (code 40); rootProject.name = "sadad".
- Q10: Logout keeps local data and unsent outbox (warns if unsent); separate "remove store from phone" deletes DB + key, refused with unsent entries unless confirmed twice.
- Q11: Recovery in Phase 3 (ticket 60); release hardening 62 waits for it.
- Q8 follow-up (store-permission gating): user asked for an explanation and options; pending.
- Q8 follow-up + device limits (user, 2026-10-17): left for later. Phase 3 is a rewrite/refactor, not new features.
  Keep store permissions, device permissions and device limits exactly as they are on the server: no new
  routes, no wiring of the five client-only store flags, no deletion, no I1–I4. The app ports the Java
  app's behaviour only (it reads installation `permissions` from the enroll response, as `SessionStore`
  did; it never read store permissions). Options A–D, E1/E2, I1–I4 stay in store-permissions.md as
  future work.
- Q12 (housekeeping commands): not answered; skip.
- Q5 follow-up (user, 2026-10-17): Robolectric 4.17 approved; ticket 40 adds the versions.md row.
