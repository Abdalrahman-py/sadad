package com.sadad.app.feature.updates

import com.sadad.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where each channel actually lives.
 *
 * `team-latest` is a real tag, so `releases/team-latest/download/<name>` serves its bytes. `latest`
 * is not a tag: GitHub reads it as the newest non-prerelease release, and only at
 * `releases/latest/download/<name>`. Building the tag shape with `latest` in it answered `404` for
 * a release that had just been published — the check that would have shipped a product nobody could
 * update.
 */
class UpdateChannelTest {
  private val repo = BuildConfig.UPDATE_REPO

  @Test
  fun theTeamChannelDownloadsByItsTag() {
    assertEquals(
        "https://github.com/$repo/releases/team-latest/download/update.json",
        UpdateChannel.TEAM.manifestUrl,
    )
    assertEquals(
        "https://github.com/$repo/releases/team-latest/download/Sadad-team.apk",
        UpdateChannel.TEAM.assetUrl("Sadad-team.apk"),
    )
    assertEquals("releases/tags/team-latest", UpdateChannel.TEAM.releaseApiPath())
  }

  @Test
  fun theStableChannelDownloadsThroughTheLatestAlias() {
    assertEquals(
        "https://github.com/$repo/releases/latest/download/update.json",
        UpdateChannel.STABLE.manifestUrl,
    )
    assertEquals(
        "https://github.com/$repo/releases/latest/download/Sadad-1.0.0-release.apk",
        UpdateChannel.STABLE.assetUrl("Sadad-1.0.0-release.apk"),
    )
  }

  /** `releases/tags/latest` does not exist, so the API road must use the alias path too. */
  @Test
  fun theStableChannelAsksTheApiForTheLatestRelease() {
    assertEquals("releases/latest", UpdateChannel.STABLE.releaseApiPath())
  }

  @Test
  fun aReleaseBuildFollowsTheStableChannelAndTheTeamBuildTheTeamOne() {
    assertEquals(UpdateChannel.STABLE, UpdateChannel.of("release"))
    assertEquals(UpdateChannel.TEAM, UpdateChannel.of("team"))
    assertEquals(UpdateChannel.TEAM, UpdateChannel.of("debug"))
  }
}
