package com.sadad.app.feature.updates

import com.sadad.app.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads `update.json` from the channel's release. GitHub's CDN answers with a redirect, which
 * OkHttp follows; the file is small and is never cached by the app.
 *
 * A miss is retried before it is believed: `releases/download/<tag>/<file>` answers `404` for a
 * release that is there — measured on 26 October 2026 for minutes at a time, on every asset of the
 * release, while its page, API and asset-by-id endpoint stayed up. One attempt turns that into
 * «this release publishes no update file yet», a sentence the user cannot act on.
 *
 * [urlFor] resolves an asset name inside the channel's release. It is the seam a test uses to point
 * at a local server instead of the real repository.
 */
internal class UpdateSource(
    private val client: OkHttpClient,
    private val channel: UpdateChannel,
    private val urlFor: (String) -> String = channel::assetUrl,
    /** The wait before each retry; a test passes a no-op so nothing sleeps. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
  suspend fun latest(): ManifestResult {
    var result: ManifestResult = ManifestResult.Unreachable(0)
    for (attempt in 0 until ATTEMPTS) {
      if (attempt > 0) pause(WAITS[attempt - 1])
      result = once()
      if (!result.isTransient()) return result
    }
    return result
  }

  fun apkUrl(manifest: UpdateManifest): String = urlFor(manifest.apk)

  private suspend fun once(): ManifestResult =
      withContext(Dispatchers.IO) {
        val request =
            Request.Builder()
                .url(urlFor(UpdateChannel.MANIFEST_ASSET))
                .header("User-Agent", USER_AGENT)
                .header("Cache-Control", "no-cache")
                .build()
        try {
          client.newCall(request).execute().use { response ->
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

  private companion object {
    val USER_AGENT = "Sadad/${BuildConfig.VERSION_NAME}"

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
