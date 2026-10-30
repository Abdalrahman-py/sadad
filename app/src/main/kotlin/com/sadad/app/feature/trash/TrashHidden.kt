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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The ids this phone removed from its trash list for good. The server keeps the archived Contact;
 * the Java app behaved the same way, so another phone still lists it.
 */
class TrashHidden(produceFile: () -> File, private val scope: CoroutineScope) {
  constructor(file: File, scope: CoroutineScope) : this({ file }, scope)

  // produceFile runs on the DataStore's thread at the first read, never on the caller's.
  private val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile)

  val ids: Flow<Set<String>> = store.data.map { it[IDS].orEmpty() }

  suspend fun hide(id: String) {
    store.edit { it[IDS] = it[IDS].orEmpty() + id }
  }

  companion object {
    private val instances = ConcurrentHashMap<String, TrashHidden>()

    /** Drops the cached list and its file: the store they belonged to is gone for good. */
    fun forget(context: Context, storeUserId: String) {
      val instance = instances.remove(storeUserId) ?: return
      instance.scope.cancel()
      File(context.applicationContext.filesDir, "trash-$storeUserId.preferences_pb").delete()
    }

    /** One DataStore per store file: a second instance for the same file would throw. */
    fun get(context: Context, storeUserId: String): TrashHidden =
        instances.computeIfAbsent(storeUserId) {
          TrashHidden(
              { File(context.applicationContext.filesDir, "trash-$it.preferences_pb") },
              CoroutineScope(SupervisorJob() + Dispatchers.IO),
          )
        }

    private val IDS = stringSetPreferencesKey("ids")
  }
}
