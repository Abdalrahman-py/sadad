package com.sadad.app.feature.debt

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sadad.app.R
import com.sadad.app.ui.components.PlaceholderScreen

@Composable
fun DebtFormScreen(contactId: String, modifier: Modifier = Modifier) {
  PlaceholderScreen(
      title = stringResource(R.string.title_debt),
      modifier = modifier,
      detail = contactId,
  )
}
