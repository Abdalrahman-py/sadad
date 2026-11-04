package com.sadad.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules every feature screen keeps (ticket 143): weights come from the theme styles, never
 * `FontWeight.Bold` or heavier by hand, and spacing sits on the 4dp grid — 1dp and 2dp hairlines
 * stay, any other off-grid value needs a one-line comment.
 */
class StyleScanTest {
  @Test
  fun featureScreensSetNoHandWeights() {
    val offenders = featureFiles().flatMap { file -> bolds(file) }
    assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
  }

  @Test
  fun featureScreensSitOnThe4dpGrid() {
    val offenders = featureFiles().flatMap { file -> offGrid(file) }
    assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
  }

  /** A press effect fills its layout bounds unless a clip comes first: the square flash. */
  @Test
  fun everyPressEffectFollowsItsShape() {
    val dirs = listOf(featureDir(), featureDir().resolveSibling("ui"))
    val files = dirs.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" } }
    val offenders = files.flatMap { file -> unclipped(file) }
    assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
  }

  // Hand strokes like 1.5.dp stay; the integer before `.dp` is the value scanned.

  /** `file:line` for every press target with no clip just before it and its own indication. */
  private fun unclipped(file: File): List<String> {
    val lines = file.readLines()
    return lines.indices
        .filter { lines[it].contains(Regex("\\.(clickable|selectable|toggleable)\\(")) }
        .filterNot { index ->
          lines.subList(maxOf(0, index - 8), index).any { it.contains(".clip(") } ||
              lines.subList(index, minOf(lines.size, index + 5)).any {
                it.contains("indication = null")
              }
        }
        .map { "${file.name}:${it + 1}: ${lines[it]}" }
  }

  /** `file:line` for every hand-set heavy weight in a screen file. */
  private fun bolds(file: File) =
      file
          .readLines()
          .withIndex()
          .filter { (_, line) -> line.contains(Regex("FontWeight.(Bold|ExtraBold|Black)")) }
          .map { (index, line) -> "${file.name}:${index + 1}: $line" }

  /** `file:line` for every `N.dp` whose N is off the 4dp grid, 1 and 2 apart. */
  private fun offGrid(file: File) =
      file.readLines().withIndex().flatMap { (index, line) ->
        Regex("(?<![\\d.])(\\d+)\\.dp")
            .findAll(line)
            .filter {
              it.groupValues[1].toInt().let { n -> n % 4 != 0 && n > 2 }
            }
            .map {
              "${file.name}:${index + 1}: $line"
            }
      }

  private fun featureFiles(): List<File> =
      featureDir()
          .walkTopDown()
          .filter { it.isFile && it.extension == "kt" }
          .sorted()
          .toList()
          .also { assertTrue("feature/ screens not found", it.isNotEmpty()) }

  private fun featureDir(): File {
    var dir: File? = File(".").absoluteFile
    repeat(4) {
      val candidate = dir?.resolve("app/src/main/kotlin/com/sadad/app/feature")
      if (candidate?.isDirectory == true) return candidate
      dir = dir?.parentFile
    }
    throw AssertionError("feature/ source dir not found under ${File(".").absolutePath}")
  }
}
