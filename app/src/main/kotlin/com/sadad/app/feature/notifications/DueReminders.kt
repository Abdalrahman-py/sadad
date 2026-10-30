package com.sadad.app.feature.notifications

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sadad.ledger.Cents
import com.sadad.ledger.LedgerJson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class Reminder(
    val storeUserId: String,
    val debtId: String,
    val contactId: String,
    val contactName: String,
    val storeName: String,
    val amountCents: Cents,
    val dueDate: String,
    val stage: String,
    val createdAt: Long,
    val read: Boolean = false,
    val dismissed: Boolean = false,
) {
  val id: String
    get() = "$storeUserId:$debtId:$dueDate:$stage"
}

/** The sent key stays in the file when a reminder is read, dismissed or restored. */
class DueReminders(produceFile: () -> File, scope: CoroutineScope) {
  constructor(file: File, scope: CoroutineScope) : this({ file }, scope)

  // produceFile runs on the DataStore's thread at the first read, never on the caller's.
  private val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile)
  private val serializer = ListSerializer(Reminder.serializer())

  private fun decode(text: String?): List<Reminder> =
      text?.let { LedgerJson.decodeFromString(serializer, it) } ?: emptyList()

  fun inbox(storeUserId: String): Flow<List<Reminder>> = list(storeUserId, false)

  fun dismissed(storeUserId: String): Flow<List<Reminder>> = list(storeUserId, true)

  fun unreadCount(storeUserId: String): Flow<Int> =
      inbox(storeUserId).map { rows -> rows.count { !it.read } }

  private fun list(storeUserId: String, dismissed: Boolean): Flow<List<Reminder>> =
      store.data.map {
        decode(it[key(storeUserId)])
            .filter { row -> row.dismissed == dismissed }
            .sortedByDescending { row -> row.createdAt }
      }

  suspend fun record(event: Reminder): Boolean {
    var added = false
    store.edit { preferences ->
      val key = key(event.storeUserId)
      val current = decode(preferences[key])
      if (current.none { it.id == event.id }) {
        preferences[key] = LedgerJson.encodeToString(serializer, current + event)
        added = true
      }
    }
    return added
  }

  suspend fun dismiss(id: String) = update(id) { it.copy(dismissed = true) }

  suspend fun restore(id: String) = update(id) { it.copy(dismissed = false) }

  suspend fun markAllRead(storeUserId: String) {
    store.edit { preferences ->
      val key = key(storeUserId)
      preferences[key] =
          LedgerJson.encodeToString(
              serializer,
              decode(preferences[key]).map { it.copy(read = true) },
          )
    }
  }

  private suspend fun update(id: String, change: (Reminder) -> Reminder) {
    store.edit { preferences ->
      for ((key, value) in preferences.asMap()) {
        if (!key.name.startsWith("store:")) continue
        val text = value as? String ?: continue
        val current = decode(text)
        if (current.any { it.id == id }) {
          preferences[stringPreferencesKey(key.name)] =
              LedgerJson.encodeToString(
                  serializer,
                  current.map { if (it.id == id) change(it) else it },
              )
        }
      }
    }
  }

  /** Removes this store's reminders: the owner deleted the store they belonged to. */
  suspend fun forget(storeUserId: String) {
    store.edit { it.remove(key(storeUserId)) }
  }

  companion object {
    private fun key(storeUserId: String) = stringPreferencesKey("store:$storeUserId")

    @Volatile private var instance: DueReminders? = null

    fun get(context: Context): DueReminders =
        instance
            ?: synchronized(this) {
              instance
                  ?: DueReminders(
                          { File(context.applicationContext.filesDir, "reminders.preferences_pb") },
                          CoroutineScope(SupervisorJob() + Dispatchers.IO),
                      )
                      .also { instance = it }
            }
  }
}
