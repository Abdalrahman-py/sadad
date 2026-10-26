package com.sadad.app.feature.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The whole decision path, against a local release server: what is offered, what is refused, and
 * what a failed check does to a release already known. Real time, because the download and the
 * DataStore both run off the test's dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCenterTest {
  @get:Rule val folder = TemporaryFolder()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val server = MockWebServer()
  private val client = OkHttpClient()
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private val apkBytes = "a small apk for the test".toByteArray()
  private val apkDigest = ApkVerifier.hexOf(MessageDigest.getInstance("SHA-256").digest(apkBytes))

  private lateinit var preferences: UpdatePreferences

  @Before
  fun setUp() {
    server.start()
    // One store for the test: DataStore refuses a second instance on the same file.
    preferences =
        UpdatePreferences.create(
            scope,
            { File(folder.root, "updates.preferences_pb") },
            UpdateChannel.TEAM,
        )
  }

  @After
  fun tearDown() {
    server.close()
    scope.cancel()
  }

  private fun manifestJson(versionCode: Int, sha256: String = apkDigest) =
      """
      {"channel":"team","versionCode":$versionCode,"versionName":"dev-$versionCode-team",
       "apk":"Sadad-team.apk","sha256":"$sha256","sizeBytes":${apkBytes.size},"notes":"fixes"}
  """

  private fun center(installed: Int = 40, nowMs: Long = 10_000): UpdateCenter {
    val source =
        UpdateSource(client, UpdateChannel.TEAM) { name -> server.url("/$name").toString() }
    return UpdateCenter(
        context = context,
        preferences = preferences,
        source = source,
        downloader = ApkDownloader(client) { File(folder.root, "updates") },
        installedVersionCode = { installed },
        clock = { nowMs },
        scope = scope,
    )
  }

  private suspend fun UpdateCenter.awaitPhase(phase: UpdateState.Phase): UpdateState =
      withTimeout(10_000) { state.first { it.phase == phase } }

  @Test
  fun aHigherCodeIsOfferedAndRemembered() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body(manifestJson(57)).build())
    val center = center(installed = 40)
    center.check(force = true)
    val state = center.state.value
    assertEquals(UpdateState.Phase.AVAILABLE, state.phase)
    assertEquals(57, state.manifest?.versionCode)
    assertTrue(state.prompts)
    assertTrue(state.hasUpdate)
  }

  @Test
  fun theSameCodeIsNotAnUpdate() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body(manifestJson(40)).build())
    val center = center(installed = 40)
    center.check(force = true)
    assertEquals(UpdateState.Phase.UP_TO_DATE, center.state.value.phase)
    assertNull(center.state.value.manifest)
  }

  @Test
  fun aFailedCheckKeepsTheReleaseAlreadyKnown() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body(manifestJson(57)).build())
    val center = center(installed = 40)
    center.check(force = true)
    server.enqueue(MockResponse.Builder().code(503).body("").build())
    center.check(force = true)
    val state = center.state.value
    assertEquals(UpdateState.Phase.AVAILABLE, state.phase)
    assertEquals(57, state.manifest?.versionCode)
    assertEquals(UpdateError.NETWORK, state.error)
  }

  @Test
  fun aSnoozedReleaseIsNotPromptedButIsStillShown() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body(manifestJson(57)).build())
    val center = center(installed = 40)
    center.check(force = true)
    center.snooze()
    withTimeout(5_000) { center.state.first { !it.prompts } }
    assertFalse(center.state.value.prompts)
    assertTrue(center.state.value.hasUpdate)
  }

  @Test
  fun aVerifiedDownloadBecomesReady() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body(manifestJson(57)).build())
    server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apkBytes)).build())
    val center = center(installed = 40)
    center.check(force = true)
    center.download()
    val state = center.awaitPhase(UpdateState.Phase.READY)
    assertEquals(100, state.progress)
    assertTrue(File(folder.root, "updates/sadad-57.apk").isFile)
  }

  @Test
  fun aDownloadThatDoesNotMatchTheDigestIsDeletedNotReady() = runBlocking {
    server.enqueue(
        MockResponse.Builder().code(200).body(manifestJson(57, sha256 = "d".repeat(64))).build()
    )
    server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apkBytes)).build())
    val center = center(installed = 40)
    center.check(force = true)
    center.download()
    val state = center.awaitPhase(UpdateState.Phase.FAILED)
    assertEquals(UpdateError.CHECKSUM_MISMATCH, state.error)
    assertFalse(File(folder.root, "updates/sadad-57.apk").exists())
  }

  @Test
  fun installRefusesAnApkWhoseBytesNoLongerMatch() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body(manifestJson(57)).build())
    server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apkBytes)).build())
    val center = center(installed = 40)
    center.check(force = true)
    center.download()
    center.awaitPhase(UpdateState.Phase.READY)
    File(folder.root, "updates/sadad-57.apk").writeText("tampered")
    center.install()
    val state =
        withTimeout(5_000) { center.state.first { it.error == UpdateError.CHECKSUM_MISMATCH } }
    assertEquals(UpdateState.Phase.AVAILABLE, state.phase)
  }

  @Test
  fun aNewProcessShowsTheReleaseFoundBeforeWithoutARequest() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body(manifestJson(57)).build())
    val first = center(installed = 40)
    first.check(force = true)
    assertEquals(UpdateState.Phase.AVAILABLE, first.state.value.phase)
    // A second center is a second process: empty state, the same preferences file, no new request.
    val second = center(installed = 40)
    second.restore()
    val state = second.state.value
    assertEquals(UpdateState.Phase.AVAILABLE, state.phase)
    assertNotNull(state.manifest)
    assertEquals(57, state.manifest?.versionCode)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun anInstalledBuildNoLongerSeesTheFoundRelease() = runBlocking {
    server.enqueue(MockResponse.Builder().code(200).body(manifestJson(57)).build())
    val first = center(installed = 40)
    first.check(force = true)
    // After the install the running build is 57: the stored release is not newer any more.
    val afterInstall = center(installed = 57)
    afterInstall.restore()
    assertNull(afterInstall.state.value.manifest)
  }
}
