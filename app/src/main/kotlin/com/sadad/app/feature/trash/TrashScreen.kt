package com.sadad.app.feature.trash

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.ui.components.PlaceholderScreen

@Composable
fun TrashScreen(modifier: Modifier = Modifier) {
  PlaceholderScreen(title = stringResource(R.string.title_trash), modifier = modifier)
}
