package com.sadad.app.feature.updates

import com.sadad.app.BuildConfig

/**
 * Where a build reads updates from. GitHub Releases is the only source: an APK and its
 * `update.json` sit in the same release.
 *
 * The download URL is the road that needs no API and no rate limit, and it is tried first. When it
 * answers `404` for a release that is there — which it does from some networks, for minutes at a
 * time — `UpdateSource` falls back to the API, whose unauthenticated limit is sixty an hour and is
 * only spent on that failure.
 */
internal enum class UpdateChannel(
    /** The release the manifest is read from. `latest` is the newest non-prerelease. */
    val release: String,
    /** The name this channel's `update.json` must carry. */
    val channel: String,
    /**
     * `latest` is an alias, not a tag. Its bytes are at `releases/latest/download/<name>`; the tag
     * shape with `latest` in it (`releases/latest/download` reversed) answers `404` even for a
     * release that is published, which is how the first product release nearly shipped unable to
     * update itself. `team-latest` is a real tag, so the team channel keeps the tag shape.
     */
    private val latestAlias: Boolean = false,
) {
  STABLE(release = "latest", channel = "stable", latestAlias = true),
  TEAM(release = "team-latest", channel = "team");

  val manifestUrl: String
    get() = assetUrl(MANIFEST_ASSET)

  /** The download URL for an asset of this channel's release, with or without the alias. */
  fun assetUrl(name: String): String =
      if (latestAlias) {
        "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/$name"
      } else {
        "https://github.com/${BuildConfig.UPDATE_REPO}/releases/$release/download/$name"
      }

  /** The API path that lists this channel's release: the alias, or a real tag. */
  fun releaseApiPath(): String = if (latestAlias) "releases/latest" else "releases/tags/$release"

  /** An API path under the repository: `releases/tags/<tag>` or `releases/assets/<id>`. */
  fun apiUrl(path: String): String = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/$path"

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
