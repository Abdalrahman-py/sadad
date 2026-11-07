package com.sadad.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Schema 6 is destructive: opening a version 5 database at version 6 drops every table and
 * recreates them empty, including the new `contact_summary`. All data is test data (ticket 160), so
 * queued rows on a test phone are lost and there is no upgrade path left.
 *
 * The v5 file is built from Room's own exported schema (`app/schemas/…/5.json`), so the DDL is
 * exactly what a phone that shipped v5 has on disk, not a guess.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerMigrationTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val store = "migration-5-to-6"

  @Test
  fun openingVersionFiveAtSixDropsRowsAndCreatesEmptyTables() {
    val file = context.getDatabasePath("ledger-$store.db")
    file.parentFile?.mkdirs()
    file.delete()
    writeVersionFive(file.absolutePath)

    val db = LedgerDatabase.open(context, store)
    try {
      val contacts = db.openHelper.readableDatabase.query("SELECT count(1) FROM contacts")
      assertTrue(contacts.moveToFirst())
      assertEquals("destructive upgrade drops contacts", 0, contacts.getInt(0))
      contacts.close()

      val debts = db.openHelper.readableDatabase.query("SELECT count(1) FROM debts")
      assertTrue(debts.moveToFirst())
      assertEquals("destructive upgrade drops debts", 0, debts.getInt(0))
      debts.close()

      val summaries = db.openHelper.readableDatabase.query("SELECT count(1) FROM contact_summary")
      assertTrue(summaries.moveToFirst())
      assertEquals("the new summary table starts empty", 0, summaries.getInt(0))
      summaries.close()

      val meta = db.openHelper.readableDatabase.query("SELECT count(1) FROM ledger_meta")
      assertTrue(meta.moveToFirst())
      assertEquals("destructive upgrade drops ledger_meta", 0, meta.getInt(0))
      meta.close()
    } finally {
      db.close()
    }
  }

  /** The schema v5 ran: `contacts` has kind and whatsappCode, `debts` has origin. */
  private fun writeVersionFive(path: String) =
      write(path, version = 5) { sqlite ->
        sqlite.execSQL(
            """
            INSERT INTO contacts
              (id, name, phone, kind, category, note, archived, version, operationId, createdAt,
               originInstallationId, createdSeq)
            VALUES ('c1', 'سارة محمود', '', 'customer', 'customer', '', 0, 1, 'op-contact', 0, 'inst', 1)
            """
                .trimIndent()
        )
        insertDebt(sqlite)
      }

  private fun insertDebt(sqlite: SQLiteDatabase) {
    sqlite.execSQL(
        """
        INSERT INTO debts
          (id, contactId, direction, amountCents, note, dueDate, version, operationId, createdAt,
           originInstallationId, createdSeq)
        VALUES ('d1', 'c1', 'receivable', 12000, '', NULL, 1, 'op-debt', 0, 'inst', 2)
        """
            .trimIndent()
    )
  }

  /** Room's own DDL for [version], then the rows the test wants, then the version stamp. */
  private fun write(path: String, version: Int, rows: (SQLiteDatabase) -> Unit) {
    val schema = database(version = version)
    val sqlite = SQLiteDatabase.openOrCreateDatabase(path, null)
    try {
      for (query in schema.getValue("setupQueries").jsonArray) {
        sqlite.execSQL(query.jsonPrimitive.content)
      }
      for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
        val table = entity.getValue("tableName").jsonPrimitive.content
        sqlite.execSQL(entity.getValue("createSql").jsonPrimitive.content.withTable(table))
        entity["indices"]?.jsonArray?.forEach { index ->
          val sql = index.jsonObject.getValue("createSql").jsonPrimitive.content.withTable(table)
          sqlite.execSQL(sql)
        }
      }
      rows(sqlite)
      sqlite.version = version
    } finally {
      sqlite.close()
    }
  }

  private fun database(version: Int) =
      Json.parseToJsonElement(schemaText(version)).jsonObject.getValue("database").jsonObject

  /**
   * The exported schema for [version], read from the directory the build hands over as
   * `sadad.schemas` (Room wrote it to `app/schemas`). A missing file is a failure, not a skip:
   * without it this test proves nothing about what is on a phone.
   */
  private fun schemaText(version: Int): String {
    val directory = checkNotNull(System.getProperty("sadad.schemas")) { "sadad.schemas is not set" }
    val file = java.io.File(directory, "$SCHEMA_DIR/$version.json")
    check(file.isFile) { "${file.path} is missing" }
    return file.readText()
  }

  private fun String.withTable(table: String) = replace("\${TABLE_NAME}", table)

  private companion object {
    const val SCHEMA_DIR = "com.sadad.app.data.db.LedgerDatabase"
  }
}
