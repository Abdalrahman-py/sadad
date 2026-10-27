package com.sadad.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.ui.theme.sadadPalette

/** One pill: its value, its label and an optional count shown after the label. */
data class Chip<T>(val value: T, val label: String, val count: Int? = null)

/**
 * A horizontally scrolling single-select row of pill chips; each chip reports its selected state,
 * the `aria-pressed` of the boards, read as one choice of a group.
 */
@Composable
fun <T> ChipRow(
    chips: List<Chip<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tagFor: (T) -> String? = { null },
    horizontalPadding: Dp = 18.dp,
) {
  Row(
      modifier =
          modifier
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = horizontalPadding)
              .selectableGroup(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (chip in chips) {
      PillChip(chip, chip.value == selected, tagFor(chip.value)) { onSelect(chip.value) }
    }
  }
}

/** The same pills wrapped over as many lines as they need, for a list that should all be seen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipFlow(
    chips: List<Chip<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
  FlowRow(
      modifier = modifier.fillMaxWidth().selectableGroup(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (chip in chips) PillChip(chip, chip.value == selected, null) { onSelect(chip.value) }
  }
}

@Composable
private fun <T> PillChip(chip: Chip<T>, on: Boolean, tag: String?, onClick: () -> Unit) {
  val p = sadadPalette
  val shape = RoundedCornerShape(18.dp)
  // The chosen pill fills in over 180ms instead of snapping.
  val fill by animateColorAsState(if (on) p.primary else p.card, tween(180), label = "chip")
  val edge by animateColorAsState(if (on) p.primary else p.line, tween(180), label = "chipEdge")
  Row(
      modifier =
          // 36dp pill, 48dp touch target.
          Modifier.minimumInteractiveComponentSize()
              .heightIn(min = 36.dp)
              .clip(shape)
              .background(fill)
              .border(BorderStroke(1.dp, edge), shape)
              .then(if (tag != null) Modifier.testTag(tag) else Modifier)
              .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
              .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    val ink = if (on) Color.White else p.text
    Text(chip.label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    if (chip.count != null)
        Text(chip.count.toString(), color = ink.copy(alpha = 0.85f), fontSize = 13.sp)
  }
}
