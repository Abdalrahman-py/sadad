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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertTrue

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
 * - each placed node with a click action has a 48 dp touch target and a spoken label (text, content
 *   description or click label);
 * - no text is cut off vertically;
 * - each [primary] action is displayed once the screen is scrolled to it, and what is on screen at
 *   that scroll position is checked too.
 *
 * Every lazy list is scrolled item by item. A node that is composed but scrolled out of view has
 * empty bounds and is not checked until it is on screen.
 */
internal fun ComposeContentTestRule.assertAccessible(vararg primary: SemanticsMatcher) {
  waitForIdle()
  val problems = linkedMapOf<String, String>()
  inspect(problems)
  sweepLists(problems)
  primary.forEach { matcher ->
    runCatching {
      reveal(matcher).assertIsDisplayed()
      inspect(problems)
    }
        .onFailure {
          problems["primary:${matcher.description}"] =
              "primary action not reachable (${matcher.description}): $it"
        }
  }
  assertTrue(
      "${problems.size} accessibility problems:\n" + problems.values.joinToString("\n"),
      problems.isEmpty(),
  )
}

/** Brings each item of every lazy list on screen in turn, so rows far down are checked too. */
private fun ComposeContentTestRule.sweepLists(problems: MutableMap<String, String>) {
  val lists = onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes()
  lists.forEachIndexed { list, node ->
    val info = node.config.getOrNull(SemanticsProperties.CollectionInfo)
    if (info == null) problems["list:$list"] = "list $list does not say how many items it has"
    repeat(maxOf(info?.rowCount ?: 0, info?.columnCount ?: 0)) { index ->
      runCatching { onAllNodes(hasScrollToIndexAction())[list].performScrollToIndex(index) }
          .onFailure {
            problems["list:$list:$index"] = "list $list cannot scroll to item $index: $it"
          }
      waitForIdle()
      inspect(problems)
    }
  }
}

/** Scrolls to [matcher], composing it first when it sits further down a lazy list. */
private fun ComposeContentTestRule.reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
  val lists = onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size
  var index = 0
  while (onAllNodes(matcher).fetchSemanticsNodes().isEmpty() && index < lists) {
    runCatching { onAllNodes(hasScrollToNodeAction())[index].performScrollToNode(matcher) }
    index++
  }
  val action = onNode(matcher)
  if (generateSequence(action.fetchSemanticsNode().parent) { it.parent }.any(::scrolls)) {
    action.performScrollTo()
  }
  return action
}

private fun ComposeContentTestRule.inspect(problems: MutableMap<String, String>) {
  val minimum = MIN_TARGET_DP * density.density
  onAllNodes(hasClickAction())
      .fetchSemanticsNodes()
      .map(::bringIntoView)
      .filterNot { it.touchBoundsInRoot.isEmpty }
      .forEach { node ->
        val bounds = node.touchBoundsInRoot
        if (bounds.width + 0.5f < minimum || bounds.height + 0.5f < minimum) {
          val size = "${bounds.width / density.density} x ${bounds.height / density.density} dp"
          problems["target:${key(node)}"] = "touch target $size: ${describe(node)}"
        }
        if (!isSpoken(node)) problems["label:${key(node)}"] = "no spoken label: ${describe(node)}"
      }
  onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))
      .fetchSemanticsNodes()
      .filter { it.layoutInfo.isPlaced }
      .forEach { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        if (layouts.any { it.didOverflowHeight }) {
          problems["cut:${key(node)}"] = "text cut off: ${describe(node)}"
        }
      }
}

/**
 * A node inside a plain scrolling container may sit below the fold; it is scrolled into view before
 * it is measured, so that a half-hidden control is not mistaken for a small one. Lazy lists are
 * covered by [sweepLists], and a node they merely composed ahead of the viewport stays unchecked.
 */
private fun ComposeContentTestRule.bringIntoView(node: SemanticsNode): SemanticsNode {
  val scrollers = generateSequence(node.parent) { it.parent }.filter(::scrolls).toList()
  if (scrollers.isEmpty() || scrollers.any { SemanticsActions.ScrollToIndex in it.config }) {
    return node
  }
  val same = SemanticsMatcher("node ${node.id}") { it.id == node.id }
  runCatching { onNode(same).performScrollTo() }
  return runCatching { onNode(same).fetchSemanticsNode() }.getOrDefault(node)
}

private fun key(node: SemanticsNode): String {
  val config = node.config
  val tag = config.getOrNull(SemanticsProperties.TestTag)
  val text = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
  return "$tag/$text/${node.id}"
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
