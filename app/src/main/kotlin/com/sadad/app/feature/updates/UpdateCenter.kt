package com.sadad.app.feature.updates

import android.content.Context
import com.sadad.app.BuildConfig
import com.sadad.app.data.net.ApiHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that decides what to offer and does the work: read the channel's manifest, compare
 * by `versionCode`, download and prove the APK, then hand it to the system installer.
 *
 * A check runs at app start (at most every [CHECK_INTERVAL_MS]), daily from [UpdateCheckWorker],
 * and whenever the user asks. Only the worker posts a notification: a check the user started is
 * already on screen.
 */
internal class UpdateCenter(
    private val context: Context,
    private val preferences: UpdatePreferences,
    private val source: UpdateSource,
    private val downloader: ApkDownloader,
    private val installedVersionCode: () -> Int,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
  private val _state = MutableStateFlow(UpdateState())
  val state: StateFlow<UpdateState> = _state.asStateFlow()

  private val checkMutex = Mutex()
  private var downloadJob: Job? = null

  /**
   * Loads the last result and the snooze. On the next launch the settings row still shows a release
   * found days ago without another request, which is what keeps a security patch in front of the
   * user after a snooze or an offline start.
   */
  suspend fun restore() {
    val saved = preferences.snapshot.first()
    val manifest = saved.manifest?.takeIf { isNewer(installedVersionCode(), it) }
    _state.update { current ->
      when {
        manifest == null -> current.copy(manifest = null, prompts = false)
        current.hasUpdate -> current.copy(lastCheckMs = saved.lastCheckMs.takeIf { it > 0 })
        else ->
            current.copy(
                phase = UpdateState.Phase.AVAILABLE,
                manifest = manifest,
                lastCheckMs = saved.lastCheckMs.takeIf { it > 0 },
                prompts = promptsFor(manifest, saved),
            )
      }
    }
  }

  /**
   * The app started: restore what was found, then ask the release server if the answer is stale.
   */
  fun refreshIfStale() {
    guarded {
      restore()
      if (UpdateChannel.autoChecks(BuildConfig.BUILD_TYPE)) check(force = false)
    }
  }

  /** A tap on «تحقق من التحديثات»: no interval, and a downloaded file is left alone. */
  fun checkNow() {
    guarded {
      restore()
      check(force = true)
    }
  }

  /**
   * A check started from a lifecycle hook must never reach the thread's uncaught handler: what it
   * can fail on besides the network is the preferences file, and the row simply keeps saying «تحقق
   * من وجود إصدار أحدث» until the next launch or the daily worker tries again.
   */
  private fun guarded(block: suspend () -> Unit) {
    scope.launch {
      try {
        block()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: RuntimeException) {
        _state.update { it.copy(phase = UpdateState.Phase.IDLE) }
      }
    }
  }

  /**
   * [force] skips the interval, for a tap on «تحقق من التحديثات». A failure keeps a release that is
   * already known: the row must not disappear because the network dropped.
   */
  suspend fun check(force: Boolean) = checkMutex.withLock {
    // A download in flight (or a session the system is holding) is more specific than a check.
    if (_state.value.phase in WORKING) return@withLock
    val saved = preferences.snapshot.first()
    val now = clock()
    if (!force && now - saved.lastCheckMs < CHECK_INTERVAL_MS && _state.value.lastCheckMs != null) {
      return@withLock
    }
    _state.update { it.copy(phase = UpdateState.Phase.CHECKING, error = null) }
    val result = source.latest()
    val installed = installedVersionCode()
    val found = (result as? ManifestResult.Found)?.manifest?.takeIf { isNewer(installed, it) }
    val failure =
        when (result) {
          is ManifestResult.Found -> null
          ManifestResult.Missing -> UpdateError.NO_MANIFEST
          ManifestResult.Invalid -> UpdateError.BAD_MANIFEST
          is ManifestResult.Unreachable -> UpdateError.NETWORK
        }
    preferences.recordCheck(now, found)
    if (found != null) downloader.keepOnly(found.versionCode)
    else UpdateNotifications.cancel(context)
    _state.update { current ->
      if (found != null) {
        // A check started from the update screen must not throw away a verified download.
        val ready =
            current.phase == UpdateState.Phase.READY &&
                current.manifest?.versionCode == found.versionCode
        current.copy(
            phase = if (ready) UpdateState.Phase.READY else UpdateState.Phase.AVAILABLE,
            manifest = found,
            progress = if (ready) 100 else 0,
            error = null,
            lastCheckMs = now,
            prompts = promptsFor(found, saved),
        )
      } else {
        // Keep a release found earlier; only the check itself failed.
        val kept = current.manifest?.takeIf { isNewer(installed, it) }
        current.copy(
            phase =
                if (kept != null) UpdateState.Phase.AVAILABLE
                else if (failure == null) UpdateState.Phase.UP_TO_DATE
                else UpdateState.Phase.FAILED,
            manifest = kept,
            error = failure,
            lastCheckMs = now,
        )
      }
    }
  }

  /**
   * Downloads on a tap, never in the background: the APK is several megabytes of someone's data.
   */
  fun download() {
    val manifest = _state.value.manifest ?: return
    if (downloadJob?.isActive == true) return
    downloadJob = scope.launch {
      _state.update { it.copy(phase = UpdateState.Phase.DOWNLOADING, progress = 0, error = null) }
      val error =
          try {
            downloader.download(
                manifest = manifest,
                url = source.apkUrl(manifest),
                onProgress = { percent -> _state.update { it.copy(progress = percent) } },
                fallbackUrl = { source.apkFallbackUrl(manifest) },
            )
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (_: RuntimeException) {
            UpdateError.DOWNLOAD_FAILED
          }
      _state.update {
        it.copy(
            phase = if (error == null) UpdateState.Phase.READY else UpdateState.Phase.FAILED,
            progress = if (error == null) 100 else it.progress,
            error = error,
        )
      }
    }
  }

  fun cancelDownload() {
    downloadJob?.cancel()
    downloadJob = null
    _state.update { it.copy(phase = UpdateState.Phase.AVAILABLE, progress = 0) }
  }

  /**
   * Re-proves the file, then the key, then the permission — in that order, so the message names the
   * one thing that stopped the install instead of the installer's generic failure.
   */
  fun install() {
    val manifest = _state.value.manifest ?: return
    scope.launch {
      try {
        start(manifest)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: RuntimeException) {
        _state.update {
          it.copy(phase = UpdateState.Phase.READY, error = UpdateError.INSTALL_FAILED)
        }
      }
    }
  }

  private suspend fun start(manifest: UpdateManifest) {
    val apk = downloader.file(manifest.versionCode)
    val verified =
        apk.isFile &&
            apk.length() == manifest.sizeBytes &&
            ApkVerifier.sha256(apk) == manifest.sha256
    if (!verified) {
      _state.update {
        it.copy(phase = UpdateState.Phase.AVAILABLE, error = UpdateError.CHECKSUM_MISMATCH)
      }
      return
    }
    // The digest proves the bytes are the published ones; this proves the published ones are the
    // release the manifest names, so a manifest pointing at the previous APK cannot install it.
    if (ApkVerifier.apkVersionCode(context, apk) != manifest.versionCode) {
      _state.update {
        it.copy(phase = UpdateState.Phase.AVAILABLE, error = UpdateError.BAD_MANIFEST)
      }
      return
    }
    val sameKey =
        ApkVerifier.signedBySameKey(
            ApkVerifier.installedSignatures(context),
            ApkVerifier.apkSignatures(context, apk),
        )
    if (!sameKey) {
      _state.update {
        it.copy(phase = UpdateState.Phase.READY, error = UpdateError.SIGNATURE_MISMATCH)
      }
      return
    }
    if (!UpdateInstaller.canInstall(context)) {
      _state.update {
        it.copy(phase = UpdateState.Phase.READY, error = UpdateError.NEEDS_PERMISSION)
      }
      return
    }
    if (UpdateInstaller.start(context, apk)) {
      _state.update { it.copy(phase = UpdateState.Phase.INSTALLING, error = null) }
    } else {
      _state.update {
        it.copy(phase = UpdateState.Phase.READY, error = UpdateError.INSTALL_FAILED)
      }
    }
  }

  /** «لاحقاً»: the prompt and the notification go quiet for a week; the settings row stays. */
  fun snooze() {
    val manifest = _state.value.manifest ?: return
    _state.update { it.copy(prompts = false) }
    val until = clock() + SNOOZE_MS
    scope.launch {
      preferences.snooze(manifest.versionCode, until)
      UpdateNotifications.cancel(context)
    }
  }

  /** «تحديث»: the prompt steps aside for the update screen, with no snooze recorded. */
  fun dismissPrompt() {
    _state.update { it.copy(prompts = false) }
  }

  /** The system installer says the new build is in place; this process is about to be replaced. */
  fun installed() {
    _state.update { UpdateState(phase = UpdateState.Phase.UP_TO_DATE) }
    scope.launch {
      preferences.clear()
      downloader.keepOnly(0)
      UpdateNotifications.cancel(context)
    }
  }

  fun installFailed(error: UpdateError) {
    _state.update { it.copy(phase = UpdateState.Phase.READY, error = error) }
  }

  /** The release download link, so a refused install can still be finished in a browser. */
  fun apkUrl(): String? = _state.value.manifest?.let { source.apkUrl(it) }

  fun notifyAvailable() {
    val manifest = _state.value.manifest ?: return
    UpdateNotifications.post(context, manifest)
  }

  private fun promptsFor(manifest: UpdateManifest, saved: UpdateSnapshot): Boolean =
      shouldPrompt(
          manifest,
          saved.snoozedVersionCode,
          saved.snoozedUntilMs,
          clock(),
      )

  companion object {
    /** Mihon's interval; here it only throttles the app-start check, the worker runs daily. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

    /** A check would only overwrite work already in progress. */
    private val WORKING = setOf(UpdateState.Phase.DOWNLOADING, UpdateState.Phase.INSTALLING)

    @Volatile private var instance: UpdateCenter? = null

    fun get(context: Context): UpdateCenter =
        instance
            ?: synchronized(this) {
              val app = context.applicationContext
              instance ?: create(app).also { instance = it }
            }

    private fun create(context: Context): UpdateCenter {
      val channel = UpdateChannel.current()
      // The shared connection pool, with room to read a slow multi-megabyte body.
      val client = ApiHttpClient.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
      return UpdateCenter(
          context = context,
          preferences = UpdatePreferences.get(context),
          source = UpdateSource(client, channel),
          downloader = ApkDownloader(client, { File(context.cacheDir, "updates") }),
          installedVersionCode = { BuildConfig.VERSION_CODE },
          clock = System::currentTimeMillis,
          scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
      )
    }
  }
}
