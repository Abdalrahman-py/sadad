package com.sadad.app.feature.updates

import com.sadad.app.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Streams the APK into the cache and proves it before the file is given a final name: the release's
 * digest and byte count, never just "the read ended". A kill mid-download leaves a `.part` file
 * that the next attempt deletes, so a half-written APK can never be installed.
 *
 * A refused or broken response is retried: the same release URL that serves the APK answers `404`
 * in windows of minutes (see [UpdateSource]), and a download is twenty megabytes of the user's data
 * to make a second time.
 */
internal class ApkDownloader(
    private val client: OkHttpClient,
    private val root: () -> File,
    /** The wait before each retry; a test passes a no-op so nothing sleeps. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
  /** `cacheDir` is read here, on the download thread, never while a screen builds itself. */
  private val directory: File
    get() = root()

  fun file(versionCode: Int): File = File(directory, "sadad-$versionCode.apk")

  suspend fun download(
      manifest: UpdateManifest,
      url: String,
      onProgress: (Int) -> Unit,
  ): UpdateError? =
      withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) return@withContext UpdateError.NO_SPACE
        val target = file(manifest.versionCode)
        if (isVerified(target, manifest)) return@withContext null
        if (directory.usableSpace < manifest.sizeBytes) return@withContext UpdateError.NO_SPACE
        val part = File(directory, "${target.name}.part")
        var error: UpdateError? = UpdateError.DOWNLOAD_FAILED
        for (attempt in 0 until ATTEMPTS) {
          if (attempt > 0) pause(WAITS[attempt - 1])
          part.delete()
          val attemptError = stream(part, url, manifest, onProgress)
          error = attemptError
          if (attemptError == null) break
          // A digest that does not match is not a transport problem: asking again would return the
          // same bytes, and the caller must hear that the release and its digest disagree.
          if (attemptError != UpdateError.DOWNLOAD_FAILED) {
            part.delete()
            return@withContext attemptError
          }
        }
        if (error != null) {
          part.delete()
          return@withContext error
        }
        if (!part.renameTo(target)) {
          part.delete()
          return@withContext UpdateError.DOWNLOAD_FAILED
        }
        null
      }

  private fun isVerified(file: File, manifest: UpdateManifest): Boolean =
      file.isFile &&
          file.length() == manifest.sizeBytes &&
          ApkVerifier.sha256(file) == manifest.sha256

  /** Drops every cached APK but [versionCode]'s (0 drops all of them, after an install). */
  fun keepOnly(versionCode: Int) {
    val keep = file(versionCode).name
    directory.listFiles()?.forEach { cached -> if (cached.name != keep) cached.delete() }
  }

  /** Writes every byte it reads; the digest and the length decide afterwards, not the read loop. */
  private fun stream(
      part: File,
      url: String,
      manifest: UpdateManifest,
      onProgress: (Int) -> Unit,
  ): UpdateError? {
    val request =
        Request.Builder()
            .url(url)
            .header("User-Agent", "Sadad/${BuildConfig.VERSION_NAME}")
            // Serves the bytes when [url] is an API asset (see UpdateSource); the release URL
            // ignores it and redirects to the CDN as before.
            .header("Accept", "application/octet-stream")
            .build()
    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return UpdateError.DOWNLOAD_FAILED
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        var lastPercent = -1
        part.outputStream().use { output ->
          response.body.byteStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              if (read + count > manifest.sizeBytes) return UpdateError.DOWNLOAD_FAILED
              output.write(buffer, 0, count)
              digest.update(buffer, 0, count)
              read += count
              val percent = ((read * 100) / manifest.sizeBytes).toInt()
              if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
              }
            }
          }
        }
        if (read != manifest.sizeBytes) return UpdateError.DOWNLOAD_FAILED
        if (ApkVerifier.hexOf(digest.digest()) != manifest.sha256) {
          return UpdateError.CHECKSUM_MISMATCH
        }
        return null
      }
    } catch (_: IOException) {
      return UpdateError.DOWNLOAD_FAILED
    }
  }

  private companion object {
    const val ATTEMPTS = 3

    /** Shorter than the manifest's waits: by now the user is watching a progress bar. */
    val WAITS = longArrayOf(1_500, 4_000)
  }
}
