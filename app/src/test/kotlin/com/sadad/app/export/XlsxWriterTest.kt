package com.sadad.app.export

import com.sadad.app.data.db.Events
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class XlsxWriterTest {
  @Test
  fun workbookHasUniqueArabicRtlSheetsWithExactNumericMoney() {
    val at = Instant.parse("2026-10-01T00:00:00Z")
    val first = Commands.contactCreate(Events.origin, at, "سامي")
    val second = Commands.contactCreate(Events.origin, at, "سامي")
    val debt =
        Commands.debtCreate(
            Events.origin,
            at,
            first.entityId,
            Direction.RECEIVABLE,
            Cents(12345),
            "<وصف> & تفصيل",
        )
    val statements = Statement.build(exportView(first, second, debt), null, null, null)
    val out = ByteArrayOutputStream()
    XlsxWriter(testLabels()).write(statements, "متجر", "مالك", "أكتوبر", out)
    val entries = unzip(out.toByteArray())
    val sheets = xml(entries.getValue("xl/workbook.xml")).getElementsByTagName("sheet")
    assertEquals(2, sheets.length)
    val names = (0 until sheets.length).map { (sheets.item(it) as Element).getAttribute("name") }
    assertEquals(listOf("سامي", "سامي (2)"), names)
    for (index in 1..2) {
      val sheet = xml(entries.getValue("xl/worksheets/sheet$index.xml"))
      assertEquals(
          "1",
          (sheet.getElementsByTagName("sheetView").item(0) as Element).getAttribute("rightToLeft"),
      )
      assertEquals(1, sheet.getElementsByTagName("autoFilter").length)
    }
    val firstSheet = xml(entries.getValue("xl/worksheets/sheet1.xml"))
    val cells = firstSheet.getElementsByTagName("c")
    assertTrue(
        (0 until cells.length).any {
          val cell = cells.item(it) as Element
          cell.getAttribute("t") == "n" && cell.textContent == "123.45"
        }
    )
    assertTrue(firstSheet.documentElement.textContent.contains("<وصف> & تفصيل"))
    assertTrue(entries.containsKey("[Content_Types].xml"))
    entries.values.forEach { xml(it) }
  }

  @Test
  fun sheetNamesAreSafeShortAndCaseInsensitiveUnique() {
    val at = Instant.EPOCH
    val names =
        listOf(
            "A/B:*?[]\\'",
            "a-b-----",
            "😀".repeat(50),
            "😀".repeat(50),
            "History",
            "سامي\nأحمد",
            "سامي أحمد",
            "سامي\tأحمد",
            "سامي\rأحمد",
        )
    val contacts = names.map { Commands.contactCreate(Events.origin, at, it) }
    val out = ByteArrayOutputStream()
    XlsxWriter(testLabels())
        .write(
            Statement.build(exportView(*contacts.toTypedArray()), null, null, null),
            "",
            "",
            "",
            out,
        )
    val sheets =
        xml(unzip(out.toByteArray()).getValue("xl/workbook.xml")).getElementsByTagName("sheet")
    val actual = (0 until sheets.length).map { (sheets.item(it) as Element).getAttribute("name") }
    assertEquals(actual.size, actual.map { it.lowercase() }.toSet().size)
    actual.forEach {
      assertTrue(it.length in 1..31)
      assertFalse(it.any { char -> char in "\\/:*?[]" })
      assertFalse(it.equals("History", ignoreCase = true))
      assertFalse(Character.isHighSurrogate(it.last()))
    }
  }

  @Test
  fun doesNotCloseTheCallersStreamAndSerializesLargeCentsExactly() {
    val at = Instant.EPOCH
    val contact = Commands.contactCreate(Events.origin, at, "زبون")
    val debt =
        Commands.debtCreate(
            Events.origin,
            at,
            contact.entityId,
            Direction.RECEIVABLE,
            Cents.MAX_WIRE,
        )
    var closed = false
    val out =
        object : ByteArrayOutputStream() {
          override fun close() {
            closed = true
          }
        }
    XlsxWriter(testLabels())
        .write(Statement.build(exportView(contact, debt), null, null, null), "", "", "", out)
    assertFalse(closed)
    assertTrue(
        unzip(out.toByteArray()).getValue("xl/worksheets/sheet1.xml").contains("90071992547409.91")
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun noContactsDoesNotProduceAnInvalidWorkbook() {
    XlsxWriter(testLabels()).write(emptyList(), "", "", "", ByteArrayOutputStream())
  }
}

internal fun unzip(bytes: ByteArray): Map<String, String> =
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      buildMap {
        while (true) {
          val entry = zip.nextEntry ?: break
          put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
        }
      }
    }

internal fun xml(text: String): Document =
    DocumentBuilderFactory.newInstance()
        .apply {
          setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

internal fun testLabels(): ExportLabels = ExportLabels { key ->
  when (key) {
    ExportLabel.DATE -> "التاريخ"
    ExportLabel.MOVEMENT -> "الحركة"
    ExportLabel.DEBT_COLUMN -> "دين ₪"
    ExportLabel.PAYMENT_COLUMN -> "دفعة ₪"
    ExportLabel.BALANCE_COLUMN -> "الرصيد ₪"
    ExportLabel.DUE_DATE -> "الاستحقاق"
    ExportLabel.DESCRIPTION -> "البيان"
    ExportLabel.OPENING -> "افتتاحي"
    ExportLabel.DEBT -> "دين"
    ExportLabel.PAYMENT -> "دفعة"
    ExportLabel.TOTAL -> "الإجمالي"
    else -> key.name
  }
}
