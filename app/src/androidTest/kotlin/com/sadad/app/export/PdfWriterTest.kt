package com.sadad.app.export

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// PdfDocument has no Robolectric shadow/native implementation; exercise the actual Android API.
@RunWith(AndroidJUnit4::class)
class PdfWriterTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun writesARealPdfWithOnePagePerContact() =
      runBlocking(Dispatchers.IO) {
        val at = Instant.EPOCH
        val contacts = listOf("سامي", "ليلى").map { Commands.contactCreate(exportOrigin, at, it) }
        val statements =
            Statement.build(exportDeviceView(*contacts.toTypedArray()), null, null, null)
        val out = ByteArrayOutputStream()
        PdfWriter(ExportLabels.from(context))
            .write(statements, "متجر سدد", "أحمد", "كل الفترات", out)
        assertTrue(out.size() > 100)
        assertEquals("%PDF", out.toByteArray().take(4).toByteArray().toString(Charsets.US_ASCII))
        assertEquals(2, pageCount(out.toByteArray()))
      }

  @Test
  fun longArabicNotesContinueAcrossPages() =
      runBlocking(Dispatchers.IO) {
        val at = Instant.EPOCH
        val contact = Commands.contactCreate(exportOrigin, at, "سامي")
        val debt =
            Commands.debtCreate(
                exportOrigin,
                at,
                contact.entityId,
                Direction.RECEIVABLE,
                Cents(100),
                "تفاصيل البضاعة المشتراة ".repeat(500),
            )
        val out = ByteArrayOutputStream()
        PdfWriter(ExportLabels.from(context))
            .write(
                Statement.build(exportDeviceView(contact, debt), null, null, null),
                "متجر",
                "مالك",
                "الفترة",
                out,
            )
        assertTrue(pageCount(out.toByteArray()) > 1)
      }

  private fun pageCount(bytes: ByteArray): Int {
    val file = File.createTempFile("statement-test", ".pdf", context.cacheDir)
    try {
      file.writeBytes(bytes)
      return PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use {
        it.pageCount
      }
    } finally {
      file.delete()
    }
  }
}
