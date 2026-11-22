package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sadad.app.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `quickadd.preferences_pb`: the note dialog's frequent items, kept on this phone only (never
 * synced, one list for every store on it). Until the owner saves a list, [items] is [seed]; after
 * that the saved list wins, an empty one included.
 */
class FrequentItems
private constructor(private val store: DataStore<Preferences>, private val seed: List<String>) {
  val items: Flow<List<String>> =
      store.data.map { saved ->
        saved[ITEMS]?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: seed
      }

  /** Saves [items] as given; the editor builds them only through [add]. */
  suspend fun save(items: List<String>) {
    store.edit { it[ITEMS] = items.joinToString(SEPARATOR) }
  }

  companion object {
    const val FILE_NAME = "quickadd.preferences_pb"
    const val MAX_ITEMS = 12
    const val MAX_CHARS = 24

    private const val SEPARATOR = "\n"
    private val ITEMS = stringPreferencesKey("frequent_items")

    @Volatile private var instance: FrequentItems? = null

    /**
     * [items] plus [raw] trimmed, or null when it is refused: blank, longer than [MAX_CHARS], more
     * than one line, already in the list (case-insensitive), or a 13th item.
     */
    fun add(items: List<String>, raw: String): List<String>? {
      val item = raw.trim()
      val refused =
          item.isEmpty() ||
              item.length > MAX_CHARS ||
              SEPARATOR in item ||
              items.size >= MAX_ITEMS ||
              items.any { it.equals(item, ignoreCase = true) }
      return if (refused) null else items + item
    }

    fun create(scope: CoroutineScope, file: File, seed: List<String>): FrequentItems =
        create(scope, seed) { file }

    private fun create(
        scope: CoroutineScope,
        seed: List<String>,
        produceFile: () -> File,
    ): FrequentItems =
        FrequentItems(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile),
            seed,
        )

    /** One per process: a second DataStore on the same file is refused. */
    fun get(context: Context): FrequentItems =
        instance
            ?: synchronized(this) {
              val app = context.applicationContext
              instance
                  ?: create(
                          CoroutineScope(SupervisorJob() + Dispatchers.IO),
                          app.resources.getStringArray(R.array.quickadd_note_suggestions).toList(),
                      ) {
                        File(app.filesDir, FILE_NAME)
                      }
                      .also { instance = it }
            }
  }
}
