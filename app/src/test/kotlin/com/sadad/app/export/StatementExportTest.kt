package com.sadad.app.export

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.db.Events
import com.sadad.ledger.Commands
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatementExportTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val view = exportView(Commands.contactCreate(Events.origin, Instant.EPOCH, "سامي"))
  private val header = StatementHeader("متجر", "مالك", "كل الفترات")

  @Test
  fun savesXlsxToTheSelectedDocument() = runBlocking {
    val file = File.createTempFile("statement-save", ".xlsx", context.cacheDir)
    try {
      StatementExport.save(
          context,
          Uri.fromFile(file),
          StatementFormat.XLSX,
          header,
          view,
          null,
          null,
          null,
      )
      assertTrue(unzip(file.readBytes()).containsKey("xl/workbook.xml"))
    } finally {
      file.delete()
    }
  }

  @Test
  fun aNewStatementRemovesThePreviousOneAndClearExportsEmptiesTheDirectory() = runBlocking {
    // Robolectric cannot render a real PDF (see ReportsExportTest), so the real write fails; the
    // clearing step inside writePdf still runs and must take the predecessor with it.
    val directory =
        File(context.cacheDir, "exports").apply {
          mkdirs()
          File(this, "statement-previous.pdf").writeBytes(byteArrayOf(1))
        }
    runCatching { StatementExport.writePdf(context, header, view, null, null, null) }
    assertTrue(
        "a new statement must leave no predecessor behind",
        directory.listFiles()!!.isEmpty(),
    )

    File(directory, "statement-a.pdf").writeBytes(byteArrayOf(1))
    File(directory, "statement-b.pdf").writeBytes(byteArrayOf(1))

    StatementExport.clearExports(context)

    assertEquals(0, directory.listFiles()!!.size)
  }

  @Test
  fun refusesMissingContactBeforeTruncatingADocument() = runBlocking {
    val file = File.createTempFile("statement-unchanged", ".xlsx", context.cacheDir)
    try {
      file.writeText("keep this document")
      val result = runCatching {
        StatementExport.save(
            context,
            Uri.fromFile(file),
            StatementFormat.XLSX,
            header,
            view,
            "missing",
            null,
            null,
        )
      }
      assertTrue(result.exceptionOrNull() is IllegalArgumentException)
      assertEquals("keep this document", file.readText())
    } finally {
      file.delete()
    }
  }
}
