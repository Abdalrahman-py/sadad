package com.sadad.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.sadad.app.ui.theme.sadadPalette

/**
 * A bar pinned under a screen's content holding one or two full-width buttons, clear of the
 * navigation inset. Each child takes an equal share: give it `Modifier.weight(1f)`.
 */
@Composable
fun StickyActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .shadow(8.dp)
              .background(sadadPalette.card)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(horizontal = 18.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
  )
}
