package com.sadad.app.feature.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A release manifest is refused unless every field can be acted on. */
class UpdateManifestTest {
  private val digest = "a".repeat(64)

  private fun json(
      channel: String = "team",
      versionCode: Int = 57,
      versionName: String = "dev-17-a1b2c3d-team",
      apk: String = "Sadad-team.apk",
      sha256: String = digest,
      sizeBytes: Long = 5_662_310,
      extra: String = "",
  ) =
      """
      {
        "channel": "$channel",
        "versionCode": $versionCode,
        "versionName": "$versionName",
        "apk": "$apk",
        "sha256": "$sha256",
        "sizeBytes": $sizeBytes,
        "publishedAt": "2026-10-26T09:00:00Z",
        "notes": "إصلاحات"$extra
      }
  """

  @Test
  fun aWellFormedManifestIsRead() {
    val result = parseManifest(json(), UpdateChannel.TEAM)
    assertTrue(result is ManifestResult.Found)
    val manifest = (result as ManifestResult.Found).manifest
    assertEquals(57, manifest.versionCode)
    assertEquals("Sadad-team.apk", manifest.apk)
    assertEquals(digest, manifest.sha256)
    assertEquals(5_662_310, manifest.sizeBytes)
  }

  @Test
  fun aFieldThisVersionDoesNotKnowIsIgnored() {
    val result = parseManifest(json(extra = ""","futureField": true"""), UpdateChannel.TEAM)
    assertTrue(result is ManifestResult.Found)
  }

  @Test
  fun theDigestIsReadInLowerCase() {
    val result = parseManifest(json(sha256 = digest.uppercase()), UpdateChannel.TEAM)
    assertEquals(digest, (result as ManifestResult.Found).manifest.sha256)
  }

  @Test
  fun anotherChannelsManifestIsRefused() {
    assertEquals(
        ManifestResult.Invalid,
        parseManifest(
            json(channel = "stable", apk = "Sadad-3.0.1-release.apk"),
            UpdateChannel.TEAM,
        ),
    )
  }

  @Test
  fun aCodeThatCannotOrderReleasesIsRefused() {
    assertEquals(ManifestResult.Invalid, parseManifest(json(versionCode = 0), UpdateChannel.TEAM))
    assertEquals(ManifestResult.Invalid, parseManifest(json(versionCode = -1), UpdateChannel.TEAM))
  }

  @Test
  fun anApkNameThatCouldEscapeTheReleaseIsRefused() {
    assertEquals(
        ManifestResult.Invalid,
        parseManifest(json(apk = "../evil.apk"), UpdateChannel.TEAM),
    )
    assertEquals(ManifestResult.Invalid, parseManifest(json(apk = "a/b.apk"), UpdateChannel.TEAM))
    assertEquals(
        ManifestResult.Invalid,
        parseManifest(json(apk = "Sadad-team.apk.exe"), UpdateChannel.TEAM),
    )
  }

  @Test
  fun aDigestThatIsNotADigestIsRefused() {
    assertEquals(ManifestResult.Invalid, parseManifest(json(sha256 = "abc"), UpdateChannel.TEAM))
    assertEquals(
        ManifestResult.Invalid,
        parseManifest(json(sha256 = "z".repeat(64)), UpdateChannel.TEAM),
    )
  }

  @Test
  fun anImpossibleSizeIsRefused() {
    assertEquals(ManifestResult.Invalid, parseManifest(json(sizeBytes = 0), UpdateChannel.TEAM))
    assertEquals(
        ManifestResult.Invalid,
        parseManifest(json(sizeBytes = 500L * 1024 * 1024), UpdateChannel.TEAM),
    )
  }

  @Test
  fun aBodyThatIsNotJsonIsInvalidNotAnException() {
    assertEquals(ManifestResult.Invalid, parseManifest("<html>404</html>", UpdateChannel.TEAM))
    assertEquals(ManifestResult.Invalid, parseManifest("", UpdateChannel.TEAM))
  }
}
