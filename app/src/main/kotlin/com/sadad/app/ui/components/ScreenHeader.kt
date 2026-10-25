package com.sadad.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.R
import com.sadad.app.ui.theme.sadadPalette

/**
 * The header each screen draws itself now the shell has no top bar: an optional back arrow, the
 * title, then [actions] at the far end. 64dp tall, 60dp with a back arrow, as the boards draw it;
 * the back arrow takes the 48dp touch target, not the boards' 44.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .heightIn(min = if (onBack == null) 64.dp else 60.dp)
              .padding(horizontal = if (onBack == null) 18.dp else 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      val back = stringResource(R.string.common_back)
      Box(
          modifier =
              Modifier.size(48.dp)
                  .clip(CircleShape)
                  .clickable(role = Role.Button, onClick = onBack)
                  .semantics { contentDescription = back },
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(R.drawable.ic_back),
            contentDescription = null,
            tint = sadadPalette.text,
            modifier = Modifier.size(24.dp),
        )
      }
    }
    Text(
        title,
        modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
        color = sadadPalette.text,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    actions()
  }
}
