package com.sadad.app.data.net

import android.content.Context
import com.sadad.ledger.LedgerJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** One asset read per mapper; unknown server codes receive the same safe Arabic fallback. */
class ErrorMessages(context: Context) {
  private val messages =
      context.assets.open("errors.json").bufferedReader(Charsets.UTF_8).use {
        LedgerJson.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            it.readText(),
        )
      }

  fun text(code: String): String = messages[code] ?: messages.getValue("temporary_service_failure")
}
