package com.sadad.app.feature.updates

/**
 * Releases are ordered by `versionCode` alone. A `versionName` cannot order them here: the team
 * build is named `dev-<run>-<sha>-team`, and Mihon's component-by-component comparison of such
 * names reports a downgrade as an update.
 */
internal fun isNewer(installedVersionCode: Int, manifest: UpdateManifest): Boolean =
    manifest.versionCode > installedVersionCode

/**
 * Snoozing hides the prompt and the notification, never the row the settings screen keeps showing:
 * a security patch must stay visible until it is installed. A different version resets the snooze.
 */
internal fun shouldPrompt(
    manifest: UpdateManifest?,
    snoozedVersionCode: Int,
    snoozedUntilMs: Long,
    nowMs: Long,
): Boolean {
  if (manifest == null) return false
  return manifest.versionCode != snoozedVersionCode || nowMs >= snoozedUntilMs
}
