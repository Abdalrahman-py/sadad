package com.sadad.app.feature.updates

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The transfer itself: which road it takes, what a retry costs, and what it refuses to keep. Every
 * test is about bytes and requests, never about the clock ([NO_WAIT]).
 */
class ApkDownloaderTest {
  @get:Rule val folder = TemporaryFolder()

  private val server = MockWebServer()
  private val client = OkHttpClient()
  private val apkBytes = "a small apk for the test".toByteArray()
  private val digest = ApkVerifier.hexOf(MessageDigest.getInstance("SHA-256").digest(apkBytes))

  private val manifest =
      UpdateManifest(
          channel = "team",
          versionCode = 57,
          versionName = "dev-57-team",
          apk = "Sadad-team.apk",
          sha256 = digest,
          sizeBytes = apkBytes.size.toLong(),
          notes = "fixes",
      )

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  private fun downloader(): ApkDownloader {
    val root = File(folder.root, "updates")
    root.mkdirs()
    return ApkDownloader(client, { root }, NO_WAIT)
  }

  private fun apk(code: Int = 200, bytes: ByteArray = apkBytes) =
      MockResponse.Builder().code(code).body(Buffer().write(bytes)).build()

  private fun partOfHalf() =
      MockResponse.Builder().code(200).body(Buffer().write(apkBytes, 0, apkBytes.size / 2)).build()

  private fun paths(count: Int = server.requestCount): List<String> =
      (1..count).map { server.takeRequest().url.encodedPath }

  @Test
  fun aDownloadThatWorksCostsOneRequestAndNoApiCall() = runTest {
    server.enqueue(apk())
    var asked = false
    val error =
        downloader()
            .download(
                manifest = manifest,
                url = server.url("/Sadad-team.apk").toString(),
                onProgress = {},
                fallbackUrl = {
                  asked = true
                  null
                },
            )
    assertNull(error)
    assertFalse("the API must not be spent on a download that works", asked)
    assertEquals(1, server.requestCount)
  }

  /**
   * The 404 windows hit every asset of a release at once, so the second road is the only way a
   * download started in one can finish. The asset endpoint is asked after two refusals, not on the
   * first: a single refusal is often a dropped connection.
   */
  @Test
  fun aRefusedReleaseUrlIsLeftForTheAssetById() = runTest {
    server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
    server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
    server.enqueue(apk())
    val error =
        downloader()
            .download(
                manifest = manifest,
                url = server.url("/Sadad-team.apk").toString(),
                onProgress = {},
                fallbackUrl = { server.url("/api/releases/assets/8").toString() },
            )
    assertNull(error)
    assertArrayEquals(apkBytes, downloader().file(57).readBytes())
    assertEquals(
        listOf("/Sadad-team.apk", "/Sadad-team.apk", "/api/releases/assets/8"),
        paths(),
    )
  }

  /**
   * A body that stops half way is a dropped connection, not a refusal: the rest is asked for with
   * `Range`, so the retry costs the remaining bytes instead of the whole twenty megabytes.
   */
  @Test
  fun aStoppedTransferIsResumedRatherThanFetchedAgain() = runTest {
    val half = apkBytes.size / 2
    server.enqueue(partOfHalf())
    server.enqueue(
        MockResponse.Builder()
            .code(206)
            .body(Buffer().write(apkBytes, half, apkBytes.size - half))
            .build()
    )
    val error =
        downloader().download(manifest, server.url("/Sadad-team.apk").toString(), {}, { null })
    assertNull(error)
    assertArrayEquals(apkBytes, downloader().file(57).readBytes())
    assertNull(server.takeRequest().headers["Range"])
    assertEquals("bytes=$half-", server.takeRequest().headers["Range"])
  }

  /**
   * A server that ignores `Range` sends the whole file again; the file is started over, not
   * doubled.
   */
  @Test
  fun aServerThatIgnoresRangeSendsTheWholeFileAndItIsKept() = runTest {
    server.enqueue(partOfHalf())
    server.enqueue(apk())
    val error =
        downloader().download(manifest, server.url("/Sadad-team.apk").toString(), {}, { null })
    assertNull(error)
    assertArrayEquals(apkBytes, downloader().file(57).readBytes())
    server.takeRequest()
    assertEquals("bytes=${apkBytes.size / 2}-", server.takeRequest().headers["Range"])
  }

  /** Bytes that are not the published ones will not become so by asking again. */
  @Test
  fun aBodyThatIsNotThePublishedApkIsNotRetried() = runTest {
    val sameSize = apkBytes.copyOf().also { it[0] = 'X'.code.toByte() }
    server.enqueue(apk(bytes = sameSize))
    val error =
        downloader().download(manifest, server.url("/Sadad-team.apk").toString(), {}, { null })
    assertEquals(UpdateError.CHECKSUM_MISMATCH, error)
    assertEquals(1, server.requestCount)
    assertFalse(downloader().file(57).exists())
  }

  @Test
  fun aRefusedDownloadWithNoSecondRoadFailsAfterTheLastAttempt() = runTest {
    repeat(4) { server.enqueue(MockResponse.Builder().code(404).body("Not Found").build()) }
    val error =
        downloader().download(manifest, server.url("/Sadad-team.apk").toString(), {}, { null })
    assertEquals(UpdateError.DOWNLOAD_FAILED, error)
    assertEquals(4, server.requestCount)
  }

  /** The bytes are already on disk and they are the published ones: no request, just the name. */
  @Test
  fun aCompletePartialFileIsProvedWithoutARefetch() = runTest {
    val root = File(folder.root, "updates").apply { mkdirs() }
    File(root, "sadad-57.apk.part").writeBytes(apkBytes)
    val error =
        downloader().download(manifest, server.url("/Sadad-team.apk").toString(), {}, { null })
    assertNull(error)
    assertEquals(0, server.requestCount)
    assertArrayEquals(apkBytes, downloader().file(57).readBytes())
  }

  /** A partial file longer than the release cannot be resumed from: it starts over, no `Range`. */
  @Test
  fun aPartialFileLongerThanTheReleaseIsDiscarded() = runTest {
    val root = File(folder.root, "updates").apply { mkdirs() }
    File(root, "sadad-57.apk.part").writeBytes(ByteArray(apkBytes.size + 10))
    server.enqueue(apk())
    val error =
        downloader().download(manifest, server.url("/Sadad-team.apk").toString(), {}, { null })
    assertNull(error)
    assertArrayEquals(apkBytes, downloader().file(57).readBytes())
    assertNull(server.takeRequest().headers["Range"])
  }

  /**
   * The partial file is the bytes the next attempt resumes from, so the cleanup must not take it.
   */
  @Test
  fun theCleanupKeepsThePartialDownloadOfTheReleaseItKeeps() = runTest {
    val root = File(folder.root, "updates").apply { mkdirs() }
    val part = File(root, "sadad-57.apk.part").apply { writeText("half an apk") }
    val older = File(root, "sadad-56.apk").apply { writeText("an older apk") }
    downloader().keepOnly(57)
    assertTrue(part.isFile)
    assertFalse(older.exists())
  }
}
