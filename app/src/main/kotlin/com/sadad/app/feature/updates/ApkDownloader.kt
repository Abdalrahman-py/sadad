package com.sadad.app.feature.updates

import com.sadad.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Streams the APK into the cache and proves it before the file is given a final name: the release's
 * digest and byte count, never just "the read ended". A kill mid-download leaves a `.part` file
 * that the next attempt resumes with `Range`, so a dropped connection costs the rest of the file,
 * not another twenty megabytes.
 *
 * Two things are retried, in this order: the same URL, and then the other road. The release URL
 * that serves the APK answers `404` in windows of minutes (see [UpdateSource]), and a retry loop
 * lasting seconds cannot outlast one — so after [SWITCH_ATTEMPT] refusals the APK is fetched by
 * asset id instead. That road is spent only then, never on a download that is working.
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

  /**
   * [fallbackUrl] is the second road, asked once, after the first has refused twice. It may cost an
   * API request, which is why a download that is working never causes it to be asked.
   */
  suspend fun download(
      manifest: UpdateManifest,
      url: String,
      onProgress: (Int) -> Unit,
      fallbackUrl: suspend () -> String? = { null },
  ): UpdateError? =
      withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) return@withContext UpdateError.NO_SPACE
        val target = file(manifest.versionCode)
        if (isVerified(target, manifest)) return@withContext null
        val part = File(directory, "${target.name}.part")
        // A complete download that was never renamed is proved, not fetched again. Anything else
        // that claims to be as long as the release cannot be resumed from, so it starts over.
        if (part.isFile && part.length() >= manifest.sizeBytes) {
          val proven =
              part.length() == manifest.sizeBytes && ApkVerifier.sha256(part) == manifest.sha256
          if (proven) return@withContext finish(part, target)
          part.delete()
        }
        var road = url
        var error: UpdateError? = UpdateError.DOWNLOAD_FAILED
        for (attempt in 0 until ATTEMPTS) {
          if (attempt > 0) pause(WAITS[attempt - 1])
          if (attempt == SWITCH_ATTEMPT) road = fallbackUrl() ?: road
          val missing = (manifest.sizeBytes - part.length()).coerceAtLeast(0)
          if (directory.usableSpace < missing) {
            part.delete()
            return@withContext UpdateError.NO_SPACE
          }
          error = stream(part, road, manifest, onProgress)
          if (error == null) break
          // A digest that does not match is not a transport problem: asking again returns the same
          // bytes from either road, and the caller must hear that the release and its digest
          // disagree. Every other refusal keeps what it read, for the next attempt to resume.
          if (error != UpdateError.DOWNLOAD_FAILED) {
            part.delete()
            return@withContext error
          }
        }
        if (error != null) return@withContext error
        finish(part, target)
      }

  private fun finish(part: File, target: File): UpdateError? {
    if (!part.renameTo(target)) {
      part.delete()
      return UpdateError.DOWNLOAD_FAILED
    }
    return null
  }

  private fun isVerified(file: File, manifest: UpdateManifest): Boolean =
      file.isFile &&
          file.length() == manifest.sizeBytes &&
          ApkVerifier.sha256(file) == manifest.sha256

  /**
   * Drops every cached APK but [versionCode]'s (0 drops all of them, after an install). A partial
   * download of that release is kept with it: it is the bytes the next attempt resumes from.
   *
   * Suspending and on [Dispatchers.IO] because it lists and deletes files: a check runs on the
   * caller's thread, and reading `cacheDir` there killed the app under StrictMode the first time a
   * check actually found a release (26 October 2026, on a device).
   */
  suspend fun keepOnly(versionCode: Int) =
      withContext(Dispatchers.IO) {
        val apk = file(versionCode).name
        val keep = if (versionCode == 0) emptySet() else setOf(apk, "$apk.part")
        directory.listFiles()?.forEach { cached -> if (cached.name !in keep) cached.delete() }
      }

  /**
   * Writes every byte it reads, starting where the last attempt stopped; the digest and the length
   * decide afterwards, not the read loop. A server that ignores `Range` and answers `200` sends the
   * whole file again, which this handles by starting the file over.
   */
  private suspend fun stream(
      part: File,
      url: String,
      manifest: UpdateManifest,
      onProgress: (Int) -> Unit,
  ): UpdateError? {
    val have = part.length().coerceAtMost(manifest.sizeBytes)
    val request =
        Request.Builder()
            .url(url)
            .header("User-Agent", "Sadad/${BuildConfig.VERSION_NAME}")
            // Serves the bytes when [url] is an API asset (see UpdateSource); the release URL
            // ignores it and redirects to the CDN as before.
            .header("Accept", "application/octet-stream")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
    try {
      return updateCall(client, request) { response ->
        if (!response.isSuccessful) return@updateCall UpdateError.DOWNLOAD_FAILED
        val resumed = have > 0 && response.code == 206
        var read = if (resumed) have else 0L
        var lastPercent = -1
        FileOutputStream(part, resumed).use { output ->
          response.body.byteStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              if (read + count > manifest.sizeBytes) return@updateCall UpdateError.DOWNLOAD_FAILED
              output.write(buffer, 0, count)
              read += count
              val percent = ((read * 100) / manifest.sizeBytes).toInt()
              if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
              }
            }
          }
        }
        if (read != manifest.sizeBytes) return@updateCall UpdateError.DOWNLOAD_FAILED
        if (ApkVerifier.sha256(part) != manifest.sha256) {
          return@updateCall UpdateError.CHECKSUM_MISMATCH
        }
        return@updateCall null
      }
    } catch (_: IOException) {
      return UpdateError.DOWNLOAD_FAILED
    }
  }

  private companion object {
    /** Two attempts on the release URL, two on the asset it names. */
    const val ATTEMPTS = 4

    const val SWITCH_ATTEMPT = 2

    /** Shorter than the check's waits: by now the user is watching a progress bar. */
    val WAITS = longArrayOf(1_500, 1_500, 4_000)
  }
}
