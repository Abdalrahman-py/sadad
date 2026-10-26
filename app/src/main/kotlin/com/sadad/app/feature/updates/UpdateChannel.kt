package com.sadad.app.feature.updates

import com.sadad.app.BuildConfig

/**
 * Where a build reads updates from. GitHub Releases is the only source: an APK and its
 * `update.json` sit in the same release, so the app never calls the API and never spends the
 * unauthenticated rate limit that Seal and Mihon both run into.
 */
internal enum class UpdateChannel(
    /** The release the manifest is read from. `latest` is the newest non-prerelease. */
    val release: String,
    /** The name this channel's `update.json` must carry. */
    val channel: String,
) {
  STABLE(release = "latest", channel = "stable"),
  TEAM(release = "team-latest", channel = "team");

  val manifestUrl: String
    get() = assetUrl(MANIFEST_ASSET)

  /** `releases/<release>/download/<name>` is the same URL whether or not the asset exists yet. */
  fun assetUrl(name: String): String =
      "https://github.com/${BuildConfig.UPDATE_REPO}/releases/$release/download/$name"

  companion object {
    const val MANIFEST_ASSET = "update.json"

    /**
     * The team build and a local debug build share `com.sadad.app.debug`, so both follow the team
     * channel; only the release build follows the stable one.
     */
    fun of(buildType: String): UpdateChannel = if (buildType == "release") STABLE else TEAM

    /**
     * A local debug build is signed with the developer's own key, so no published APK can install
     * over it: it may check by hand, but it is never checked for in the background.
     */
    fun autoChecks(buildType: String): Boolean = buildType == "release" || buildType == "team"

    fun current(): UpdateChannel = of(BuildConfig.BUILD_TYPE)
  }
}
