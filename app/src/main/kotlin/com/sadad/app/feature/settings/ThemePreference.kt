package com.sadad.app.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sadad.app.feature.history.Period
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.feature.history.periodOf
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeChoice(val key: String) {
  SYSTEM("system"),
  LIGHT("light"),
  DARK("dark");

  /** [systemDark] is the device's night mode; it decides only for [SYSTEM]. */
  fun isDark(systemDark: Boolean): Boolean =
      when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
      }
}

/**
 * The statement export period the settings screen shares. A preset follows the day it is used on
 * («هذا الشهر» is always the current month); only a custom range holds dates.
 */
sealed interface ExportPeriod {
  data class Preset(val preset: PeriodPreset) : ExportPeriod

  data class Custom(val from: LocalDate, val to: LocalDate) : ExportPeriod
}

fun ExportPeriod.toPeriod(today: LocalDate): Period =
    when (this) {
      is ExportPeriod.Preset -> periodOf(preset, today)
      is ExportPeriod.Custom -> Period.Custom(from, to)
    }

/**
 * `settings.preferences_pb`: the theme and the export period, both phone-wide. One instance owns
 * the file, so the activity and the settings screen share [get].
 */
class ThemePreference private constructor(private val store: DataStore<Preferences>) {
  val theme: Flow<ThemeChoice> =
      store.data.map { saved ->
        ThemeChoice.entries.firstOrNull { it.key == saved[THEME] } ?: ThemeChoice.SYSTEM
      }

  val exportPeriod: Flow<ExportPeriod> = store.data.map { decode(it[EXPORT_PERIOD]) }

  suspend fun setTheme(choice: ThemeChoice) {
    store.edit { it[THEME] = choice.key }
  }

  suspend fun setExportPeriod(value: ExportPeriod) {
    store.edit { it[EXPORT_PERIOD] = encode(value) }
  }

  companion object {
    const val FILE_NAME = "settings.preferences_pb"

    private val THEME = stringPreferencesKey("theme")
    private val EXPORT_PERIOD = stringPreferencesKey("export_period")
    private val DEFAULT_PERIOD = ExportPeriod.Preset(PeriodPreset.MONTH)

    @Volatile private var instance: ThemePreference? = null

    fun create(scope: CoroutineScope, file: File): ThemePreference = create(scope) { file }

    /** [produceFile] runs on the DataStore's thread at the first read, never on the caller's. */
    private fun create(scope: CoroutineScope, produceFile: () -> File): ThemePreference =
        ThemePreference(PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile))

    /**
     * One per process: a second DataStore on the same file is refused. `filesDir` touches the disk,
     * so it is resolved lazily: the activity calls this on the main thread during the first frame.
     */
    fun get(context: Context): ThemePreference =
        instance
            ?: synchronized(this) {
              val app = context.applicationContext
              instance
                  ?: create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                        File(app.filesDir, FILE_NAME)
                      }
                      .also { instance = it }
            }

    private fun encode(value: ExportPeriod): String =
        when (value) {
          is ExportPeriod.Preset -> value.preset.name
          is ExportPeriod.Custom -> "CUSTOM:${value.from}:${value.to}"
        }

    private fun decode(text: String?): ExportPeriod {
      if (text == null) return DEFAULT_PERIOD
      val parts = text.split(':')
      return if (parts[0] == "CUSTOM" && parts.size == 3) {
        ExportPeriod.Custom(LocalDate.parse(parts[1]), LocalDate.parse(parts[2]))
      } else {
        PeriodPreset.entries.firstOrNull { it.name == text }?.let(ExportPeriod::Preset)
            ?: DEFAULT_PERIOD
      }
    }
  }
}
