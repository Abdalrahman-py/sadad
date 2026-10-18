package com.sadad.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sadad.ledger.LedgerView
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StatementHeader(val store: String, val owner: String, val periodLabel: String)

enum class StatementFormat(val mimeType: String) {
  PDF("application/pdf"), XLSX(XlsxWriter.MIME),
}

object StatementExport {
  suspend fun save(context: Context, uri: Uri, format: StatementFormat, header: StatementHeader, view: LedgerView, contactId: String?, from: Instant?, to: Instant?) = withContext(Dispatchers.IO) {
    val statements = statements(view, contactId, from, to)
    val labels = ExportLabels.from(context)
    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open statement document")
    out.use { write(format, labels, header, statements, it) }
  }

  suspend fun writePdf(context: Context, header: StatementHeader, view: LedgerView, contactId: String?, from: Instant?, to: Instant?): Uri = withContext(Dispatchers.IO) {
    val statements = statements(view, contactId, from, to)
    val directory = File(context.cacheDir, "exports")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create exports directory")
    val file = File.createTempFile("statement-", ".pdf", directory)
    try {
      file.outputStream().use { write(StatementFormat.PDF, ExportLabels.from(context), header, statements, it) }
      FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (error: Throwable) {
      file.delete()
      throw error
    }
  }

  suspend fun sharePdf(context: Context, header: StatementHeader, view: LedgerView, contactId: String?, from: Instant?, to: Instant?) {
    val uri = writePdf(context, header, view, contactId, from, to)
    // Document creation is IO work; only launching Android's chooser belongs to the UI thread.
    withContext(Dispatchers.Main) {
      val send = Intent(Intent.ACTION_SEND).apply {
        type = StatementFormat.PDF.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("statement", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val chooser = Intent.createChooser(send, context.getString(ExportLabel.SHARE.resourceId)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = send.clipData
      }
      context.startActivity(chooser)
    }
  }

  private fun statements(view: LedgerView, contactId: String?, from: Instant?, to: Instant?): List<ContactStatement> =
      Statement.build(view, contactId, from, to).also { require(it.isNotEmpty()) { "No contacts to export" } }

  private fun write(format: StatementFormat, labels: ExportLabels, header: StatementHeader, statements: List<ContactStatement>, out: OutputStream) {
    when (format) {
      StatementFormat.PDF -> PdfWriter(labels).write(statements, header.store, header.owner, header.periodLabel, out)
      StatementFormat.XLSX -> XlsxWriter(labels).write(statements, header.store, header.owner, header.periodLabel, out)
    }
  }
}
