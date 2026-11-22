package com.sadad.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * An M3 [Surface] bar pinned under a screen's content holding one or two full-width buttons, clear
 * of the navigation inset. Each child takes an equal share: give it `Modifier.weight(1f)`.
 */
@Composable
fun StickyActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  Surface(
      modifier = modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    // On the Row, so the line draws over the Surface fill.
    Row(
        modifier =
            Modifier.topHairline(MaterialTheme.colorScheme.outlineVariant)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
  }
}
