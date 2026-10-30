package com.sadad.app.feature.updates

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cancellation must close a stalled socket, not wait for its read timeout. */
class UpdateCancellationTest {
  @get:Rule val folder = TemporaryFolder()

  @Test
  fun cancellingADownloadClosesTheConnectionBeforeItsReadTimeout() = runBlocking {
    val server = MockWebServer()
    val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    val bytes = "an apk".toByteArray()
    val manifest = UpdateManifest(
        "team", 57, "dev-57-team", "Sadad-team.apk",
        ApkVerifier.hexOf(MessageDigest.getInstance("SHA-256").digest(bytes)),
        bytes.size.toLong(), "fixes",
    )
    server.start()
    server.enqueue(MockResponse.Builder().body("an apk").bodyDelay(10, TimeUnit.SECONDS).build())
    repeat(4) { server.enqueue(MockResponse.Builder().body("an apk").build()) }
    val downloader = ApkDownloader(client, { File(folder.root, "updates") }, {})
    val job = launch(Dispatchers.IO) {
      downloader.download(manifest, server.url("/apk").toString(), {})
    }
    try {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
      job.cancel()
      val stopped = withTimeoutOrNull(1_000) { job.join(); true } ?: false
      assertTrue("Cancellation must stop the HTTP read within one second", stopped)
    } finally {
      client.dispatcher.cancelAll()
      job.cancelAndJoin()
      server.close()
    }
  }

  @Test
  fun leavingAnUpdateCheckClosesItsStalledConnection() = runBlocking {
    val server = MockWebServer()
    val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    server.start()
    server.enqueue(MockResponse.Builder().body("{}").bodyDelay(10, TimeUnit.SECONDS).build())
    repeat(6) { server.enqueue(MockResponse.Builder().body("{}").build()) }
    val source = UpdateSource(client, UpdateChannel.TEAM,
        { server.url("/$it").toString() }, { server.url("/$it").toString() }, {})
    val job = launch(Dispatchers.IO) { source.latest() }
    try {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
      job.cancel()
      val stopped = withTimeoutOrNull(1_000) { job.join(); true } ?: false
      assertTrue("Leaving the check must close its HTTP read within one second", stopped)
    } finally {
      client.dispatcher.cancelAll()
      job.cancelAndJoin()
      server.close()
    }
  }
}
