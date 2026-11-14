# Play Console: Data safety answers

Draft for the owner to copy into Play Console → App content → Data safety. Pending owner approval.
Every answer matches the code at the commit that adds this file; the review notes at the bottom cite where.

## Overview questions

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes |
| Do you provide a way for users to request that their data is deleted? | Yes: email request, see the privacy page section «حذف المتجر وبياناته» (`/privacy#delete`) |
| Account creation method | Accounts are created by the store admin outside the app (username and password) |
| Delete account URL | `https://abdalrahman-py.github.io/sadad/privacy#delete` |

## Data types

"Collected" means sent off the phone. Nothing is "shared" with third parties in Play's sense: Supabase and Sentry are service providers processing data for the app.

| Category | Data type | Collected | Shared | Processed ephemerally | Required or optional | Purposes |
| --- | --- | --- | --- | --- | --- | --- |
| Personal info | Name (people in the ledger, staff name) | Yes | No | No | Required | App functionality |
| Personal info | Phone number (people in the ledger) | Yes | No | No | Optional (a person can have no phone) | App functionality |
| Personal info | Other info (ledger notes, categories) | Yes | No | No | Optional | App functionality |
| Financial info | Other financial info (debts, payments, amounts, due dates) | Yes | No | No | Required | App functionality |
| App info and performance | Crash logs | Yes | No | No | Required (not user-controllable) | Analytics (crash diagnosis) |
| App info and performance | Diagnostics (device model, Android and app version in crash reports) | Yes | No | No | Required | Analytics (crash diagnosis) |
| Device or other IDs | Device or other IDs (random installation id) | Yes | No | No | Required | App functionality, Fraud prevention, security, and compliance |

Not collected: location, contacts (only the picked contact's name and number, already counted above as ledger data), photos, files, messages, audio, calendar, web history, health, app activity, user payment info.

## Review notes (not for the Console)

- Ledger data sent to Supabase: `app/src/main/kotlin/com/sadad/app/data/net/LedgerApi.kt` (operations, enroll with `label` = `Build.MODEL` from `AppGraph.kt`, `staffName`, `publicKey`).
- Installation id is random, no ANDROID_ID: `app/src/main/kotlin/com/sadad/app/data/session/Installations.kt`.
- Crash reports only when a DSN is built in (production `v*` releases): `app/src/main/kotlin/com/sadad/app/crash/CrashReporting.kt`; scrubbing of message, extras, request, user, contexts and breadcrumbs: `SentryScrubber.kt`.
- Photos stay local: `app/src/main/kotlin/com/sadad/app/feature/photo/PhotoEditor.kt` (`PhotoStore.save`).
- Contacts: only the picked contact is saved (`feature/contactpicker/`); after the system-picker ticket the app has no contacts permission at all.
- HTTPS only: release `allowCleartextTraffic=false` (`app/build.gradle.kts`), checked by the release workflow.
- "Within 30 days" for deletion is a promise the owner makes; confirm before publishing.
