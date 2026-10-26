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

/** The channel's `update.json` is the only thing read, and every answer it can give is mapped. */
class UpdateSourceTest {
  private val server = MockWebServer()
  private val client = OkHttpClient()

  private val digest = "b".repeat(64)

  private fun source() =
      UpdateSource(client, UpdateChannel.TEAM) { name -> server.url("/$name").toString() }

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
    server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
    assertEquals(ManifestResult.Missing, source().latest())
  }

  @Test
  fun aServerErrorIsUnreachable() = runTest {
    server.enqueue(MockResponse.Builder().code(503).body("").build())
    assertEquals(ManifestResult.Unreachable(503), source().latest())
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
