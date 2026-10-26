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
          pause = NO_WAIT,
      )

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
    assertEquals(ManifestResult.Missing, source().latest())
    assertEquals(4, server.requestCount)
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
    assertEquals(ManifestResult.Unreachable(503), source().latest())
    assertEquals(4, server.requestCount)
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
