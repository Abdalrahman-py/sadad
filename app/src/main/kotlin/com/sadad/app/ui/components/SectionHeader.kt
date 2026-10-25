package com.sadad.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.ui.theme.sadadPalette

/** A section title, an optional [count] pill, and an optional trailing link («عرض الكل»). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    linkLabel: String? = null,
    onLink: () -> Unit = {},
) {
  val p = sadadPalette
  Row(
      modifier = modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        color = p.text,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
    )
    if (count != null) {
      Box(
          Modifier.heightIn(min = 22.dp)
              .background(p.owesSoft, RoundedCornerShape(11.dp))
              .padding(horizontal = 7.dp),
          contentAlignment = Alignment.Center,
      ) {
        Text(count.toString(), color = p.owes, fontSize = 12.sp)
      }
    }
    Box(Modifier.weight(1f))
    if (linkLabel != null) {
      Text(
          linkLabel,
          modifier =
              Modifier.heightIn(min = 48.dp)
                  .clickable(role = Role.Button, onClick = onLink)
                  .padding(horizontal = 4.dp, vertical = 12.dp),
          color = if (p.isDark) p.accent else p.primary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
      )
    }
  }
}
