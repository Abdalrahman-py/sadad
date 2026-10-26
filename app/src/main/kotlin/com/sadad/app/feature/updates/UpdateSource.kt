package com.sadad.app.feature.updates

import com.sadad.app.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads `update.json` from the channel's release. GitHub's CDN answers with a redirect, which
 * OkHttp follows; the file is small and is never cached by the app.
 *
 * [urlFor] resolves an asset name inside the channel's release. It is the seam a test uses to point
 * at a local server instead of the real repository.
 */
internal class UpdateSource(
    private val client: OkHttpClient,
    private val channel: UpdateChannel,
    private val urlFor: (String) -> String = channel::assetUrl,
) {
  suspend fun latest(): ManifestResult =
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

  fun apkUrl(manifest: UpdateManifest): String = urlFor(manifest.apk)

  private companion object {
    val USER_AGENT = "Sadad/${BuildConfig.VERSION_NAME}"
  }
}
