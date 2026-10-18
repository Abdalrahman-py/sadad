package com.sadad.app.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.ui.ScreenTag
import com.sadad.app.ui.components.PlaceholderScreen

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
  PlaceholderScreen(
      title = stringResource(R.string.title_home),
      modifier = modifier,
      tag = ScreenTag.HOME,
  )
}
