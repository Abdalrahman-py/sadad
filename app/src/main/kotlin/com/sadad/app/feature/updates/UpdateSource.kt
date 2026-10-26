package com.sadad.app.feature.updates

import com.sadad.app.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads `update.json` from the channel's release, by two roads.
 *
 * The first is the download URL, `releases/download/<tag>/<file>`, which is what the app has always
 * used: it needs no API, no token and no rate limit. It answers `404` for a release that is there —
 * measured on 26 October 2026 from this network, for minutes at a time, on every asset of the
 * release, while the release page, the API and the asset-by-id endpoint stayed up. So a `404` here
 * is not yet an answer: it is retried, and then the API is asked.
 *
 * The second is the API, which costs one unauthenticated request out of sixty an hour and is only
 * spent when the first road fails: `releases/tags/<tag>` lists the assets with their ids, and
 * `releases/assets/<id>` serves an asset's bytes. It also names the APK's URL, so a download that
 * starts after a fallback does not walk back into the same broken path.
 *
 * [urlFor] and [apiUrlFor] resolve an asset name and an API path. They are the seams a test uses to
 * point at a local server instead of the real repository.
 */
internal class UpdateSource(
    private val client: OkHttpClient,
    private val channel: UpdateChannel,
    private val urlFor: (String) -> String = channel::assetUrl,
    private val apiUrlFor: (String) -> String = channel::apiUrl,
    /** The wait before each retry; a test passes a no-op so nothing sleeps. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
  /** Set when the manifest came through the API, so the APK is fetched the same way. */
  private var apkAssetId: Long? = null

  suspend fun latest(): ManifestResult {
    apkAssetId = null
    var result: ManifestResult = ManifestResult.Unreachable(0)
    for (attempt in 0 until ATTEMPTS) {
      if (attempt > 0) pause(WAITS[attempt - 1])
      result = once()
      if (!result.isTransient()) return result
    }
    return viaApi() ?: result
  }

  fun apkUrl(manifest: UpdateManifest): String =
      apkAssetId?.let { apiUrlFor("releases/assets/$it") } ?: urlFor(manifest.apk)

  private suspend fun once(): ManifestResult =
      read(urlFor(UpdateChannel.MANIFEST_ASSET), accept = null)

  /**
   * The release as the API reports it. Returns null when the API cannot be read either, so the
   * caller keeps the answer the download URL gave instead of inventing a third one.
   */
  private suspend fun viaApi(): ManifestResult? {
    val listing = text(apiUrlFor("releases/tags/${channel.release}"), JSON_ACCEPT) ?: return null
    val assets = assetsOf(listing)
    val manifestId = assets[UpdateChannel.MANIFEST_ASSET] ?: return ManifestResult.Missing
    val body = text(apiUrlFor("releases/assets/$manifestId"), BYTES_ACCEPT) ?: return null
    val parsed = parseManifest(body, channel)
    if (parsed is ManifestResult.Found) apkAssetId = assets[parsed.manifest.apk]
    return parsed
  }

  private suspend fun text(url: String, accept: String): String? =
      withContext(Dispatchers.IO) {
        try {
          client.newCall(request(url, accept)).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
          }
        } catch (_: IOException) {
          null
        }
      }

  private suspend fun read(url: String, accept: String?): ManifestResult =
      withContext(Dispatchers.IO) {
        try {
          client.newCall(request(url, accept)).execute().use { response ->
            when {
              response.code == 404 -> ManifestResult.Missing
              !response.isSuccessful -> ManifestResult.Unreachable(response.code)
              else -> parseManifest(response.body.string(), channel)
            }
          }
        } catch (_: IOException) {
          ManifestResult.Unreachable(0)
        }
      }

  private fun request(url: String, accept: String?): Request =
      Request.Builder()
          .url(url)
          .header("User-Agent", USER_AGENT)
          .header("Cache-Control", "no-cache")
          .apply { if (accept != null) header("Accept", accept) }
          .build()

  /** `{"assets":[{"id":1,"name":"update.json"}, …]}` → the ids by name; empty when unreadable. */
  private fun assetsOf(listing: String): Map<String, Long> =
      runCatching {
        val array = ApiJson.parseToJsonElement(listing).jsonObject["assets"] as? JsonArray
        array
            ?.mapNotNull { element ->
              val asset = element.jsonObject
              val name = asset["name"]?.jsonPrimitive?.content
              val id = asset["id"]?.jsonPrimitive?.longOrNull
              if (name != null && id != null) name to id else null
            }
            ?.toMap()
      }
          .getOrNull() ?: emptyMap()

  private companion object {
    val USER_AGENT = "Sadad/${BuildConfig.VERSION_NAME}"

    val ApiJson = Json { ignoreUnknownKeys = true }

    const val JSON_ACCEPT = "application/vnd.github+json"
    const val BYTES_ACCEPT = "application/octet-stream"

    const val ATTEMPTS = 4

    /** Roughly thirteen seconds in all: the window seen in the wild, without stalling a check. */
    val WAITS = longArrayOf(1_500, 4_000, 8_000)
  }
}

/**
 * A 404 or a server error is worth asking again; a manifest that arrived but cannot be trusted will
 * not become trustworthy, and the answer is the same every time.
 */
private fun ManifestResult.isTransient(): Boolean =
    this is ManifestResult.Missing ||
        (this is ManifestResult.Unreachable && (status == 0 || status >= 500))
