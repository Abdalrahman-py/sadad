package com.sadad.app.data.session

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The installation id the server approves. It must survive an uninstall of the same signing key,
 * or every reinstall looks like a stranger's phone and waits for an admin again.
 *
 * `Settings.Secure.ANDROID_ID` is stable per app-signing key + user since Android 8, so the same
 * APK on the same phone derives the same UUID; a cleared or unavailable value falls back to the
 * random UUID every install used to get.
 */
object DeviceIdentity {
  fun installationId(stableSeed: String?): String {
    val seed = stableSeed?.takeIf { it.isNotBlank() } ?: return UUID.randomUUID().toString()
    val bytes = "sadad:$seed".toByteArray(StandardCharsets.UTF_8)
    return UUID.nameUUIDFromBytes(bytes).toString()
  }
}
