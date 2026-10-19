package com.sadad.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
  @Test
  fun paginates120RowsWithRepeatedHeadersAndNewPagesForEveryContact() {
    val layout = PdfLayout()
    val pages = layout.paginate(listOf(List(120) { 28 }, listOf(28, 28)))
    assertEquals(7, pages.count { it.contactIndex == 0 })
    assertEquals(
        (0 until 120).toList(),
        pages.filter { it.contactIndex == 0 }.flatMap { it.rows.toList() },
    )
    assertEquals(1, pages.count { it.contactIndex == 1 })
    assertEquals(listOf(0, 1), pages.last().rows.toList())
    assertTrue(pages.all { it.repeatHeader && it.rows.count() <= layout.capacity / 28 })
  }

  @Test
  fun exactBoundaryFitsAndVariableHeightMovesToNextPage() {
    val layout = PdfLayout()
    val pages = layout.paginate(listOf(listOf(layout.capacity - 28, 28, 29)))
    assertEquals(listOf(0, 1), pages.first().rows.toList())
    assertEquals(listOf(2), pages.last().rows.toList())
  }

  @Test(expected = IllegalArgumentException::class)
  fun oversizedRowsMustBeSplitBeforePagination() {
    val layout = PdfLayout()
    layout.paginate(listOf(listOf(layout.capacity + 1)))
  }

  @Test
  fun emptyContactStillStartsItsOwnPage() {
    assertEquals(2, PdfLayout().paginate(listOf(emptyList(), emptyList())).size)
  }
}
