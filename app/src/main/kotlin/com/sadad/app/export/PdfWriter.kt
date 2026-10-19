package com.sadad.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import java.io.OutputStream

class PdfWriter(private val labels: ExportLabels) {
  private val geometry = PdfLayout()
  private val ink = Color.rgb(20, 51, 42)
  private val green = Color.rgb(6, 75, 66)
  private val border = Color.rgb(199, 221, 211)

  fun write(
      statements: List<ContactStatement>,
      store: String,
      owner: String,
      period: String,
      out: OutputStream,
  ) {
    require(statements.isNotEmpty()) { "No contacts to export" }
    val document = PdfDocument()
    try {
      var pageNumber = 0
      for (statement in statements) {
        val rows = statement.tableRows(labels).flatMap { splitRow(it).asSequence() }.toList()
        val pages = geometry.paginate(listOf(rows.map { it.height }))
        for (page in pages) {
          val current =
              document.startPage(
                  PdfDocument.PageInfo.Builder(geometry.width, geometry.height, ++pageNumber)
                      .create()
              )
          try {
            val canvas = current.canvas
            header(canvas, statement, store, owner, period, pageNumber)
            val heads =
                labels.columns.mapIndexed { column, value ->
                  cell(value, column, bold = true, header = true)
                }
            drawRow(canvas, heads, geometry.tableTop, geometry.tableHeaderHeight, green)
            var y = geometry.tableTop + geometry.tableHeaderHeight
            for (index in page.rows) {
              val row = rows[index]
              val fill =
                  if (row.total) Color.rgb(213, 239, 228)
                  else if (index % 2 == 1) Color.rgb(243, 250, 247) else Color.WHITE
              drawRow(canvas, row.cells, y, row.height, fill)
              y += row.height
            }
          } finally {
            document.finishPage(current)
          }
        }
      }
      document.writeTo(out)
    } finally {
      document.close()
    }
  }

  private data class RenderRow(val cells: List<StaticLayout>, val height: Int, val total: Boolean)

  /** Split on actual laid-out line boundaries, including unbroken text and surrogate pairs. */
  private fun splitRow(row: TableRow): List<RenderRow> {
    val result = mutableListOf<RenderRow>()
    var remaining = row.cells
    do {
      val parts = mutableListOf<String>()
      val rest = mutableListOf<String>()
      remaining.forEachIndexed { column, value ->
        val full = cell(value, column, row.total)
        var lines = 0
        // A split after a newline creates a trailing empty line in the fragment's layout.
        val spareLine = full.paint.fontMetricsInt.let { it.descent - it.ascent }
        while (
            lines < full.lineCount &&
                full.getLineBottom(lines) <= geometry.capacity - 16 - spareLine
        ) lines++
        check(lines > 0) { "A PDF line must fit within the page body" }
        var end = full.getLineEnd(lines - 1)
        if (end < value.length && end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        parts += value.substring(0, end)
        rest += value.substring(end)
      }
      val layouts = parts.mapIndexed { column, value -> cell(value, column, row.total) }
      val height = maxOf(28, layouts.maxOf { it.height } + 16)
      result += RenderRow(layouts, height, row.total)
      remaining =
          if (rest.any { it.isNotEmpty() })
              rest.toMutableList().apply {
                if (this[1].isEmpty()) this[1] = labels[ExportLabel.CONTINUED]
              }
          else emptyList()
    } while (remaining.isNotEmpty())
    return result
  }

  private fun cell(
      value: String,
      column: Int,
      bold: Boolean = false,
      header: Boolean = false,
  ): StaticLayout {
    val paint = textPaint(if (header) 10f else 11f, if (header) Color.WHITE else ink, bold)
    if (!header && column in 2..5) {
      while (paint.textSize > 7f && paint.measureText(value) > geometry.columns[column] - 10) paint
          .textSize -= 1f
    }
    return layout(value, geometry.columns[column] - 10, paint)
  }

  private fun drawRow(canvas: Canvas, cells: List<StaticLayout>, top: Int, height: Int, fill: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var right = (geometry.width - geometry.margin).toFloat()
    cells.forEachIndexed { column, text ->
      val left = right - geometry.columns[column]
      paint.style = Paint.Style.FILL
      paint.color = fill
      canvas.drawRect(left, top.toFloat(), right, (top + height).toFloat(), paint)
      paint.style = Paint.Style.STROKE
      paint.color = border
      paint.strokeWidth = 0.7f
      canvas.drawRect(left, top.toFloat(), right, (top + height).toFloat(), paint)
      canvas.save()
      canvas.translate(left + 5f, top + (height - text.height) / 2f)
      text.draw(canvas)
      canvas.restore()
      right = left
    }
  }

  private fun header(
      canvas: Canvas,
      statement: ContactStatement,
      store: String,
      owner: String,
      period: String,
      number: Int,
  ) {
    canvas.drawColor(Color.WHITE)
    canvas.drawRect(0f, 0f, geometry.width.toFloat(), 70f, Paint().apply { color = green })
    block(canvas, labels[ExportLabel.TITLE], 18, 38, 22f, bold = true, color = Color.WHITE)
    block(canvas, "${labels[ExportLabel.STORE]}: $store", 80, 28, 12f)
    block(canvas, "${labels[ExportLabel.OWNER]}: $owner", 108, 24, 11f)
    block(
        canvas,
        "${labels[ExportLabel.CONTACT]}: ${statement.contact.name}",
        132,
        32,
        14f,
        bold = true,
    )
    block(canvas, "${labels[ExportLabel.PHONE]}: ${statement.contact.phone}", 164, 22, 11f)
    block(canvas, "${labels[ExportLabel.PERIOD]}: $period", 186, 26, 11f)
    block(
        canvas,
        "${labels[ExportLabel.OPENING]}: ${statement.opening.decimal()} ₪   |   ${labels[ExportLabel.CLOSING_DESCRIPTION]}: ${statement.closing.decimal()} ₪",
        212,
        26,
        11f,
        bold = true,
    )
    canvas.drawLine(
        geometry.margin.toFloat(),
        802f,
        (geometry.width - geometry.margin).toFloat(),
        802f,
        Paint().apply { color = border },
    )
    block(canvas, "${statement.contact.name} | ${labels[ExportLabel.PAGE]} $number", 810, 24, 10f)
  }

  private fun block(
      canvas: Canvas,
      value: String,
      top: Int,
      height: Int,
      size: Float,
      bold: Boolean = false,
      color: Int = ink,
  ) {
    val paint = textPaint(size, color, bold)
    val width = geometry.width - 2 * geometry.margin
    val text =
        StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxOf(1, height / paint.fontMetricsInt.let { it.descent - it.ascent }))
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    canvas.save()
    canvas.translate(geometry.margin.toFloat(), top.toFloat())
    text.draw(canvas)
    canvas.restore()
  }

  private fun textPaint(size: Float, color: Int, bold: Boolean) =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
      }

  private fun layout(value: String, width: Int, paint: TextPaint): StaticLayout =
      StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
          .setAlignment(Layout.Alignment.ALIGN_NORMAL)
          .setTextDirection(TextDirectionHeuristics.RTL)
          .setIncludePad(false)
          .build()
}
