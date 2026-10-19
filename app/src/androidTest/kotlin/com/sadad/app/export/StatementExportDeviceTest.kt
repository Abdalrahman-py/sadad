package com.sadad.app.export

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sadad.ledger.Commands
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerState
import com.sadad.ledger.LedgerView
import com.sadad.ledger.Origin
import com.sadad.ledger.view
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatementExportDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val view = exportDeviceView(Commands.contactCreate(exportOrigin, Instant.EPOCH, "سامي"))
  private val header = StatementHeader("متجر", "مالك", "كل الفترات")

  @Test
  fun pdfGetsUniqueReadableContentUrisInTheExportsDirectory() =
      runBlocking(Dispatchers.IO) {
        val first = StatementExport.writePdf(context, header, view, null, null, null)
        val second = StatementExport.writePdf(context, header, view, null, null, null)
        try {
          assertNotEquals(first, second)
          assertEquals("content", first.scheme)
          assertEquals("${context.packageName}.files", first.authority)
          assertTrue(first.path!!.startsWith("/exports/"))
          context.contentResolver.openInputStream(first)!!.use {
            assertEquals("%PDF", it.readBytes().take(4).toByteArray().toString(Charsets.US_ASCII))
          }
        } finally {
          context.contentResolver.delete(first, null, null)
          context.contentResolver.delete(second, null, null)
        }
      }

  @Test
  fun chooserIsLaunchedOnMainWithOnlyReadPermission() =
      runBlocking(Dispatchers.IO) {
        var launched: Intent? = null
        val capture =
            object : ContextWrapper(context) {
              override fun startActivity(intent: Intent) {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                launched = intent
              }
            }
        StatementExport.sharePdf(capture, header, view, null, null, null)
        val chooser = launched!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        try {
          assertEquals(Intent.ACTION_SEND, send.action)
          assertEquals("application/pdf", send.type)
          assertEquals(
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
              send.flags and
                  (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
          )
          assertEquals(uri, send.clipData!!.getItemAt(0).uri)
          assertNotNull(chooser.clipData)
        } finally {
          context.contentResolver.delete(uri, null, null)
        }
      }
}

internal val exportOrigin =
    Origin("5e0f2a1b-3c4d-4e5f-8a6b-7c8d9e0f1a2b", 1, "0d1e2f3a-4b5c-4d6e-8f7a-8b9c0d1e2f3a")

internal fun exportDeviceView(vararg commands: LedgerCommand): LedgerView =
    LedgerState.EMPTY.view(commands.toList(), emptySet(), exportOrigin.installationId, "أحمد")
