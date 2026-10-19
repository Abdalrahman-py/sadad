package com.sadad.app.export

import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxWriter(private val labels: ExportLabels) {
  fun write(
      statements: List<ContactStatement>,
      store: String,
      owner: String,
      period: String,
      out: OutputStream,
  ) {
    require(statements.isNotEmpty()) { "No contacts to export" }
    val names = sheetNames(statements.map { it.contact.name })
    val borrowed =
        object : FilterOutputStream(out) {
          override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length)
          }

          override fun close() {
            flush()
          }
        }
    ZipOutputStream(borrowed).use { zip ->
      zip.entry(
          "[Content_Types].xml",
          buildString {
            append(
                """$XML<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>"""
            )
            for (id in 1..statements.size) append(
                """<Override PartName="/xl/worksheets/sheet$id.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
            )
            append("</Types>")
          },
      )
      zip.entry(
          "_rels/.rels",
          """$XML<Relationships xmlns="$PACKAGE_REL"><Relationship Id="workbook" Type="$OFFICE_REL/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
      )
      zip.entry(
          "xl/workbook.xml",
          buildString {
            append(
                """$XML<workbook xmlns="$MAIN" xmlns:r="$OFFICE_REL"><bookViews><workbookView/></bookViews><sheets>"""
            )
            names.forEachIndexed { index, name ->
              val id = index + 1
              append("""<sheet name="${escape(name)}" sheetId="$id" r:id="rId$id"/>""")
            }
            append("</sheets></workbook>")
          },
      )
      zip.entry(
          "xl/_rels/workbook.xml.rels",
          buildString {
            append("""$XML<Relationships xmlns="$PACKAGE_REL">""")
            for (id in 1..statements.size) append(
                """<Relationship Id="rId$id" Type="$OFFICE_REL/worksheet" Target="worksheets/sheet$id.xml"/>"""
            )
            append(
                """<Relationship Id="styles" Type="$OFFICE_REL/styles" Target="styles.xml"/></Relationships>"""
            )
          },
      )
      zip.entry("xl/styles.xml", styles())
      statements.forEachIndexed { index, statement ->
        zip.entry("xl/worksheets/sheet${index + 1}.xml", sheet(statement, store, owner, period))
      }
    }
  }

  private fun sheet(
      statement: ContactStatement,
      store: String,
      owner: String,
      period: String,
  ): String = buildString {
    append(
        """$XML<worksheet xmlns="$MAIN"><sheetViews><sheetView workbookViewId="0" rightToLeft="1"><pane ySplit="6" topLeftCell="A7" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="23" customWidth="1"/><col min="2" max="6" width="18" customWidth="1"/><col min="7" max="7" width="48" customWidth="1"/></cols><sheetData>"""
    )
    row(
        1,
        TableRow(
            listOf("${labels[ExportLabel.STORE]}: $store", "${labels[ExportLabel.OWNER]}: $owner")
        ),
    )
    row(
        2,
        TableRow(
            listOf(
                "${labels[ExportLabel.CONTACT]}: ${statement.contact.name}",
                "${labels[ExportLabel.PHONE]}: ${statement.contact.phone}",
                "${labels[ExportLabel.CATEGORY]}: ${statement.contact.category}",
            )
        ),
    )
    row(3, TableRow(listOf("${labels[ExportLabel.PERIOD]}: $period")))
    row(
        4,
        TableRow(
            listOf(labels[ExportLabel.OPENING], "", "", "", statement.opening.decimal()),
            setOf(4),
        ),
    )
    row(5, TableRow(listOf("${labels[ExportLabel.CONTACT_NOTE]}: ${statement.contact.note}")))
    row(6, TableRow(labels.columns), header = true)
    var number = 7
    for (tableRow in statement.tableRows(labels)) row(number++, tableRow)
    // Exclude the totals row; even an empty period retains a filterable opening row.
    append(
        """</sheetData><autoFilter ref="A6:G${number - 2}"/><mergeCells count="1"><mergeCell ref="A5:G5"/></mergeCells><pageMargins left="0.3" right="0.3" top="0.5" bottom="0.5" header="0.2" footer="0.2"/></worksheet>"""
    )
  }

  private fun StringBuilder.row(number: Int, row: TableRow, header: Boolean = false) {
    append("""<row r="$number">""")
    row.cells.forEachIndexed { column, value ->
      if (value.isEmpty()) return@forEachIndexed
      val numeric = column in row.numeric
      val style = if (header) 1 else if (numeric) 2 else 0
      append("""<c r="${('A'.code + column).toChar()}$number" s="$style"""")
      if (numeric) append(""" t="n"><v>$value</v></c>""")
      else append(""" t="inlineStr"><is><t xml:space="preserve">${escape(value)}</t></is></c>""")
    }
    append("</row>")
  }

  private fun sheetNames(names: List<String>): List<String> {
    val used = mutableSetOf<String>()
    return names.map { original ->
      val base =
          cleanXml(original)
              .replace(Regex("[\t\r\n]"), " ")
              .replace(Regex("[\\\\/\\[\\]:*?]"), "-")
              .replace("'", "")
              .trim()
              .ifEmpty { labels[ExportLabel.CONTACT] }
      var suffix = ""
      var index = 1
      var name: String
      do {
        name = truncate(base, 31 - suffix.length) + suffix
        suffix = " (${++index})"
      } while (name.equals("History", ignoreCase = true) || !used.add(name.lowercase(Locale.ROOT)))
      name
    }
  }

  private fun truncate(text: String, limit: Int): String {
    var end = minOf(text.length, limit)
    if (end < text.length && end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
  }

  private fun ZipOutputStream.entry(name: String, xml: String) {
    putNextEntry(ZipEntry(name))
    write(xml.toByteArray(Charsets.UTF_8))
    closeEntry()
  }

  private fun styles(): String =
      """
      $XML<styleSheet xmlns="$MAIN">
      <numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00 &quot;₪&quot;"/></numFmts>
      <fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Arial"/></font></fonts>
      <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF064B42"/><bgColor indexed="64"/></patternFill></fill></fills>
      <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
      <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
      <cellXfs count="3">
      <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"><alignment horizontal="right" vertical="top" wrapText="1"/></xf>
      <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0"><alignment horizontal="right" wrapText="1"/></xf>
      <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"><alignment horizontal="right"/></xf>
      </cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
      </styleSheet>
      """
          .trimIndent()

  companion object {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun cleanXml(value: String): String = buildString {
      value.codePoints().forEach { cp ->
        if (
            cp == 9 ||
                cp == 10 ||
                cp == 13 ||
                cp in 0x20..0xD7FF ||
                cp in 0xE000..0xFFFD ||
                cp in 0x10000..0x10FFFF
        )
            appendCodePoint(cp)
      }
    }

    private fun escape(value: String): String =
        cleanXml(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
  }
}
