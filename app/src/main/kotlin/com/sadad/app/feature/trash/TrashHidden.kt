package com.sadad.app.feature.trash

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The ids this phone removed from its trash list for good. The server keeps the archived Contact;
 * the Java app behaved the same way, so another phone still lists it.
 */
class TrashHidden(file: File, scope: CoroutineScope) {
  private val store = PreferenceDataStoreFactory.create(scope = scope) { file }

  val ids: Flow<Set<String>> = store.data.map { it[IDS].orEmpty() }

  suspend fun hide(id: String) {
    store.edit { it[IDS] = it[IDS].orEmpty() + id }
  }

  companion object {
    private val instances = ConcurrentHashMap<String, TrashHidden>()

    /** One DataStore per store file: a second instance for the same file would throw. */
    fun get(context: Context, storeUserId: String): TrashHidden =
        instances.computeIfAbsent(storeUserId) {
          TrashHidden(
              File(context.applicationContext.filesDir, "trash-$it.preferences_pb"),
              CoroutineScope(SupervisorJob() + Dispatchers.IO),
          )
        }

    private val IDS = stringSetPreferencesKey("ids")
  }
}
