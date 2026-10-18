package com.sadad.app.export

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.db.Events
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfWriterTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun writesARealPdfWithOnePagePerContact() {
    val at = Instant.EPOCH
    val contacts = listOf("سامي", "ليلى").map { Commands.contactCreate(Events.origin, at, it) }
    val statements = Statement.build(exportView(*contacts.toTypedArray()), null, null, null)
    val out = ByteArrayOutputStream()
    PdfWriter(ExportLabels.from(context)).write(statements, "متجر سدد", "أحمد", "كل الفترات", out)
    assertTrue(out.size() > 100)
    assertEquals("%PDF", out.toByteArray().take(4).toByteArray().toString(Charsets.US_ASCII))
    assertEquals(2, pageCount(out.toByteArray()))
  }

  @Test
  fun longArabicNotesContinueAcrossPages() {
    val at = Instant.EPOCH
    val contact = Commands.contactCreate(Events.origin, at, "سامي")
    val debt = Commands.debtCreate(Events.origin, at, contact.entityId, Direction.RECEIVABLE, Cents(100), "تفاصيل البضاعة المشتراة ".repeat(500))
    val out = ByteArrayOutputStream()
    PdfWriter(ExportLabels.from(context)).write(Statement.build(exportView(contact, debt), null, null, null), "متجر", "مالك", "الفترة", out)
    assertTrue(pageCount(out.toByteArray()) > 1)
  }

  private fun pageCount(bytes: ByteArray): Int {
    val file = File.createTempFile("statement-test", ".pdf", context.cacheDir)
    try {
      file.writeBytes(bytes)
      return PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
    } finally { file.delete() }
  }
}
