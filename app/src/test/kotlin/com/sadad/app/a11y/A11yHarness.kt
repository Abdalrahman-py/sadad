package com.sadad.app.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.sadad.app.ui.theme.SadadTheme

/** Phone width the font-scale checks run at (ticket 76). */
internal const val PHONE_QUALIFIERS = "w360dp-h800dp-xxhdpi"

internal const val NORMAL_FONT = 1.0f
internal const val LARGEST_FONT = 2.0f

private const val MIN_TARGET_DP = 48f

/** Renders [content] like the shell does (Arabic, RTL) at the given system font scale. */
internal fun ComposeContentTestRule.showAt(fontScale: Float, content: @Composable () -> Unit) {
  setContent {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, fontScale),
        LocalLayoutDirection provides LayoutDirection.Rtl,
    ) {
      SadadTheme { content() }
    }
  }
}

/**
 * Checks everything on screen and fails once with every problem listed, so a red run records the
 * full count:
 * - each node with a click action has a 48 dp touch target and a spoken label (text, content
 *   description or click label);
 * - no text is cut off vertically;
 * - each [primary] action is displayed once the screen is scrolled to it.
 */
internal fun ComposeContentTestRule.assertAccessible(vararg primary: SemanticsMatcher) {
  waitForIdle()
  val problems = mutableListOf<String>()
  val minimum = MIN_TARGET_DP * density.density
  onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
    val bounds = node.touchBoundsInRoot
    if (bounds.width + 0.5f < minimum || bounds.height + 0.5f < minimum) {
      val size = "${bounds.width / density.density} x ${bounds.height / density.density} dp"
      problems += "touch target $size: ${describe(node)}"
    }
    if (!isSpoken(node)) problems += "no spoken label: ${describe(node)}"
  }
  onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))
      .fetchSemanticsNodes()
      .forEach { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        if (layouts.any { it.didOverflowHeight }) problems += "text cut off: ${describe(node)}"
      }
  primary.forEach { matcher ->
    val action = onNode(matcher)
    runCatching {
      if (generateSequence(action.fetchSemanticsNode()) { it.parent }.any(::scrolls)) {
        action.performScrollTo()
      }
      action.assertIsDisplayed()
    }
        .onFailure { problems += "primary action not reachable (${matcher.description}): $it" }
  }
  if (problems.isNotEmpty()) {
    // DIAGNOSTIC (temporary): Gradle's console shows only "Class at File.kt:line" for a failure, so
    // the problem list rides in the frame's file name.
    val text = "${problems.size} PROBLEMS: " + problems.joinToString(" || ").replace('\n', ' ')
    throw AssertionError(text).also { error ->
      error.stackTrace =
          Throwable()
              .stackTrace
              .filter {
                it.className.startsWith("com.sadad.app.a11y.") && "Harness" !in it.className
              }
              .map { StackTraceElement(it.className, it.methodName, text, it.lineNumber) }
              .toTypedArray()
    }
  }
}

private fun scrolls(node: SemanticsNode): Boolean = SemanticsActions.ScrollBy in node.config

private fun isSpoken(node: SemanticsNode): Boolean {
  val config = node.config
  return config.getOrNull(SemanticsProperties.ContentDescription)?.any(String::isNotBlank) ==
      true ||
      config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true ||
      config.getOrNull(SemanticsProperties.EditableText)?.text?.isNotBlank() == true ||
      config.getOrNull(SemanticsActions.OnClick)?.label?.isNotBlank() == true
}

private fun describe(node: SemanticsNode): String {
  val config = node.config
  val tag = config.getOrNull(SemanticsProperties.TestTag)
  val text = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
  return "tag=$tag text=$text bounds=${node.boundsInRoot}"
}
