package com.sadad.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.ui.theme.sadadPalette

/** A name's initials on `soft`, 40dp; decorative, the row beside it says the name. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) {
  val initials = name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull() }
  Box(
      modifier =
          modifier.size(40.dp).background(sadadPalette.soft, CircleShape).clearAndSetSemantics {},
      contentAlignment = Alignment.Center,
  ) {
    Text(
        initials.joinToString(""),
        color = sadadPalette.muted,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
  }
}
