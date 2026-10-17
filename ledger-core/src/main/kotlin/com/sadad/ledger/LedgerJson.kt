package com.sadad.ledger

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for the Ledger wire format: defaults are sent (`schemaVersion`, an
 * empty `dependsOn`), an absent optional field is omitted rather than sent as `null`, and fields
 * the server adds later are ignored when decoding.
 */
val LedgerJson: Json = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}
