package com.sadad.app.export

data class StatementPage(val contactIndex: Int, val rows: IntRange, val repeatHeader: Boolean = true)

/** Geometry in A4 points shared by the renderer and JVM pagination tests. */
class PdfLayout {
  val width = 595
  val height = 842
  val margin = 36
  val tableTop = 240
  val tableHeaderHeight = 30
  val bottom = height - 52
  val capacity = bottom - tableTop - tableHeaderHeight
  val columns = listOf(74, 45, 58, 58, 66, 74, 148)

  fun paginate(contactRows: List<List<Int>>): List<StatementPage> = buildList {
    contactRows.forEachIndexed { contactIndex, heights ->
      var start = 0
      var used = 0
      heights.forEachIndexed { index, rowHeight ->
        require(rowHeight in 1..capacity) { "Split oversized rows before pagination" }
        if (used + rowHeight > capacity) {
          add(StatementPage(contactIndex, start until index))
          start = index
          used = 0
        }
        used += rowHeight
      }
      add(StatementPage(contactIndex, start until heights.size))
    }
  }
}
