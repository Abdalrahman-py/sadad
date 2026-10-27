package com.sadad.app.feature.updates

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A retry that does not wait: these tests are about the decision, never about the clock. */
internal val NO_WAIT: suspend (Long) -> Unit = { _ -> }

/** The channel's `update.json` is the only thing read, and every answer it can give is mapped. */
class UpdateSourceTest {
  private val server = MockWebServer()
  private val client = OkHttpClient()

  private val digest = "b".repeat(64)

  private fun source() =
      UpdateSource(
          client = client,
          channel = UpdateChannel.TEAM,
          urlFor = { name -> server.url("/$name").toString() },
          apiUrlFor = { path -> server.url("/api/$path").toString() },
          pause = NO_WAIT,
      )

  /** The tag as the API reports it: the assets that make the release readable through the API. */
  private fun listing(vararg assets: Pair<String, Long>) =
      assets.joinToString(prefix = "{\"assets\":[", postfix = "]}") { (name, id) ->
        """{"id":$id,"name":"$name"}"""
      }

  private fun body(versionCode: Int = 57) =
      """
      {"channel":"team","versionCode":$versionCode,"versionName":"dev-17-a1b2c3d-team",
       "apk":"Sadad-team.apk","sha256":"$digest","sizeBytes":5662310,"notes":"fixes"}
  """

  @Before fun start() = server.start()

  @After fun stop() = server.close()

  @Test
  fun theManifestIsReadFromTheRelease() = runTest {
    server.enqueue(MockResponse.Builder().code(200).body(body()).build())
    val result = source().latest()
    assertTrue(result is ManifestResult.Found)
    assertEquals(57, (result as ManifestResult.Found).manifest.versionCode)
    assertEquals("/update.json", server.takeRequest().url.encodedPath)
  }

  @Test
  fun anOlderReleaseWithoutTheManifestIsMissingNotAFailureToTrust() = runTest {
    repeat(4) { server.enqueue(MockResponse.Builder().code(404).body("Not Found").build()) }
    server.enqueue(MockResponse.Builder().code(200).body(listing("Sadad-team.apk" to 8)).build())
    assertEquals(ManifestResult.Missing, source().latest())
    val paths = (1..5).map { server.takeRequest().url.encodedPath }
    // Four refusals from the download URL, then the API, which is where the "missing" is proved.
    assertEquals("/api/releases/tags/team-latest", paths.last())
  }

  /**
   * The case measured on 26 October 2026: `releases/download/<tag>/update.json` answered 404 for
   * four attempts while `api.github.com` answered at once, anonymously. The update must be found.
   */
  @Test
  fun aReleaseTheApiCanReadIsStillFoundWhenTheDownloadUrlRefuses() = runTest {
    repeat(4) { server.enqueue(MockResponse.Builder().code(404).body("Not Found").build()) }
    server.enqueue(
        MockResponse.Builder()
            .code(200)
            .body(listing("update.json" to 7, "Sadad-team.apk" to 8))
            .build()
    )
    server.enqueue(MockResponse.Builder().code(200).body(body(versionCode = 58)).build())
    val source = source()
    val result = source.latest()
    assertTrue(result is ManifestResult.Found)
    val manifest = (result as ManifestResult.Found).manifest
    assertEquals(58, manifest.versionCode)
    // The APK is fetched by asset id, so the download does not walk back into the broken URL.
    assertEquals(server.url("/api/releases/assets/8").toString(), source.apkUrl(manifest))
  }

  /**
   * When the API cannot be read either, the download URL's refusal is the answer, not an invention.
   */
  @Test
  fun anApiThatCannotBeReadLeavesTheDownloadUrlsAnswer() = runTest {
    repeat(4) { server.enqueue(MockResponse.Builder().code(404).body("Not Found").build()) }
    server.enqueue(MockResponse.Builder().code(503).body("").build())
    assertEquals(ManifestResult.Missing, source().latest())
  }

  /**
   * The case seen in the wild: the release is there and GitHub's download URL answers 404 for it,
   * then serves it. A single attempt would have told the user to install by hand.
   */
  @Test
  fun a404IsAskedAgainAndTheReleaseThatAnswersIsTheOneUsed() = runTest {
    server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
    server.enqueue(MockResponse.Builder().code(200).body(body(versionCode = 58)).build())
    val result = source().latest()
    assertTrue(result is ManifestResult.Found)
    assertEquals(58, (result as ManifestResult.Found).manifest.versionCode)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun aServerErrorIsUnreachable() = runTest {
    repeat(4) { server.enqueue(MockResponse.Builder().code(503).body("").build()) }
    server.enqueue(MockResponse.Builder().code(503).body("").build())
    assertEquals(ManifestResult.Unreachable(503), source().latest())
    assertEquals(5, server.requestCount)
  }

  /** A refusal that cannot change by asking again is not retried. */
  @Test
  fun aManifestThatCannotBeTrustedIsNotRetried() = runTest {
    server.enqueue(MockResponse.Builder().code(200).body("{\"hello\":true}").build())
    assertEquals(ManifestResult.Invalid, source().latest())
    assertEquals(1, server.requestCount)
  }

  @Test
  fun aBodyThatIsNotAManifestIsInvalid() = runTest {
    server.enqueue(MockResponse.Builder().code(200).body("{\"hello\":true}").build())
    assertEquals(ManifestResult.Invalid, source().latest())
  }

  @Test
  fun aDeadServerIsUnreachableRatherThanAnException() = runTest {
    server.close()
    assertEquals(ManifestResult.Unreachable(0), source().latest())
  }
}
