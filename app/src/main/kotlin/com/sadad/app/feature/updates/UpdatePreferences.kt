package com.sadad.app.feature.updates

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the update check learned, kept across restarts: the last check, the snooze, and the release
 * it last found. The manifest is stored whole so the settings row can show an update on the next
 * launch without a request, which is what keeps a security patch visible.
 */
internal data class UpdateSnapshot(
    val lastCheckMs: Long = 0,
    val snoozedVersionCode: Int = 0,
    val snoozedUntilMs: Long = 0,
    val manifest: UpdateManifest? = null,
)

/** `updates.preferences_pb`: one file, one instance, like the settings the theme keeps. */
internal class UpdatePreferences
private constructor(
    private val store: DataStore<Preferences>,
    private val channel: UpdateChannel,
) {
  val snapshot: Flow<UpdateSnapshot> =
      store.data.map { saved ->
        UpdateSnapshot(
            lastCheckMs = saved[LAST_CHECK] ?: 0,
            snoozedVersionCode = saved[SNOOZED_CODE] ?: 0,
            snoozedUntilMs = saved[SNOOZED_UNTIL] ?: 0,
            manifest = saved[FOUND]?.let { text -> decode(text) },
        )
      }

  suspend fun recordCheck(nowMs: Long, manifest: UpdateManifest?) {
    store.edit { saved ->
      saved[LAST_CHECK] = nowMs
      if (manifest == null) saved.remove(FOUND) else saved[FOUND] = encodeManifest(manifest)
    }
  }

  suspend fun snooze(versionCode: Int, untilMs: Long) {
    store.edit { saved ->
      saved[SNOOZED_CODE] = versionCode
      saved[SNOOZED_UNTIL] = untilMs
    }
  }

  /** After the new build is running: nothing is pending, and the snooze belongs to the old code. */
  suspend fun clear() {
    store.edit { saved ->
      saved.remove(FOUND)
      saved.remove(SNOOZED_CODE)
      saved.remove(SNOOZED_UNTIL)
    }
  }

  private fun decode(text: String): UpdateManifest? =
      (parseManifest(text, channel) as? ManifestResult.Found)?.manifest

  companion object {
    const val FILE_NAME = "updates.preferences_pb"

    private val LAST_CHECK = longPreferencesKey("last_check_ms")
    private val SNOOZED_CODE = intPreferencesKey("snoozed_version_code")
    private val SNOOZED_UNTIL = longPreferencesKey("snoozed_until_ms")
    private val FOUND = stringPreferencesKey("found_manifest")

    @Volatile private var instance: UpdatePreferences? = null

    /**
     * [produceFile] runs on the DataStore's thread at the first read, never on the caller's: the
     * store tab and the installer's receiver both reach this on the main thread.
     */
    fun create(
        scope: CoroutineScope,
        produceFile: () -> File,
        channel: UpdateChannel,
    ): UpdatePreferences =
        UpdatePreferences(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile),
            channel,
        )

    fun get(context: Context): UpdatePreferences =
        instance
            ?: synchronized(this) {
              val app = context.applicationContext
              instance
                  ?: create(
                          CoroutineScope(SupervisorJob() + Dispatchers.IO),
                          { File(app.filesDir, FILE_NAME) },
                          UpdateChannel.current(),
                      )
                      .also { instance = it }
            }
  }
}
