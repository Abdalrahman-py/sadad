package com.sadad.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.R
import com.sadad.app.ui.theme.sadadPalette

/**
 * The boards' empty card: an icon on a 72dp `soft` tile, a bold title and a muted hint, centred on
 * a white 18dp card.
 */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    icon: Int = R.drawable.ic_ledger,
) {
  val p = sadadPalette
  Column(
      modifier =
          modifier
              .background(p.card, RoundedCornerShape(18.dp))
              .border(1.dp, p.line, RoundedCornerShape(18.dp))
              .padding(horizontal = 20.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(
        Modifier.size(72.dp).background(p.soft, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(icon),
          contentDescription = null,
          tint = if (p.isDark) p.accent else p.primary,
          modifier = Modifier.size(36.dp),
      )
    }
    Text(
        text = title,
        color = p.text,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Text(
        text = detail,
        color = p.muted,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        textAlign = TextAlign.Center,
    )
  }
}
