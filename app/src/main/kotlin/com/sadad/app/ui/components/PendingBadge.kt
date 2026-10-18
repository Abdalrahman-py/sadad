package com.sadad.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sadad.app.R

/** Marks a row the server has not confirmed yet. */
@Composable
fun PendingBadge(modifier: Modifier = Modifier) {
  val description = stringResource(R.string.pending_badge)
  Box(
      modifier =
          modifier
              .size(10.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.tertiary)
              .semantics {
                contentDescription = description
              }
  )
}
