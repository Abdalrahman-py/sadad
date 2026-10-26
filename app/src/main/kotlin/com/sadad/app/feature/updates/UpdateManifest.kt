package com.sadad.app.feature.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `update.json` in a release's assets: the one place the app learns what the newest build is.
 *
 * `versionCode` orders releases — the team build's `dev-<run>-<sha>-team` name carries no order, so
 * comparing names (as Seal and Mihon do) cannot work here. `sha256` and `sizeBytes` prove the APK
 * is the one that release published, which a GitHub API response alone never does.
 *
 * The manifest also carries `publishedAt`, which the app ignores: a build date orders nothing and
 * `versionCode` is the only thing that does.
 */
@Serializable
internal data class UpdateManifest(
    val channel: String,
    val versionCode: Int,
    val versionName: String,
    /** The APK's file name in the same release, never a URL. */
    val apk: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String = "",
)

internal sealed interface ManifestResult {
  data class Found(val manifest: UpdateManifest) : ManifestResult

  /** The release carries no `update.json`: an older release, or the other channel's. */
  data object Missing : ManifestResult

  /** A manifest is there but cannot be trusted: malformed, or not the one this channel expects. */
  data object Invalid : ManifestResult

  /** [status] is 0 when the request never reached the server. */
  data class Unreachable(val status: Int) : ManifestResult
}

private val ManifestJson = Json { ignoreUnknownKeys = true }
private val SHA256 = Regex("^[0-9a-f]{64}$")
private const val MAX_APK_BYTES = 200L * 1024 * 1024

/**
 * Reads a manifest and refuses anything the installer could not act on: a wrong channel, a code
 * that cannot order releases, a digest that is not a digest, or an APK name that could escape the
 * release (a path).
 */
internal fun parseManifest(text: String, channel: UpdateChannel): ManifestResult {
  val parsed =
      try {
        ManifestJson.decodeFromString(UpdateManifest.serializer(), text)
      } catch (_: RuntimeException) {
        return ManifestResult.Invalid
      }
  val sha = parsed.sha256.lowercase()
  val apk = parsed.apk
  val valid =
      parsed.channel == channel.channel &&
          parsed.versionCode > 0 &&
          parsed.versionName.isNotBlank() &&
          SHA256.matches(sha) &&
          apk.endsWith(".apk") &&
          !apk.contains('/') &&
          !apk.contains('\\') &&
          !apk.contains("..") &&
          parsed.sizeBytes in 1..MAX_APK_BYTES
  return if (valid) ManifestResult.Found(parsed.copy(sha256 = sha)) else ManifestResult.Invalid
}

/** Stored in the preferences so the settings row survives a restart without a new request. */
internal fun encodeManifest(manifest: UpdateManifest): String =
    ManifestJson.encodeToString(UpdateManifest.serializer(), manifest)
