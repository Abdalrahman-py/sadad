# Decided build and library versions

Decided 2026-10-15 by a version audit: the newest stable versions on Google Maven, Maven Central, the Gradle plugin portal and services.gradle.org, checked against the AGP release notes and Kotlin's KGP compatibility table, then proven together by a full build (`:ledger-core` test, all JVM tests, all four assemble tasks, `spotlessCheck`, Room through KSP) with no compatibility warnings. Do not pick newer versions without a new audit. Tickets 30 and 14 apply the build tools; Phase 3 tickets add the libraries.

## Why these versions

- Compose BOM 2026.09.00 = Compose 1.12.1, material3 1.4.0. Compose 1.12 needs compileSdk 37.
- AGP 9.0: max API 36.1. AGP 9.2: max API 37, Gradle ≥ 9.4.1. AGP 9.3: max API 37, Gradle ≥ 9.5.0. AGP 9.4: max API 37, Gradle ≥ 9.6.0.
- Kotlin 2.4.21 fully supports AGP only up to 9.3.1, so AGP 9.4.x is outside the tested range.
- AGP 9 has built-in Kotlin: never apply `org.jetbrains.kotlin.android`.
- SDK package for API 37 is `platforms;android-37.0`. AGP installs build-tools 36.0.0 itself.

## Versions

| Item | Version | Note |
| --- | --- | --- |
| Gradle | 9.7.1 | AGP 9.3 needs ≥ 9.5.0; KGP max tested 9.7.0 (patch above) |
| AGP | 9.3.3 | newest with API 37 inside Kotlin's tested range (9.3.1 + patches); 9.4.1 is newest overall |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | targetSdk 36 since ticket 68 |
| JDK | 21 | AGP 9 needs ≥ 17 |
| Kotlin (compose, serialization, jvm plugins) | 2.4.21 | |
| KSP | 2.3.12 | |
| Compose BOM | 2026.09.00 | |
| activity-compose | 1.13.0 | |
| lifecycle (viewmodel-compose, runtime-compose) | 2.11.0 | |
| navigation-compose | 2.10.2 | |
| Room (runtime, ktx, compiler, gradle plugin) | 2.8.5 | |
| WorkManager (work-runtime-ktx) | 2.12.0 | |
| DataStore Preferences | 1.2.1 | |
| biometric | 1.1.0 | user decision 2026-10-15: stable 1.1.0, not the 1.4.0 alpha |
| core-ktx | 1.19.1 | |
| OkHttp (+ mockwebserver) | 5.5.0 | |
| kotlinx-serialization-json | 1.11.0 | |
| kotlinx-coroutines (+ test) | 1.11.0 | |
| Spotless / ktfmt | 8.10.4 / 0.64 | |
| junit4 | 4.13.2 | |
| Robolectric | 4.17 | user approved 2026-10-17; build-proven by ticket 40 |
| Turbine | 1.2.1 | build-proven by ticket 40 |
| benchmark-macro-junit4 / baselineprofile plugin | 1.5.0 | build-proven by ticket 63 (`:benchmark:assemble`, `:app:assembleBenchmark`) |
| profileinstaller | 1.4.1 | build-proven by ticket 63 |
| sentry-android / sentry gradle plugin | 8.61.0 / 6.23.0 | Phase 4, not build-proven |
| androidx test ext junit / runner / uiautomator | 1.3.0 / 1.7.0 / 2.4.0 | uiautomator build-proven by ticket 63 |

