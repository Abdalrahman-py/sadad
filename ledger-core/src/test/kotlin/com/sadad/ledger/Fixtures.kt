package com.sadad.ledger

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Reads `contract/fixtures` (path from the `sadad.fixtures` system property set by Gradle). */
object Fixtures {
  const val INSTALLATION_ID = "5e0f2a1b-3c4d-4e5f-8a6b-7c8d9e0f1a2b"
  const val EPOCH = "0d1e2f3a-4b5c-4d6e-8f7a-8b9c0d1e2f3a"

  private val root: File by lazy {
    val path = System.getProperty("sadad.fixtures") ?: error("sadad.fixtures is not set")
    File(path).also { require(it.isDirectory) { "missing fixtures directory $it" } }
  }

  /** One fixture file with `{installationId}` and `{epoch}` filled in. */
  fun read(name: String): JsonObject {
    val text =
        File(root, name)
            .readText()
            .replace("{installationId}", INSTALLATION_ID)
            .replace("{epoch}", EPOCH)
    return Json.parseToJsonElement(text).jsonObject
  }

  /** A contract file relative to the fixtures directory, read as is (`../openapi.json`). */
  fun readFile(name: String): JsonObject =
      Json.parseToJsonElement(File(root, name).readText()).jsonObject
}
