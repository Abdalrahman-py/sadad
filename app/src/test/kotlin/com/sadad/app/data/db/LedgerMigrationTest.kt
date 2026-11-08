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
 * The upgrade from the first schema keeps what the store already had. The local database is the
 * only copy of rows this phone has not pushed yet, so an upgrade that drops the tables throws away
 * real work: this test opens a v1 file holding a Contact and a Debt and expects both to survive,
 * with the Contact's new `kind` filled from the migration's default.
 *
 * The v1 file is built from Room's own exported schema (`app/schemas/…/1.json`), so the DDL is
 * exactly what a phone that shipped v1 has on disk, not a guess.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerMigrationTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val store = "migration"

  @Test
  fun aVersionOneDatabaseKeepsItsRowsAndGainsTheContactKind() {
    val file = context.getDatabasePath("ledger-$store.db")
    file.parentFile?.mkdirs()
    writeVersionOne(file.absolutePath)

    val db = LedgerDatabase.open(context, store)
    try {
      val contacts = db.openHelper.readableDatabase.query("SELECT id, kind FROM contacts")
      assertTrue("the Contact must survive the upgrade", contacts.moveToFirst())
      assertEquals("c1", contacts.getString(0))
      assertEquals("customer", contacts.getString(1))
      contacts.close()

      val debts = db.openHelper.readableDatabase.query("SELECT count(1) FROM debts")
      assertTrue("the Debt must survive the upgrade", debts.moveToFirst())
      assertEquals(1, debts.getInt(0))
      debts.close()
    } finally {
      db.close()
    }
  }

  @Test
  fun aVersionThreeDatabaseKeepsItsRowsAndGainsTheDebtOriginAndTheOffsetsTable() {
    val file = context.getDatabasePath("ledger-migration-3.db")
    file.parentFile?.mkdirs()
    writeVersionThree(file.absolutePath)

    val db = LedgerDatabase.open(context, "migration-3")
    try {
      val debts = db.openHelper.readableDatabase.query("SELECT id, origin FROM debts")
      assertTrue("the Debt must survive the upgrade", debts.moveToFirst())
      assertEquals("d1", debts.getString(0))
      assertEquals("manual", debts.getString(1))
      debts.close()

      val offsets = db.openHelper.readableDatabase.query("SELECT count(1) FROM offsets")
      assertTrue(offsets.moveToFirst())
      assertEquals(0, offsets.getInt(0))
      offsets.close()

      val contacts = db.openHelper.readableDatabase.query("SELECT count(1) FROM contacts")
      assertTrue(contacts.moveToFirst())
      assertEquals(1, contacts.getInt(0))
      contacts.close()
    } finally {
      db.close()
    }
  }

  @Test
  fun aVersionFiveDatabaseKeepsItsRowsCursorAndOutboxAndGainsEmptySummaries() {
    val store5 = "migration-5-to-6"
    val file = context.getDatabasePath("ledger-$store5.db")
    file.parentFile?.mkdirs()
    file.delete()
    writeVersionFive(file.absolutePath)

    val db = LedgerDatabase.open(context, store5)
    try {
      val contacts = db.openHelper.readableDatabase.query("SELECT id FROM contacts")
      assertTrue("the Contact must survive the upgrade", contacts.moveToFirst())
      assertEquals("c1", contacts.getString(0))
      contacts.close()

      val debts = db.openHelper.readableDatabase.query("SELECT id FROM debts")
      assertTrue("the Debt must survive the upgrade", debts.moveToFirst())
      assertEquals("d1", debts.getString(0))
      debts.close()

      val payments = db.openHelper.readableDatabase.query("SELECT id FROM payments")
      assertTrue("the Payment must survive the upgrade", payments.moveToFirst())
      assertEquals("p1", payments.getString(0))
      payments.close()

      val meta = db.openHelper.readableDatabase.query("SELECT cursor FROM ledger_meta WHERE id = 0")
      assertTrue("ledger_meta must survive the upgrade", meta.moveToFirst())
      assertEquals(7L, meta.getLong(0))
      meta.close()

      val outbox = db.openHelper.readableDatabase.query("SELECT entityId, state FROM outbox")
      assertTrue("the queued outbox row must survive the upgrade", outbox.moveToFirst())
      assertEquals("c1", outbox.getString(0))
      assertEquals("queued", outbox.getString(1))
      outbox.close()

      val summaries = db.openHelper.readableDatabase.query("SELECT count(1) FROM contact_summary")
      assertTrue(summaries.moveToFirst())
      assertEquals("the new summary table starts empty", 0, summaries.getInt(0))
      summaries.close()
    } finally {
      db.close()
    }
  }

  /**
   * Writes a v1 database the way a phone that ran the first schema has it: Room's DDL, two rows.
   */
  private fun writeVersionOne(path: String) =
      write(path, version = 1) { sqlite ->
        sqlite.execSQL(
            """
            INSERT INTO contacts
              (id, name, phone, category, note, archived, version, operationId, createdAt,
               originInstallationId, createdSeq)
            VALUES ('c1', 'سارة محمود', '', 'customer', '', 0, 1, 'op-contact', 0, 'inst', 1)
            """
                .trimIndent()
        )
        insertDebt(sqlite)
      }

  /** The schema the phones ran before credit: `contacts` has its kind, `debts` has no origin. */
  private fun writeVersionThree(path: String) =
      write(path, version = 3) { sqlite ->
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

  /** The schema v1.3.0 ran: kind, whatsappCode, origin, plus a cursor and a queued outbox row. */
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
        sqlite.execSQL(
            """
            INSERT INTO payments
              (id, debtId, amountCents, method, note, reversed, operationId, createdAt,
               originInstallationId, createdSeq)
            VALUES ('p1', 'd1', 2000, 'cash', '', 0, 'op-payment', 0, 'inst', 3)
            """
                .trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO ledger_meta (id, storeId, epoch, cursor)
            VALUES (0, 42, 'epoch-5', 7)
            """
                .trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO outbox
              (operationId, entityId, type, state, code, createdAt, recoveryPermit, payload)
            VALUES ('op-queued', 'c1', 'contact.create', 'queued', NULL, 0, NULL, X'0102')
            """
                .trimIndent()
        )
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
